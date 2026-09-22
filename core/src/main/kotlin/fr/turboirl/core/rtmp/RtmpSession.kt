package fr.turboirl.core.rtmp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom

/** One RTMP connection, server side, publish only. [run] blocks until the peer goes away. */
class RtmpSession(
    private val socket: Socket,
    private val listener: RtmpListener,
    private val logger: Logger,
    private val limiter: ReadLimiter = ReadLimiter(),
) {
    private class ChunkStream {
        var timestamp = 0L
        var tsDelta = 0L
        var length = 0
        var typeId = 0
        var streamId = 0
        var extendedTs = false
        var payload: ByteArray? = null
        var read = 0
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }
    }

    private val counter = CountingInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
    private val input = DataInputStream(counter)
    private val output = BufferedOutputStream(socket.getOutputStream())
    private val chunkStreams = HashMap<Int, ChunkStream>()
    private val remote = socket.remoteSocketAddress.toString().removePrefix("/")

    private var inChunkSize = 128
    private var outChunkSize = 128
    private var ackWindow = DEFAULT_WINDOW
    private var lastAckAt = 0L
    private var app = ""
    private var publishing = false

    val bytesReceived: Long get() = counter.count

    fun run() {
        var reason = "connexion fermée"
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = READ_TIMEOUT_MS
            handshake()
            while (true) readChunk()
        } catch (e: EndOfStream) {
            reason = e.message ?: reason
        } catch (e: SocketTimeoutException) {
            reason = "aucune donnée depuis ${READ_TIMEOUT_MS / 1000} s"
        } catch (e: EOFException) {
            reason = "connexion fermée par la caméra"
        } catch (e: IOException) {
            reason = "erreur réseau : ${e.message}"
        } finally {
            close()
            if (publishing) {
                publishing = false
                listener.onPublishEnd(reason)
            }
            logger.log("RTMP $remote : $reason")
        }
    }

    fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private class EndOfStream(msg: String) : IOException(msg)

    private fun handshake() {
        val c0 = input.readUnsignedByte()
        if (c0 != 3) throw IOException("version RTMP inattendue : $c0")
        val c1 = ByteArray(HANDSHAKE_SIZE)
        input.readFully(c1)

        val s1 = ByteArray(HANDSHAKE_SIZE)
        SecureRandom().nextBytes(s1)
        for (i in 0 until 8) s1[i] = 0
        output.write(3)
        output.write(s1)
        output.write(c1)
        output.flush()

        input.readFully(ByteArray(HANDSHAKE_SIZE)) // C2
    }

    private fun readChunk() {
        val first = input.readUnsignedByte()
        val fmt = first shr 6
        var csid = first and 0x3F
        if (csid == 0) {
            csid = 64 + input.readUnsignedByte()
        } else if (csid == 1) {
            csid = 64 + input.readUnsignedByte() + (input.readUnsignedByte() shl 8)
        }
        val cs = chunkStreams.getOrPut(csid) { ChunkStream() }

        if (fmt <= 2) {
            val tsField = readU24()
            if (fmt <= 1) {
                cs.length = readU24()
                cs.typeId = input.readUnsignedByte()
            }
            if (fmt == 0) cs.streamId = Integer.reverseBytes(input.readInt())
            cs.extendedTs = tsField == 0xFFFFFF
            val ts = if (cs.extendedTs) input.readInt().toLong() and 0xFFFFFFFFL else tsField.toLong()
            cs.tsDelta = ts
            cs.timestamp = if (fmt == 0) ts else (cs.timestamp + ts) and 0xFFFFFFFFL
            // A new header in the middle of a message means the peer aborted it.
            cs.payload = null
        } else {
            if (cs.extendedTs) input.readInt()
            if (cs.payload == null) cs.timestamp = (cs.timestamp + cs.tsDelta) and 0xFFFFFFFFL
        }

        if (cs.length > MAX_MESSAGE_SIZE) throw IOException("message RTMP trop gros : ${cs.length}")
        val payload = cs.payload ?: ByteArray(cs.length).also {
            cs.payload = it
            cs.read = 0
        }
        val n = minOf(inChunkSize, cs.length - cs.read)
        limiter.acquire(n)
        input.readFully(payload, cs.read, n)
        cs.read += n
        if (cs.read >= cs.length) {
            cs.payload = null
            handleMessage(cs.typeId, cs.timestamp, payload)
        }

        if (counter.count - lastAckAt >= ackWindow) {
            lastAckAt = counter.count
            sendMessage(2, TYPE_ACK, 0, u32(counter.count))
        }
    }

    private fun handleMessage(typeId: Int, timestamp: Long, payload: ByteArray) {
        when (typeId) {
            TYPE_SET_CHUNK_SIZE -> if (payload.size >= 4) {
                val size = readU32(payload, 0).toInt() and 0x7FFFFFFF
                if (size < 1) throw IOException("taille de chunk invalide")
                inChunkSize = size
            }
            TYPE_WINDOW_ACK_SIZE -> if (payload.size >= 4) {
                val window = readU32(payload, 0)
                if (window > 0) ackWindow = window
            }
            TYPE_AUDIO -> if (publishing && payload.isNotEmpty()) listener.onAudio(timestamp, payload)
            TYPE_VIDEO -> if (publishing && payload.isNotEmpty()) listener.onVideo(timestamp, payload)
            TYPE_DATA_AMF0 -> handleData(payload, 0)
            TYPE_DATA_AMF3 -> handleData(payload, 1)
            TYPE_COMMAND_AMF0 -> handleCommand(payload, 0)
            TYPE_COMMAND_AMF3 -> handleCommand(payload, 1)
            else -> {} // abort, ack, user control, peer bandwidth, aggregate: nothing to do
        }
    }

    private fun handleData(payload: ByteArray, offset: Int) {
        try {
            val reader = Amf0.Reader(payload, offset)
            while (reader.hasMore()) {
                val value = reader.read()
                if (value is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    listener.onMetadata(value as Map<String, Any?>)
                    return
                }
            }
        } catch (e: IOException) {
            logger.log("RTMP $remote : métadonnées illisibles (${e.message})")
        }
    }

    private fun handleCommand(payload: ByteArray, offset: Int) {
        val reader = Amf0.Reader(payload, offset)
        val name = reader.read() as? String ?: return
        val txId = (reader.read() as? Double) ?: 0.0
        val args = ArrayList<Any?>()
        while (reader.hasMore()) args.add(reader.read())

        when (name) {
            "connect" -> {
                app = ((args.getOrNull(0) as? Map<*, *>)?.get("app") as? String).orEmpty()
                sendMessage(2, TYPE_WINDOW_ACK_SIZE, 0, u32(DEFAULT_WINDOW))
                sendMessage(2, TYPE_SET_PEER_BANDWIDTH, 0, u32(DEFAULT_WINDOW) + byteArrayOf(2))
                sendMessage(2, TYPE_SET_CHUNK_SIZE, 0, u32(OUT_CHUNK_SIZE.toLong()))
                outChunkSize = OUT_CHUNK_SIZE
                sendCommand(
                    0,
                    Amf0.Writer().string("_result").number(txId)
                        .obj(mapOf("fmsVer" to "FMS/3,0,1,123", "capabilities" to 31))
                        .obj(
                            mapOf(
                                "level" to "status",
                                "code" to "NetConnection.Connect.Success",
                                "description" to "Connection succeeded.",
                                "objectEncoding" to 0,
                            )
                        )
                )
            }
            "createStream" ->
                sendCommand(0, Amf0.Writer().string("_result").number(txId).nullValue().number(1.0))
            "publish" -> {
                val streamKey = (args.getOrNull(1) as? String).orEmpty()
                sendMessage(2, TYPE_USER_CONTROL, 0, byteArrayOf(0, 0) + u32(1))
                sendCommand(
                    1,
                    Amf0.Writer().string("onStatus").number(0.0).nullValue()
                        .obj(
                            mapOf(
                                "level" to "status",
                                "code" to "NetStream.Publish.Start",
                                "description" to "Publishing.",
                            )
                        )
                )
                if (!publishing) {
                    publishing = true
                    listener.onPublishStart(remote, app, streamKey)
                }
            }
            "play" -> throw EndOfStream("lecture RTMP non supportée (publish uniquement)")
            "FCUnpublish", "deleteStream", "closeStream" -> throw EndOfStream("fin de stream demandée par la caméra")
            else -> if (txId != 0.0) {
                // releaseStream, FCPublish, ...: clients only need an answer, not a meaningful one
                sendCommand(0, Amf0.Writer().string("_result").number(txId).nullValue())
            }
        }
    }

    private fun sendCommand(streamId: Int, amf: Amf0.Writer) =
        sendMessage(3, TYPE_COMMAND_AMF0, streamId, amf.toByteArray())

    private fun sendMessage(csid: Int, typeId: Int, streamId: Int, payload: ByteArray) {
        val header = ByteArray(12)
        header[0] = csid.toByte()
        header[4] = (payload.size shr 16).toByte()
        header[5] = (payload.size shr 8).toByte()
        header[6] = payload.size.toByte()
        header[7] = typeId.toByte()
        header[8] = streamId.toByte() // little endian
        header[9] = (streamId shr 8).toByte()
        header[10] = (streamId shr 16).toByte()
        header[11] = (streamId shr 24).toByte()
        output.write(header)
        var pos = 0
        while (pos < payload.size) {
            if (pos > 0) output.write(0xC0 or csid)
            val n = minOf(outChunkSize, payload.size - pos)
            output.write(payload, pos, n)
            pos += n
        }
        output.flush()
    }

    private fun readU24(): Int =
        (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()

    private companion object {
        const val HANDSHAKE_SIZE = 1536
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_MESSAGE_SIZE = 16 * 1024 * 1024
        const val DEFAULT_WINDOW = 2_500_000L
        const val OUT_CHUNK_SIZE = 4096

        const val TYPE_SET_CHUNK_SIZE = 1
        const val TYPE_ACK = 3
        const val TYPE_USER_CONTROL = 4
        const val TYPE_WINDOW_ACK_SIZE = 5
        const val TYPE_SET_PEER_BANDWIDTH = 6
        const val TYPE_AUDIO = 8
        const val TYPE_VIDEO = 9
        const val TYPE_DATA_AMF3 = 15
        const val TYPE_COMMAND_AMF3 = 17
        const val TYPE_DATA_AMF0 = 18
        const val TYPE_COMMAND_AMF0 = 20

        fun u32(v: Long): ByteArray =
            byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

        fun readU32(b: ByteArray, off: Int): Long =
            ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
                ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)
    }
}

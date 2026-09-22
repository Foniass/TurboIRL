package fr.turboirl.app.transcode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import fr.turboirl.core.EncodedVideoSink
import fr.turboirl.core.VideoProcessor
import fr.turboirl.core.rtmp.Logger
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * Hardware H.264 → H.264 transcoder. The camera's frames are decoded onto a [GlScaler], which
 * draws them at the output resolution into the encoder's input surface. Bitrate changes at any
 * time; the output resolution can change too (the encoder alone is recreated). Everything
 * codec-related runs on one handler thread.
 */
class VideoTranscoder(
    private val sink: EncodedVideoSink,
    private val logger: Logger,
    initialKbps: Int,
    initialHeight: Int,
) : VideoProcessor {

    class Stats {
        @Volatile var bitrateKbps = 0
        @Volatile var halfRate = false
        @Volatile var width = 0
        @Volatile var height = 0
        @Volatile var inputWidth = 0
        @Volatile var inputHeight = 0
        @Volatile var framesIn = 0L
        @Volatile var framesOut = 0L
        @Volatile var framesDropped = 0L
        @Volatile var bytesOut = 0L
        @Volatile var restarts = 0
        @Volatile var resolutionChanges = 0
    }

    val stats = Stats()

    @Volatile override var active = true
        private set

    private val thread = HandlerThread("transcoder").apply { start() }
    private val handler = Handler(thread.looper)

    private var scaler: GlScaler? = null
    private var decoder: MediaCodec? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var inWidth = 0
    private var inHeight = 0
    private var outHeight = initialHeight
    private var outWidth = 0
    @Volatile private var wantedHeight = initialHeight

    private class Frame(val data: ByteArray, val ptsUs: Long, val keyframe: Boolean)

    private val pending = ArrayDeque<Frame>()
    private val freeInputs = ArrayDeque<Int>()
    private var awaitingKeyframe = true
    private var failures = 0
    private var encoderSps: ByteArray? = null
    private var encoderPps: ByteArray? = null

    @Volatile private var targetKbps = initialKbps

    init {
        stats.bitrateKbps = initialKbps
    }

    // ---------------------------------------------------------------- VideoProcessor (RTMP thread)

    override fun configure(sps: ByteArray, pps: ByteArray, width: Int, height: Int) {
        handler.post {
            val same = this.sps?.contentEquals(sps) == true && this.pps?.contentEquals(pps) == true
            this.sps = sps
            this.pps = pps
            inWidth = width
            inHeight = height
            if (!same || decoder == null) restartCodecs("caméra en ${width}x$height")
        }
    }

    override fun frame(annexB: ByteArray, ptsMs: Long, dtsMs: Long, keyframe: Boolean) {
        stats.framesIn++
        handler.post {
            if (pending.size >= MAX_PENDING) {
                // Decoder can't keep up: drop up to the next keyframe rather than pile up latency
                stats.framesDropped += pending.size
                pending.clear()
                awaitingKeyframe = true
            }
            pending.addLast(Frame(annexB, ptsMs * 1000, keyframe))
            feedDecoder()
        }
    }

    override fun requestKeyframe() {
        handler.post {
            awaitingKeyframe = true
            pending.clear()
            syncFrame()
        }
    }

    override fun release() {
        handler.post {
            releaseCodecs()
            scaler?.release()
            scaler = null
        }
        thread.quitSafely()
    }

    // ---------------------------------------------------------------- control (any thread)

    fun setBitrate(kbps: Int) {
        if (kbps == targetKbps) return
        targetKbps = kbps
        stats.bitrateKbps = kbps
        handler.post {
            try {
                encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000) })
            } catch (e: Exception) {
                logger.log("Encodeur : changement de débit refusé (${e.message})")
            }
        }
    }

    fun setHalfRate(half: Boolean) {
        stats.halfRate = half
        scaler?.halfRate = half
    }

    /** Output height (480 / 720 / 1080); the encoder is recreated when it changes. */
    fun setOutputHeight(height: Int) {
        if (height == wantedHeight) return
        wantedHeight = height
        handler.post {
            if (encoder != null && height != outHeight) {
                outHeight = height
                stats.resolutionChanges++
                recreateEncoder("changement de résolution")
            } else {
                outHeight = height
            }
        }
    }

    // ---------------------------------------------------------------- codecs (handler thread)

    private fun outputSize(): Pair<Int, Int> {
        val h = if (inHeight > 0) minOf(outHeight, inHeight) else outHeight
        val aspect = if (inWidth > 0 && inHeight > 0) inWidth.toDouble() / inHeight else 16.0 / 9
        val w = (Math.round(h * aspect / 16.0) * 16).toInt()
        return w to h
    }

    private fun restartCodecs(reason: String) {
        releaseCodecs()
        val s = sps ?: return
        val p = pps ?: return
        try {
            val sc = scaler ?: GlScaler(logger).also { scaler = it }
            sc.halfRate = stats.halfRate
            createEncoder()

            val dec = MediaCodec.createDecoderByType(MIME)
            val decFormat = MediaFormat.createVideoFormat(MIME, inWidth, inHeight).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + s))
                setByteBuffer("csd-1", ByteBuffer.wrap(START_CODE + p))
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            dec.setCallback(decoderCallback, handler)
            dec.configure(decFormat, sc.inputSurface, null, 0)
            dec.start()
            decoder = dec
            awaitingKeyframe = true
            failures = 0
            logger.log("Réencodage démarré : $reason → ${outWidth}x${stats.height} à ${targetKbps} kb/s (${encoder?.name})")
        } catch (e: Exception) {
            failures++
            stats.restarts++
            logger.log("Réencodage impossible (${e.message}), essai $failures/$MAX_FAILURES")
            releaseCodecs()
            if (failures >= MAX_FAILURES) {
                active = false
                logger.log("Réencodage abandonné : la vidéo de la caméra passe telle quelle")
            } else {
                handler.postDelayed({ restartCodecs("nouvel essai") }, 2000)
            }
        }
    }

    private fun createEncoder() {
        val (w, h) = outputSize()
        val enc = MediaCodec.createEncoderByType(MIME)
        val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, targetKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, GOP_SECONDS)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            val caps = enc.codecInfo.getCapabilitiesForType(MIME).encoderCapabilities
            if (caps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
        }
        enc.setCallback(encoderCallback, handler)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()
        encoder = enc
        encoderSurface = surface
        encoderSps = null
        encoderPps = null
        outWidth = w
        stats.width = w
        stats.height = h
        scaler?.setOutput(surface, w, h)
    }

    private fun recreateEncoder(reason: String) {
        scaler?.setOutput(null, 0, 0)
        releaseEncoder()
        try {
            createEncoder()
            logger.log("Encodeur recréé ($reason) → ${outWidth}x${stats.height}")
        } catch (e: Exception) {
            logger.log("Encodeur : recréation impossible (${e.message})")
            onCodecError("encodeur", e)
        }
    }

    private fun releaseEncoder() {
        encoder?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        encoder = null
        encoderSurface?.release()
        encoderSurface = null
    }

    private fun releaseCodecs() {
        scaler?.setOutput(null, 0, 0)
        decoder?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        decoder = null
        releaseEncoder()
        pending.clear()
        freeInputs.clear()
    }

    private fun feedDecoder() {
        val dec = decoder ?: return
        while (pending.isNotEmpty() && freeInputs.isNotEmpty()) {
            val f = pending.removeFirst()
            if (awaitingKeyframe) {
                if (!f.keyframe) {
                    stats.framesDropped++
                    continue
                }
                awaitingKeyframe = false
            }
            val index = freeInputs.removeFirst()
            try {
                val buf = dec.getInputBuffer(index) ?: continue
                if (buf.capacity() < f.data.size) {
                    logger.log("Décodeur : image trop grande (${f.data.size} o)")
                    stats.framesDropped++
                    freeInputs.addFirst(index)
                    continue
                }
                buf.clear()
                buf.put(f.data)
                dec.queueInputBuffer(index, 0, f.data.size, f.ptsUs, 0)
            } catch (e: Exception) {
                onCodecError("décodeur", e)
                return
            }
        }
    }

    private fun syncFrame() {
        try {
            encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {
        }
    }

    private fun onCodecError(which: String, e: Exception) {
        logger.log("Erreur $which : ${e.message}")
        stats.restarts++
        releaseCodecs()
        handler.postDelayed({ restartCodecs("après erreur") }, 1000)
    }

    private val decoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (codec !== decoder) return
            freeInputs.addLast(index)
            feedDecoder()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (codec !== decoder) return
            try {
                codec.releaseOutputBuffer(index, info.size > 0)
            } catch (e: Exception) {
                onCodecError("décodeur", e)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (codec === decoder) onCodecError("décodeur", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (codec !== decoder) return
            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)
            stats.inputWidth = w
            stats.inputHeight = h
            if (w != inWidth || h != inHeight) {
                logger.log("Décodeur : la caméra envoie du ${w}x$h")
                inWidth = w
                inHeight = h
                if (outputSize().first != outWidth) recreateEncoder("format d'entrée")
            }
        }
    }

    private val encoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (codec !== encoder) return
            try {
                val buf = codec.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    val data = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(data)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        parseCodecConfig(data)
                    } else {
                        emit(data, info.presentationTimeUs / 1000, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                    }
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: Exception) {
                onCodecError("encodeur", e)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (codec === encoder) onCodecError("encodeur", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
    }

    /** Splits the encoder's SPS/PPS blob (Annex B) into the two NAL units. */
    private fun parseCodecConfig(data: ByteArray) {
        for (nal in splitNals(data)) {
            when (nal[0].toInt() and 0x1F) {
                7 -> encoderSps = nal
                8 -> encoderPps = nal
            }
        }
    }

    private fun emit(data: ByteArray, ptsMs: Long, keyframe: Boolean) {
        val s = encoderSps
        val p = encoderPps
        // AUD, then on keyframes our own SPS/PPS, then the slices (minus any AUD/SPS/PPS the encoder added)
        val nals = splitNals(data).filter { (it[0].toInt() and 0x1F) !in setOf(7, 8, 9) }
        var size = 4 + AUD.size
        if (keyframe && s != null && p != null) size += 8 + s.size + p.size
        for (n in nals) size += 4 + n.size
        val out = ByteArray(size)
        var o = putNal(out, 0, AUD)
        if (keyframe && s != null && p != null) {
            o = putNal(out, o, s)
            o = putNal(out, o, p)
        }
        for (n in nals) o = putNal(out, o, n)
        stats.framesOut++
        stats.bytesOut += o
        sink.encoded(out, o, ptsMs, keyframe)
    }

    private fun putNal(out: ByteArray, off: Int, nal: ByteArray): Int {
        out[off] = 0
        out[off + 1] = 0
        out[off + 2] = 0
        out[off + 3] = 1
        System.arraycopy(nal, 0, out, off + 4, nal.size)
        return off + 4 + nal.size
    }

    companion object {
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val MAX_PENDING = 60
        private const val MAX_FAILURES = 3
        private const val GOP_SECONDS = 2
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
        private val AUD = byteArrayOf(0x09, 0xF0.toByte())

        /** Annex B → list of NAL units (3- or 4-byte start codes). */
        fun splitNals(data: ByteArray): List<ByteArray> {
            val starts = ArrayList<Int>()
            var i = 0
            while (i + 2 < data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                    starts.add(i + 3)
                    i += 3
                } else {
                    i++
                }
            }
            val nals = ArrayList<ByteArray>()
            for ((k, start) in starts.withIndex()) {
                var end = if (k + 1 < starts.size) starts[k + 1] - 3 else data.size
                while (end > start && data[end - 1].toInt() == 0) end-- // trailing zero of a 4-byte start code
                if (end > start) nals.add(data.copyOfRange(start, end))
            }
            return nals
        }
    }
}

package fr.turboirl.app.transcode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import fr.turboirl.core.AudioProcessor
import fr.turboirl.core.EncodedAudioSink
import fr.turboirl.core.rtmp.Logger
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * AAC → PCM → AAC-LC at a lower bitrate, so that the sound survives links where the camera's
 * 128 kb/s would not. Frames stay 1024 samples, timestamps are carried through. Everything runs
 * on one handler thread; after repeated failures the camera's audio goes through untouched.
 */
class AudioTranscoder(
    private val sink: EncodedAudioSink,
    private val logger: Logger,
    private val kbps: Int,
) : AudioProcessor {

    class Stats {
        @Volatile var framesIn = 0L
        @Volatile var framesOut = 0L
        @Volatile var bytesOut = 0L
        @Volatile var dropped = 0L
        @Volatile var sampleRate = 0
        @Volatile var channels = 0
    }

    val stats = Stats()

    @Volatile override var active = true
        private set

    private val thread = HandlerThread("audio-transcoder").apply { start() }
    private val handler = Handler(thread.looper)

    private var decoder: MediaCodec? = null
    private var encoder: MediaCodec? = null
    private var asc: ByteArray? = null
    private var sampleRate = 48000
    private var channels = 2
    private var failures = 0

    private class Chunk(val data: ByteArray, val ptsUs: Long)

    private val pendingIn = ArrayDeque<Chunk>()    // raw AAC waiting for a decoder input buffer
    private val freeDecInputs = ArrayDeque<Int>()
    private val pendingPcm = ArrayDeque<Chunk>()   // PCM waiting for an encoder input buffer
    private val freeEncInputs = ArrayDeque<Int>()
    private var freqIndex = 3
    private var outChannels = 2

    // ---------------------------------------------------------------- AudioProcessor (RTMP thread)

    override fun configure(asc: ByteArray, sampleRate: Int, channels: Int) {
        handler.post {
            val same = this.asc?.contentEquals(asc) == true && decoder != null
            this.asc = asc
            this.sampleRate = sampleRate
            this.channels = channels
            if (!same) restart("config ${sampleRate} Hz, $channels canaux")
        }
    }

    override fun frame(rawAac: ByteArray, ptsMs: Long) {
        stats.framesIn++
        handler.post {
            if (pendingIn.size > MAX_PENDING) {
                stats.dropped += pendingIn.size
                pendingIn.clear()
            }
            pendingIn.addLast(Chunk(rawAac, ptsMs * 1000))
            feedDecoder()
        }
    }

    override fun release() {
        handler.post { releaseCodecs() }
        thread.quitSafely()
    }

    // ---------------------------------------------------------------- codecs (handler thread)

    private fun restart(reason: String) {
        releaseCodecs()
        val config = asc ?: return
        try {
            val dec = MediaCodec.createDecoderByType(MIME)
            val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(config))
            }
            dec.setCallback(decoderCallback, handler)
            dec.configure(format, null, null, 0)
            dec.start()
            decoder = dec
            // The encoder is created once the decoder tells us the real PCM format
            logger.log("Réencodage audio : décodeur prêt ($reason), cible $kbps kb/s")
        } catch (e: Exception) {
            fail("démarrage", e)
        }
    }

    private fun createEncoder(rate: Int, ch: Int) {
        encoder?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {
            }
        }
        encoder = null
        freeEncInputs.clear()
        val enc = MediaCodec.createEncoderByType(MIME)
        val format = MediaFormat.createAudioFormat(MIME, rate, ch).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024)
        }
        enc.setCallback(encoderCallback, handler)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        encoder = enc
        freqIndex = AAC_RATES.indexOf(rate).let { if (it < 0) 3 else it }
        outChannels = ch
        stats.sampleRate = rate
        stats.channels = ch
        logger.log("Réencodage audio démarré : $rate Hz, $ch canaux → $kbps kb/s (${enc.name})")
    }

    private fun releaseCodecs() {
        for (c in listOfNotNull(decoder, encoder)) {
            try {
                c.stop()
            } catch (_: Exception) {
            }
            try {
                c.release()
            } catch (_: Exception) {
            }
        }
        decoder = null
        encoder = null
        pendingIn.clear()
        pendingPcm.clear()
        freeDecInputs.clear()
        freeEncInputs.clear()
    }

    private fun fail(what: String, e: Exception) {
        failures++
        logger.log("Réencodage audio : erreur $what (${e.message}), essai $failures/$MAX_FAILURES")
        releaseCodecs()
        if (failures >= MAX_FAILURES) {
            active = false
            logger.log("Réencodage audio abandonné : le son de la caméra passe tel quel")
        } else {
            handler.postDelayed({ restart("nouvel essai") }, 1000)
        }
    }

    private fun feedDecoder() {
        val dec = decoder ?: return
        while (pendingIn.isNotEmpty() && freeDecInputs.isNotEmpty()) {
            val c = pendingIn.removeFirst()
            val index = freeDecInputs.removeFirst()
            try {
                val buf = dec.getInputBuffer(index) ?: continue
                buf.clear()
                buf.put(c.data)
                dec.queueInputBuffer(index, 0, c.data.size, c.ptsUs, 0)
            } catch (e: Exception) {
                fail("décodeur", e)
                return
            }
        }
    }

    private fun feedEncoder() {
        val enc = encoder ?: return
        while (pendingPcm.isNotEmpty() && freeEncInputs.isNotEmpty()) {
            val c = pendingPcm.removeFirst()
            val index = freeEncInputs.removeFirst()
            try {
                val buf = enc.getInputBuffer(index) ?: continue
                val n = minOf(c.data.size, buf.capacity())
                buf.clear()
                buf.put(c.data, 0, n)
                enc.queueInputBuffer(index, 0, n, c.ptsUs, 0)
                if (n < c.data.size) {
                    // Rare: split, the remainder keeps a timestamp shifted by the samples consumed
                    val rest = c.data.copyOfRange(n, c.data.size)
                    val shiftUs = n.toLong() * 1_000_000 / (outChannels * 2 * stats.sampleRate.coerceAtLeast(1))
                    pendingPcm.addFirst(Chunk(rest, c.ptsUs + shiftUs))
                }
            } catch (e: Exception) {
                fail("encodeur", e)
                return
            }
        }
    }

    private val decoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (codec !== decoder) return
            freeDecInputs.addLast(index)
            feedDecoder()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (codec !== decoder) return
            try {
                val buf = codec.getOutputBuffer(index)
                if (buf != null && info.size > 0 && encoder != null) {
                    val pcm = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(pcm)
                    if (pendingPcm.size > MAX_PENDING) {
                        stats.dropped += pendingPcm.size
                        pendingPcm.clear()
                    }
                    pendingPcm.addLast(Chunk(pcm, info.presentationTimeUs))
                    feedEncoder()
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: Exception) {
                fail("décodeur", e)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (codec === decoder) fail("décodeur", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (codec !== decoder) return
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            try {
                createEncoder(rate, ch)
            } catch (e: Exception) {
                fail("encodeur", e)
            }
        }
    }

    private val encoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (codec !== encoder) return
            freeEncInputs.addLast(index)
            feedEncoder()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (codec !== encoder) return
            try {
                val buf = codec.getOutputBuffer(index)
                if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val frameLen = info.size + 7
                    val out = ByteArray(frameLen)
                    out[0] = 0xFF.toByte()
                    out[1] = 0xF1.toByte() // MPEG-4, no CRC
                    out[2] = ((1 shl 6) or (freqIndex shl 2) or (outChannels shr 2)).toByte() // profile LC
                    out[3] = (((outChannels and 3) shl 6) or (frameLen shr 11)).toByte()
                    out[4] = (frameLen shr 3).toByte()
                    out[5] = (((frameLen and 7) shl 5) or 0x1F).toByte()
                    out[6] = 0xFC.toByte()
                    buf.position(info.offset)
                    buf.get(out, 7, info.size)
                    stats.framesOut++
                    stats.bytesOut += frameLen
                    sink.encoded(out, frameLen, info.presentationTimeUs / 1000)
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: Exception) {
                fail("encodeur", e)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (codec === encoder) fail("encodeur", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
    }

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        const val MAX_PENDING = 200
        const val MAX_FAILURES = 3
        val AAC_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    }
}

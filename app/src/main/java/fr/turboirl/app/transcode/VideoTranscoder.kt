package fr.turboirl.app.transcode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
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
 * Hardware H.264 → H.265 transcoder (H.264 output if the phone has no hardware HEVC encoder).
 * The camera's frames are decoded onto a [GlScaler], which draws them at the output resolution
 * into the encoder's input surface. Bitrate changes at any time; the output resolution can
 * change too (the encoder alone is recreated). Everything codec-related runs on one handler
 * thread.
 */
class VideoTranscoder(
    private val sink: EncodedVideoSink,
    private val logger: Logger,
    initialKbps: Int,
    initialHeight: Int,
) : VideoProcessor {

    /** Decoder-side view for the journal: what libmedia actually did with our frames. */
    @Volatile var debugInfo = ""

    fun debug(): String = try {
        "décodeur ${decoder?.name ?: "absent"} : entrées libres ${freeInputs.size}, en attente ${pending.size}, sorties en file ${decodedOut.size}, " +
            "consommées $queued, callbacks sortie $outputCallbacks (vides $emptyOutputs), attente image clé $awaitingKeyframe"
    } catch (_: Exception) { "?" }

    private var queued = 0L
    private var outputCallbacks = 0L
    private var emptyOutputs = 0L
    // Silent-decoder watchdog: a decoder that eats frames without ever producing one is retried differently
    // (no low-latency flag, then the software decoder). Seen 25/09 on a Nothing A015 with the bench video.
    private var decoderStrategy = 0
    private var decoderSinceNs = 0L
    private var queuedAtStart = 0L
    private var outputsAtStart = 0L

    class Stats {
        /** 1 = every frame, 2 = 15 i/s, 6 = 5 i/s, 30 = 1 i/s. */
        @Volatile var frameDivider = 1
        @Volatile var width = 0
        @Volatile var height = 0
        @Volatile var framesIn = 0L
        @Volatile var framesOut = 0L
        @Volatile var framesDecoded = 0L
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
    @Volatile private var blur = false

    /** Blur on/off for the outgoing video (applies at once, and to any scaler created later). */
    fun setBlur(on: Boolean) {
        blur = on
        handler.post { scaler?.blur = on }
    }
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
    // Decoded pictures waiting to be handed to the scaler, one at a time: the SurfaceTexture keeps only the
    // newest queued buffer, so releasing two before the GL thread took the first silently lost the older one
    // (7-13 % of the frames on the Redmi, whose decoder outputs in bursts).
    private val decodedOut = ArrayDeque<Int>()
    private var inFlightSinceNs = 0L
    private var awaitingKeyframe = true
    private var failures = 0
    private var encoderVps: ByteArray? = null
    private var encoderSps: ByteArray? = null
    private var encoderPps: ByteArray? = null
    private var outputHevc = false
    private var hevcLogged = false

    @Volatile private var targetKbps = initialKbps
    private var cbrLogged = false

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
        handler.post {
            try {
                encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000) })
            } catch (e: Exception) {
                logger.log("Encodeur : changement de débit refusé (${e.message})")
            }
        }
    }

    fun scalerFramesDrawn(): Long = scaler?.framesDrawn ?: 0
    fun scalerFramesReceived(): Long = scaler?.framesReceived ?: 0
    fun scalerSwapWaitMs(): Long = scaler?.swapWaitMs ?: 0

    /** 1 = every frame, 2 = 15 i/s, 6 = 5 i/s, 30 = 1 i/s. */
    fun setFrameDivider(divider: Int) {
        stats.frameDivider = divider
        scaler?.frameDivider = divider
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

    private val decoderWatchdog = object : Runnable {
        override fun run() {
            val dec = decoder ?: return
            val consumed = queued - queuedAtStart
            val produced = outputCallbacks - outputsAtStart
            if (System.nanoTime() - decoderSinceNs > 5_000_000_000L && consumed >= 45 && produced == 0L) {
                if (decoderStrategy < 2) {
                    decoderStrategy++
                    logger.log("Décodeur ${dec.name} muet ($consumed images consommées, aucune sortie) : nouvel essai " +
                        if (decoderStrategy == 1) "sans mode basse latence" else "avec le décodeur logiciel")
                    releaseCodecs()
                    restartCodecs("décodeur muet")
                    return
                }
                logger.log("Décodeur ${dec.name} muet malgré les essais ($consumed images consommées)")
                return
            }
            handler.postDelayed(this, 3000)
        }
    }

    private fun softwareDecoderName(): String? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) } &&
                (info.name.contains("android", ignoreCase = true) || info.name.contains("google", ignoreCase = true) || info.name.startsWith("OMX.google"))
        }?.name

    private fun restartCodecs(reason: String) {
        releaseCodecs()
        val s = sps ?: return
        val p = pps ?: return
        try {
            val sc = scaler ?: GlScaler(logger).also { scaler = it; it.blur = blur }
            sc.frameDivider = stats.frameDivider
            sc.onFrameConsumed = { handler.post { inFlightSinceNs = 0L; pumpDecoded() } }
            createEncoder()

            val software = if (decoderStrategy >= 2) softwareDecoderName() else null
            val dec = if (software != null) MediaCodec.createByCodecName(software) else MediaCodec.createDecoderByType(MIME)
            val decFormat = MediaFormat.createVideoFormat(MIME, inWidth, inHeight).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + s))
                setByteBuffer("csd-1", ByteBuffer.wrap(START_CODE + p))
                if (Build.VERSION.SDK_INT >= 30 && decoderStrategy == 0) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            dec.setCallback(decoderCallback, handler)
            dec.configure(decFormat, sc.inputSurface, null, 0)
            dec.start()
            decoder = dec
            decoderSinceNs = System.nanoTime()
            queuedAtStart = queued
            outputsAtStart = outputCallbacks
            handler.removeCallbacks(decoderWatchdog)
            handler.postDelayed(decoderWatchdog, 3000)
            logger.log("Décodeur ${dec.name} configuré (${inWidth}x$inHeight, SPS ${s.size} o, PPS ${p.size} o" +
                (if (decoderStrategy == 1) ", sans mode basse latence" else if (decoderStrategy >= 2) ", logiciel" else "") + ")")
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

    /** H.265 when a hardware encoder exists for it, else H.264. */
    private fun pickOutputMime(): String {
        val hasHevc = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.isHardwareAccelerated && info.supportedTypes.any { it.equals(MIME_HEVC, ignoreCase = true) }
        }
        if (!hasHevc && !hevcLogged) {
            hevcLogged = true
            logger.log("Pas d'encodeur H.265 matériel : sortie en H.264")
        }
        return if (hasHevc) MIME_HEVC else MIME
    }

    private fun createEncoder() {
        val (w, h) = outputSize()
        val mime = pickOutputMime()
        val enc = MediaCodec.createEncoderByType(mime)
        val format = MediaFormat.createVideoFormat(mime, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, targetKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, GOP_SECONDS)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            val caps = enc.codecInfo.getCapabilitiesForType(mime).encoderCapabilities
            val cbr = caps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            if (cbr) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            if (!cbrLogged) {
                cbrLogged = true
                logger.log("Encodeur ${enc.name} (${if (mime == MIME_HEVC) "H.265" else "H.264"}) : mode CBR ${if (cbr) "supporté" else "NON supporté (débit variable)"}")
            }
        }
        outputHevc = mime == MIME_HEVC
        encoderVps = null
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
        decodedOut.clear() // indices of the codec just released
        inFlightSinceNs = 0L
    }

    /** Hands the next decoded picture to the scaler once the previous one has been taken (handler thread). */
    private fun pumpDecoded() {
        val dec = decoder ?: return
        val now = System.nanoTime()
        if (inFlightSinceNs != 0L) {
            if (now - inFlightSinceNs < IN_FLIGHT_TIMEOUT_NS) return
            inFlightSinceNs = 0L // the GL thread never told us: don't stall forever
        }
        if (decodedOut.isEmpty()) return
        try {
            // GL thread too slow: keep the picture fresh rather than pile up latency
            while (decodedOut.size > MAX_DECODED_QUEUE) {
                dec.releaseOutputBuffer(decodedOut.removeFirst(), false)
                stats.framesDropped++
            }
            inFlightSinceNs = now
            dec.releaseOutputBuffer(decodedOut.removeFirst(), true)
        } catch (e: Exception) {
            onCodecError("décodeur", e)
        }
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
                queued++
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
            outputCallbacks++
            if (info.size <= 0) {
                emptyOutputs++
                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (e: Exception) {
                    onCodecError("décodeur", e)
                }
                return
            }
            stats.framesDecoded++
            decodedOut.addLast(index)
            pumpDecoded()
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (codec === decoder) onCodecError("décodeur", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (codec !== decoder) return
            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)
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

    private fun nalType(nal: ByteArray): Int =
        if (outputHevc) (nal[0].toInt() shr 1) and 0x3F else nal[0].toInt() and 0x1F

    /** Splits the encoder's parameter-set blob (Annex B): SPS/PPS for H.264, VPS/SPS/PPS for H.265. */
    private fun parseCodecConfig(data: ByteArray) {
        for (nal in splitNals(data)) {
            if (outputHevc) {
                when (nalType(nal)) {
                    32 -> encoderVps = nal
                    33 -> encoderSps = nal
                    34 -> encoderPps = nal
                }
            } else {
                when (nalType(nal)) {
                    7 -> encoderSps = nal
                    8 -> encoderPps = nal
                }
            }
        }
    }

    private fun emit(data: ByteArray, ptsMs: Long, keyframe: Boolean) {
        val params = listOfNotNull(if (outputHevc) encoderVps else null, encoderSps, encoderPps)
        val complete = params.size == (if (outputHevc) 3 else 2)
        // AUD, then on keyframes our own parameter sets, then the slices (minus any AUD/parameter sets the encoder added)
        val skip = if (outputHevc) HEVC_NON_SLICE else H264_NON_SLICE
        val aud = if (outputHevc) AUD_HEVC else AUD
        val nals = splitNals(data).filter { nalType(it) !in skip }
        var size = 4 + aud.size
        if (keyframe && complete) for (p in params) size += 4 + p.size
        for (n in nals) size += 4 + n.size
        val out = ByteArray(size)
        var o = putNal(out, 0, aud)
        if (keyframe && complete) for (p in params) o = putNal(out, o, p)
        for (n in nals) o = putNal(out, o, n)
        stats.framesOut++
        stats.bytesOut += o
        sink.encoded(out, o, ptsMs, keyframe, outputHevc)
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
        private const val MIME_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
        private const val MAX_PENDING = 60
        private const val MAX_DECODED_QUEUE = 3
        private const val IN_FLIGHT_TIMEOUT_NS = 200_000_000L
        private const val MAX_FAILURES = 3
        private const val GOP_SECONDS = 2
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
        private val AUD = byteArrayOf(0x09, 0xF0.toByte())
        private val AUD_HEVC = byteArrayOf(0x46, 0x01, 0x50)
        private val H264_NON_SLICE = setOf(7, 8, 9)
        private val HEVC_NON_SLICE = setOf(32, 33, 34, 35)

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

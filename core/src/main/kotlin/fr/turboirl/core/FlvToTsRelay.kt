package fr.turboirl.core

import fr.turboirl.core.rtmp.Logger
import fr.turboirl.core.rtmp.RtmpListener
import fr.turboirl.core.ts.TsMuxer
import fr.turboirl.core.ts.TsSink

/**
 * Repackages the FLV tags of an RTMP publisher (H.264 + AAC) into MPEG-TS, without touching
 * the encoded data. Survives publisher reconnections: output timestamps follow the wall clock
 * so they keep increasing across sessions.
 *
 * [congested] is polled on every frame; while it returns true, video is withheld (from the
 * next frame on) and only audio + clock go out, so that a saturated uplink never silences the
 * stream. Video resumes on the first keyframe after the link has recovered.
 */
class FlvToTsRelay(
    sink: TsSink,
    private val logger: Logger,
    private val congested: () -> Boolean = { false },
) : RtmpListener, EncodedVideoSink {

    /** Set before the camera connects; null = the camera's video goes through untouched. */
    @Volatile var processor: VideoProcessor? = null

    // Video input size from the stream metadata, for the processor
    private var width = 0
    private var height = 0


    class Stats {
        @Volatile var publishing = false
        @Volatile var publisher = ""
        @Volatile var videoInfo = ""
        @Volatile var videoBytes = 0L
        @Volatile var audioBytes = 0L
        @Volatile var videoFrames = 0L
        @Volatile var keyframes = 0L
        @Volatile var tsBytes = 0L
        @Volatile var sessions = 0
        @Volatile var videoSuspended = false
        @Volatile var videoSuspensions = 0
        @Volatile var videoSuspendedMs = 0L
    }

    val stats = Stats()

    private val muxer = TsMuxer(sink)
    private val startNs = System.nanoTime()

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var nalLengthSize = 4
    private var aacProfile = -1
    private var aacFreqIndex = 0
    private var aacChannels = 0

    private var started = false // true once a keyframe went out in this session
    private var videoSuspended = false
    private var suspendedAtNs = 0L
    private var resumedAtNs = 0L
    private var holdMs = MIN_HOLD_MS // minimum suspension, grows when the link keeps collapsing right after a resume
    private var lastPcrOnlyNs = 0L
    private var sessionFirstTs = -1L
    private var sessionOffsetMs = 0L
    private var lastOutMs = 0L
    private var warnedVideoCodec = false
    private var warnedAudioCodec = false

    private var scratch = ByteArray(256 * 1024)

    override fun onPublishStart(remote: String, app: String, streamKey: String) {
        sps = null
        pps = null
        aacProfile = -1
        started = false
        sessionFirstTs = -1
        videoSuspended = false
        stats.videoSuspended = false
        stats.publisher = remote
        stats.videoInfo = ""
        stats.publishing = true
        stats.sessions++
        logger.log("Caméra connectée ($remote) → $app/$streamKey")
    }

    override fun onMetadata(metadata: Map<String, Any?>) {
        val w = (metadata["width"] as? Double)?.toInt()
        val h = (metadata["height"] as? Double)?.toInt()
        if (w != null && h != null && w > 0 && h > 0) {
            width = w
            height = h
        }
        val fps = metadata["framerate"] as? Double
        val kbps = (metadata["videodatarate"] as? Double)?.toInt()
        stats.videoInfo = buildString {
            if (w != null && h != null) append("${w}x$h")
            if (fps != null && fps > 0) append(" @ ${"%.0f".format(fps)} i/s")
            if (kbps != null && kbps > 0) append(" ~$kbps kb/s")
        }.trim()
        logger.log("Métadonnées caméra : ${stats.videoInfo.ifEmpty { metadata.keys.joinToString() }}")
    }

    override fun onPublishEnd(reason: String) {
        synchronized(muxer) { muxer.flush() }
        stats.publishing = false
        logger.log("Caméra déconnectée : $reason")
    }

    override fun onVideo(timestampMs: Long, data: ByteArray) {
        stats.videoBytes += data.size
        val b0 = data[0].toInt() and 0xFF
        if (b0 and 0x80 != 0 || (b0 and 0x0F) != 7) {
            if (!warnedVideoCodec) {
                warnedVideoCodec = true
                logger.log("Codec vidéo non supporté (seul le H.264 l'est) : 0x${b0.toString(16)}")
            }
            return
        }
        if (data.size < 5) return
        val packetType = data[1].toInt()
        if (packetType == 0) {
            parseAvcConfig(data)
            return
        }
        if (packetType != 1) return
        val spsNal = sps ?: return
        val ppsNal = pps ?: return

        val keyframe = (b0 shr 4) == 1
        if (!started) {
            if (!keyframe) return
            started = true
        }
        val nowNs = System.nanoTime()
        if (videoSuspended) {
            val ms = (nowNs - suspendedAtNs) / 1_000_000
            if (congested() || !keyframe || ms < holdMs) return
            videoSuspended = false
            stats.videoSuspended = false
            stats.videoSuspendedMs += ms
            resumedAtNs = nowNs
            processor?.takeIf { it.active }?.requestKeyframe()
            logger.log("Vidéo reprise après ${"%.1f".format(ms / 1000.0)} s")
        } else if (congested()) {
            // Collapsing again right after a resume: the link cannot carry video, hold it off longer.
            val sinceResume = (nowNs - resumedAtNs) / 1_000_000
            holdMs = if (resumedAtNs != 0L && sinceResume < RELAPSE_WINDOW_MS) minOf(holdMs * 2, MAX_HOLD_MS) else MIN_HOLD_MS
            videoSuspended = true
            stats.videoSuspended = true
            stats.videoSuspensions++
            suspendedAtNs = nowNs
            logger.log("Liaison saturée : vidéo suspendue (${holdMs / 1000} s minimum), le son continue")
            return
        }
        // signed 24-bit composition time offset
        val cts = ((data[2].toInt() shl 24) or ((data[3].toInt() and 0xFF) shl 16) or ((data[4].toInt() and 0xFF) shl 8)) shr 8

        // Worst case growth: every NAL gains (4 - nalLengthSize) bytes.
        val needed = data.size * 2 + spsNal.size + ppsNal.size + 32
        if (scratch.size < needed) scratch = ByteArray(needed)
        val out = scratch
        var o = putNal(out, 0, AUD, AUD.size)

        var hasSps = false
        forEachNal(data) { off, _ -> if ((data[off].toInt() and 0x1F) == 7) hasSps = true }
        if (keyframe && !hasSps) {
            o = putNal(out, o, spsNal, spsNal.size)
            o = putNal(out, o, ppsNal, ppsNal.size)
        }
        forEachNal(data) { off, len ->
            if ((data[off].toInt() and 0x1F) != 9) { // we already wrote our own AUD
                o = putStartCode(out, o)
                System.arraycopy(data, off, out, o, len)
                o += len
            }
        }

        val outMs = outputTime(timestampMs)
        stats.videoFrames++
        if (keyframe) stats.keyframes++
        val p = processor
        if (p != null && p.active) {
            p.frame(out.copyOf(o), outMs + cts, outMs, keyframe)
            return
        }
        val dts = outMs * 90 + PTS_OFFSET
        synchronized(muxer) {
            muxer.writeVideo(out, o, dts + cts * 90L, dts, outMs * 90, keyframe)
            stats.tsBytes = muxer.bytesWritten
        }
    }

    override fun onAudio(timestampMs: Long, data: ByteArray) {
        stats.audioBytes += data.size
        val b0 = data[0].toInt() and 0xFF
        if ((b0 shr 4) != 10) {
            if (!warnedAudioCodec) {
                warnedAudioCodec = true
                logger.log("Codec audio non supporté (seul l'AAC l'est) : format ${b0 shr 4}")
            }
            return
        }
        if (data.size < 2) return
        if (data[1].toInt() == 0) {
            parseAacConfig(data)
            return
        }
        if (aacProfile < 0 || !started) return

        val rawLen = data.size - 2
        val frameLen = rawLen + 7
        if (scratch.size < frameLen) scratch = ByteArray(frameLen)
        val out = scratch
        out[0] = 0xFF.toByte()
        out[1] = 0xF1.toByte() // MPEG-4, no CRC
        out[2] = ((aacProfile shl 6) or (aacFreqIndex shl 2) or (aacChannels shr 2)).toByte()
        out[3] = (((aacChannels and 3) shl 6) or (frameLen shr 11)).toByte()
        out[4] = (frameLen shr 3).toByte()
        out[5] = (((frameLen and 7) shl 5) or 0x1F).toByte()
        out[6] = 0xFC.toByte()
        System.arraycopy(data, 2, out, 7, rawLen)

        val outMs = outputTime(timestampMs)
        synchronized(muxer) {
            if (videoSuspended) {
                val now = System.nanoTime()
                if (now - lastPcrOnlyNs > PCR_ONLY_INTERVAL_NS) {
                    lastPcrOnlyNs = now
                    muxer.writePcrOnly(outMs * 90)
                }
            }
            muxer.writeAudio(out, frameLen, outMs * 90 + PTS_OFFSET)
            stats.tsBytes = muxer.bytesWritten
        }
    }

    /** Re-encoded video coming back from the processor (encoder thread). */
    override fun encoded(annexB: ByteArray, len: Int, ptsMs: Long, keyframe: Boolean) {
        val pts = ptsMs * 90 + PTS_OFFSET
        synchronized(muxer) {
            muxer.writeVideo(annexB, len, pts, pts, ptsMs * 90, keyframe)
            stats.tsBytes = muxer.bytesWritten
        }
    }

    /** Maps a session-relative RTMP timestamp onto the relay's ever-increasing timeline. */
    private fun outputTime(timestampMs: Long): Long {
        if (sessionFirstTs < 0) {
            sessionFirstTs = timestampMs
            val wallMs = (System.nanoTime() - startNs) / 1_000_000
            sessionOffsetMs = maxOf(wallMs, lastOutMs + 100)
        }
        val out = maxOf(0, timestampMs - sessionFirstTs) + sessionOffsetMs
        if (out > lastOutMs) lastOutMs = out
        return out
    }

    private fun parseAvcConfig(data: ByteArray) {
        // FLV header (5) + AVCDecoderConfigurationRecord
        try {
            var p = 5
            nalLengthSize = (data[p + 4].toInt() and 3) + 1
            val spsCount = data[p + 5].toInt() and 0x1F
            p += 6
            for (i in 0 until spsCount) {
                val len = u16(data, p)
                if (i == 0) sps = data.copyOfRange(p + 2, p + 2 + len)
                p += 2 + len
            }
            val ppsCount = data[p].toInt() and 0xFF
            p += 1
            for (i in 0 until ppsCount) {
                val len = u16(data, p)
                if (i == 0) pps = data.copyOfRange(p + 2, p + 2 + len)
                p += 2 + len
            }
            logger.log("Config H.264 reçue (SPS ${sps?.size} o, PPS ${pps?.size} o)")
            val s = sps
            val pp = pps
            if (s != null && pp != null) processor?.configure(s, pp, if (width > 0) width else 1280, if (height > 0) height else 720)
        } catch (e: IndexOutOfBoundsException) {
            logger.log("Config H.264 invalide")
        }
    }

    private fun parseAacConfig(data: ByteArray) {
        if (data.size < 4) return
        val b0 = data[2].toInt() and 0xFF
        val b1 = data[3].toInt() and 0xFF
        val objectType = b0 shr 3
        aacFreqIndex = ((b0 and 7) shl 1) or (b1 shr 7)
        aacChannels = (b1 shr 3) and 0xF
        if (objectType !in 1..4 || aacFreqIndex == 15) {
            logger.log("Config AAC non supportée (type $objectType)")
            aacProfile = -1
            return
        }
        aacProfile = objectType - 1
        muxer.setHasAudio(true)
        logger.log("Config AAC reçue (${AAC_RATES.getOrElse(aacFreqIndex) { 0 }} Hz, $aacChannels canaux)")
    }

    private inline fun forEachNal(data: ByteArray, block: (off: Int, len: Int) -> Unit) {
        var p = 5
        while (p + nalLengthSize <= data.size) {
            var len = 0
            repeat(nalLengthSize) { len = (len shl 8) or (data[p++].toInt() and 0xFF) }
            if (len <= 0 || len > data.size - p) break
            block(p, len)
            p += len
        }
    }

    private fun putStartCode(out: ByteArray, off: Int): Int {
        out[off] = 0
        out[off + 1] = 0
        out[off + 2] = 0
        out[off + 3] = 1
        return off + 4
    }

    private fun putNal(out: ByteArray, off: Int, nal: ByteArray, len: Int): Int {
        val o = putStartCode(out, off)
        System.arraycopy(nal, 0, out, o, len)
        return o + len
    }

    private fun u16(b: ByteArray, off: Int): Int = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private companion object {
        const val PTS_OFFSET = 45_000L // 0.5 s of headroom between PCR and DTS
        const val PCR_ONLY_INTERVAL_NS = 100_000_000L
        const val MIN_HOLD_MS = 5_000L
        const val MAX_HOLD_MS = 60_000L
        const val RELAPSE_WINDOW_MS = 15_000L
        val AUD = byteArrayOf(0x09, 0xF0.toByte())
        val AAC_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    }
}

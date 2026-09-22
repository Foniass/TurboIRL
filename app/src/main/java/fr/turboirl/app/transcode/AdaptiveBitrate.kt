package fr.turboirl.app.transcode

import fr.turboirl.app.SrtSender
import fr.turboirl.core.rtmp.Logger

/**
 * Drives the transcoder from the SRT sender's state: cut the bitrate hard as soon as the send
 * buffer builds up (or packets get dropped), climb back once it has stayed empty. Never climbs
 * while video is withheld, and restarts from the floor when it comes back. The encoder's real
 * output is measured and the requested bitrate corrected when the hardware overshoots.
 * The output resolution follows the bitrate with a strong hysteresis; below [LOW_KBPS] the
 * frame rate is halved so that the picture stays legible.
 */
class AdaptiveBitrate(
    private val srt: SrtSender,
    private val transcoder: VideoTranscoder,
    private val latencyMs: Int,
    private val minKbps: Int,
    private val maxKbps: Int,
    private val maxHeight: Int,
    /** Audio + TS overhead to leave room for, in kb/s. */
    private val audioOverheadKbps: Int,
    private val videoSuspended: () -> Boolean,
    private val logger: Logger,
) {
    @Volatile var targetKbps = 0
        private set

    @Volatile var height = 0
        private set

    /** Requested / measured ratio applied to the encoder setting (1 = encoder is honest). */
    @Volatile var correction = 1.0
        private set

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread(::loop, "abr").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        // With a long latency budget, short dips are meant to be absorbed: only brake once a real backlog forms
        val hiMs = (latencyMs / 4).coerceIn(400, 2500)
        val loMs = (latencyMs / 20).coerceIn(100, 500)
        var target = (maxKbps * 4 / 10).coerceIn(minKbps, maxKbps)
        var lastDropped = srt.stats.dropped
        var lastCut = 0L
        var lastRaise = 0L
        var drainedSince = 0L
        var lastLog = 0L
        var wasSuspended = false
        var candidate = 0
        var candidateSince = 0L
        var lastSwitch = 0L
        val outSamples = ArrayDeque<Pair<Long, Long>>() // (time ms, bytesOut)
        var lastCorrectionLog = 0L
        var lastTargetChange = 0L
        height = ladder(target, 0)
        apply(target)
        transcoder.setOutputHeight(height)
        try {
            while (running) {
                Thread.sleep(500)
                val st = srt.stats
                val now = System.currentTimeMillis()
                val suspended = videoSuspended()
                if (suspended && !wasSuspended) {
                    // Audio priority took over: start again from the floor when video comes back
                    target = minKbps
                    apply(target)
                    lastCut = now
                    lastTargetChange = now
                    outSamples.clear()
                    logger.log("Encodeur ramené à $minKbps kb/s (vidéo suspendue)")
                }
                wasSuspended = suspended
                if (!st.connected) {
                    drainedSince = 0
                    continue
                }
                val dropped = st.dropped - lastDropped
                lastDropped = st.dropped
                val buffer = st.sendBufferMs
                val egressKbps = (st.sendRateMbps * 1000).toInt()
                val before = target
                if (buffer > hiMs || dropped > 0) {
                    drainedSince = 0
                    if (now - lastCut > 1000) {
                        // Under what the link just carried, minus room for audio + TS overhead
                        val fromLink = if (egressKbps > 0) (egressKbps - audioOverheadKbps) * 8 / 10 else target
                        target = minOf(target * 7 / 10, fromLink).coerceIn(minKbps, maxKbps)
                        if (dropped > 0) transcoder.requestKeyframe()
                        lastCut = now
                    }
                } else if (buffer < loMs && !suspended) {
                    if (drainedSince == 0L) drainedSince = now
                    if (now - drainedSince > 2000 && now - lastCut > 3000 && now - lastRaise > 1500) {
                        target = (target * 115 / 100 + 50).coerceIn(minKbps, maxKbps)
                        lastRaise = now
                    }
                } else {
                    drainedSince = 0
                }

                // Measured output vs requested: MediaTek's encoder (VBR only) overshoots on busy scenes.
                // Only judged once the target has been stable for a while, else the window still
                // holds output produced for the previous target and the correction spirals down.
                if (target != before) {
                    lastTargetChange = now
                    outSamples.clear()
                }
                outSamples.addLast(now to transcoder.stats.bytesOut)
                while (outSamples.size > 1 && now - outSamples.first().first > 3000) outSamples.removeFirst()
                val oldest = outSamples.first()
                if (!suspended && now - lastTargetChange >= 3000 && now - oldest.first >= 2000) {
                    val measuredKbps = ((transcoder.stats.bytesOut - oldest.second) * 8 / (now - oldest.first)).toInt()
                    val requested = (target * correction).toInt()
                    if (measuredKbps > requested * 125 / 100 && measuredKbps > minKbps) {
                        correction = (correction * 0.93).coerceAtLeast(0.6)
                    } else if (measuredKbps < requested * 90 / 100 && correction < 1.0) {
                        correction = (correction * 1.05).coerceAtMost(1.0)
                    }
                    if (now - lastCorrectionLog > 10_000 && correction < 0.95) {
                        lastCorrectionLog = now
                        logger.log("Encodeur : ${measuredKbps} kb/s mesurés pour $requested demandés, correction ×${"%.2f".format(correction)}")
                    }
                }

                if (target != before || correction != lastApplied) {
                    apply(target)
                    if (target != before && (target < before || now - lastLog > 10_000)) {
                        lastLog = now
                        logger.log("Encodeur → $target kb/s (tampon SRT $buffer ms, sortie $egressKbps kb/s${if (dropped > 0) ", $dropped perdus" else ""})")
                    }
                }

                // Resolution: down after 3 s, up after 20 s, never twice within 30 s
                val wanted = ladder(target, height)
                if (wanted == height) {
                    candidateSince = 0
                } else {
                    if (wanted != candidate) {
                        candidate = wanted
                        candidateSince = now
                    }
                    val holdMs = if (wanted < height) 3000 else 20_000
                    if (now - candidateSince >= holdMs && now - lastSwitch >= 30_000 && !suspended) {
                        logger.log("Résolution de sortie → ${wanted}p (débit $target kb/s)")
                        height = wanted
                        lastSwitch = now
                        transcoder.setOutputHeight(wanted)
                        candidateSince = 0
                    }
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    /** 480p under ~650 kb/s, 1080p only above ~3500 kb/s sustained, 720p otherwise. */
    private fun ladder(kbps: Int, current: Int): Int {
        val h = when {
            kbps >= (if (current >= 1080) KBPS_1080 * 9 / 10 else KBPS_1080) -> 1080
            kbps >= (if (current >= 720) KBPS_720 * 9 / 10 else KBPS_720 * 12 / 10) -> 720
            else -> 480
        }
        return minOf(h, maxHeight)
    }

    private var lastApplied = 0.0

    private fun apply(kbps: Int) {
        targetKbps = kbps
        lastApplied = correction
        transcoder.setBitrate((kbps * correction).toInt().coerceAtLeast(200))
        transcoder.setHalfRate(kbps < LOW_KBPS)
    }

    private companion object {
        const val LOW_KBPS = 700
        const val KBPS_720 = 650
        const val KBPS_1080 = 3500
    }
}

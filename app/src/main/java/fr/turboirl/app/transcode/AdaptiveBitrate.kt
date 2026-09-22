package fr.turboirl.app.transcode

import fr.turboirl.app.SrtSender
import fr.turboirl.core.rtmp.Logger

/**
 * Drives the transcoder from the SRT sender's state: cut the bitrate hard as soon as the send
 * buffer builds up (or packets get dropped), climb back slowly once it has stayed empty. The
 * output resolution follows the bitrate (480p / 720p / 1080p) with hysteresis, and below
 * [LOW_KBPS] the frame rate is halved so that the picture stays legible.
 */
class AdaptiveBitrate(
    private val srt: SrtSender,
    private val transcoder: VideoTranscoder,
    private val latencyMs: Int,
    private val minKbps: Int,
    private val maxKbps: Int,
    private val maxHeight: Int,
    private val logger: Logger,
) {
    @Volatile var targetKbps = 0
        private set

    @Volatile var height = 0
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
        val hiMs = latencyMs / 5      // 400 ms at 2 s: react well before audio priority (40 %) has to
        val loMs = latencyMs / 20     // 100 ms: considered drained
        var target = (maxKbps * 6 / 10).coerceIn(minKbps, maxKbps)
        var lastDropped = srt.stats.dropped
        var lastCut = 0L
        var lastRaise = 0L
        var drainedSince = 0L
        var lastLog = 0L
        var candidateSince = 0L
        var candidate = 0
        height = ladder(target)
        apply(target)
        transcoder.setOutputHeight(height)
        try {
            while (running) {
                Thread.sleep(500)
                val st = srt.stats
                val now = System.currentTimeMillis()
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
                        val fromLink = if (egressKbps > 0) (egressKbps - AUDIO_OVERHEAD_KBPS) * 8 / 10 else target
                        target = minOf(target * 7 / 10, fromLink).coerceIn(minKbps, maxKbps)
                        if (dropped > 0) transcoder.requestKeyframe()
                        lastCut = now
                    }
                } else if (buffer < loMs) {
                    if (drainedSince == 0L) drainedSince = now
                    if (now - drainedSince > 3000 && now - lastCut > 4000 && now - lastRaise > 2000) {
                        target = (target * 11 / 10 + 50).coerceIn(minKbps, maxKbps)
                        lastRaise = now
                    }
                } else {
                    drainedSince = 0
                }
                if (target != before) {
                    apply(target)
                    if (target < before || now - lastLog > 10_000) {
                        lastLog = now
                        logger.log("Encodeur → $target kb/s (tampon SRT $buffer ms, sortie $egressKbps kb/s${if (dropped > 0) ", $dropped perdus" else ""})")
                    }
                }

                // Resolution: down quickly, up only after the bitrate has held for a while
                val wanted = ladder(target)
                if (wanted == height) {
                    candidateSince = 0
                } else {
                    if (wanted != candidate) {
                        candidate = wanted
                        candidateSince = now
                    }
                    val holdMs = if (wanted < height) 2000 else 8000
                    if (now - candidateSince >= holdMs) {
                        logger.log("Résolution de sortie → ${wanted}p (débit $target kb/s)")
                        height = wanted
                        transcoder.setOutputHeight(wanted)
                        candidateSince = 0
                    }
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun ladder(kbps: Int): Int {
        val up = if (kbps >= height) 12 else 10 // 20 % more required to climb than to stay
        val h = when {
            kbps * 10 >= KBPS_1080 * up -> 1080
            kbps * 10 >= KBPS_720 * up -> 720
            else -> 480
        }
        return minOf(h, maxHeight)
    }

    private fun apply(kbps: Int) {
        targetKbps = kbps
        transcoder.setBitrate(kbps)
        transcoder.setHalfRate(kbps < LOW_KBPS)
    }

    private companion object {
        const val AUDIO_OVERHEAD_KBPS = 200
        const val LOW_KBPS = 700
        const val KBPS_720 = 900
        const val KBPS_1080 = 2500
    }
}

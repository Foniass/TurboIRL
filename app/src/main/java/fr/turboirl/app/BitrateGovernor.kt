package fr.turboirl.app

import fr.turboirl.core.rtmp.Logger
import fr.turboirl.core.rtmp.ReadLimiter

/**
 * Camera-side adaptive bitrate without re-encoding: when the SRT send buffer fills, the RTMP
 * reads are throttled just under what the uplink actually carries. The TCP window towards the
 * camera closes and a GoPro then lowers its encoder rate (down to its 800 kb/s floor). When the
 * buffer stays empty the throttle is released step by step. Audio priority (video suspension)
 * remains the safety net above this.
 */
class BitrateGovernor(
    private val srt: SrtSender,
    private val limiter: ReadLimiter,
    private val latencyMs: Int,
    private val maxKbps: Int,
    private val logger: Logger,
) {
    /** Current cap on the camera's bytes, 0 = none. */
    @Volatile var limitKbps = 0
        private set

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread(::loop, "bitrate-governor").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        limiter.bytesPerSec = 0
    }

    private fun loop() {
        val hiMs = latencyMs / 4          // start braking here (video suspension kicks in at 40 %)
        val loMs = latencyMs / 10         // considered drained below this
        val floorBps = FLOOR_KBPS * 125L
        val ceilingBps = maxKbps * 125L * 13 / 10
        var limitBps = 0L
        var lastBrakeAt = 0L
        var lastReleaseAt = 0L
        var drainedSince = 0L
        try {
            while (running) {
                Thread.sleep(500)
                val st = srt.stats
                if (!st.connected) {
                    if (limitBps != 0L) {
                        limitBps = 0
                        apply(limitBps)
                    }
                    continue
                }
                val now = System.currentTimeMillis()
                val buffer = st.sendBufferMs
                val egressBps = (st.sendRateMbps * 125_000).toLong()
                if (buffer > hiMs) {
                    drainedSince = 0
                    // Brake below the measured egress, and never more than 30 % at once.
                    val current = if (limitBps == 0L) ceilingBps else limitBps
                    var target = minOf(current, (egressBps * 0.8).toLong())
                    target = maxOf(target, current * 7 / 10, floorBps)
                    if (target < current && now - lastBrakeAt > 1500) {
                        limitBps = target
                        lastBrakeAt = now
                        apply(limitBps)
                        logger.log("Frein caméra → ${limitKbps} kb/s (tampon SRT $buffer ms, sortie ${egressBps * 8 / 1000} kb/s)")
                    }
                } else if (limitBps != 0L && buffer < loMs) {
                    if (drainedSince == 0L) drainedSince = now
                    if (now - drainedSince > 3000 && now - lastReleaseAt > 2000) {
                        lastReleaseAt = now
                        limitBps = limitBps * 115 / 100
                        if (limitBps >= ceilingBps) {
                            limitBps = 0
                            logger.log("Frein caméra relâché")
                        }
                        apply(limitBps)
                    }
                } else {
                    drainedSince = 0
                }
            }
        } catch (_: InterruptedException) {
        }
        limiter.bytesPerSec = 0
    }

    private fun apply(bps: Long) {
        limiter.bytesPerSec = bps
        limitKbps = (bps * 8 / 1000).toInt()
    }

    private companion object {
        // Below the GoPro's own minimum the camera cannot follow: let audio priority handle it.
        const val FLOOR_KBPS = 900
    }
}

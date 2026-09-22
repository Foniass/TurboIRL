package fr.turboirl.core.rtmp

/**
 * Token bucket on the bytes read from the publisher's socket. Reading slower than the camera
 * sends fills the TCP window, and a camera with adaptive bitrate (GoPro) lowers its encoder
 * rate in response. 0 = no limit.
 */
class ReadLimiter {
    @Volatile var bytesPerSec = 0L

    private var tokens = 0.0
    private var lastNs = 0L

    fun acquire(n: Int) {
        val rate = bytesPerSec
        if (rate <= 0) {
            tokens = 0.0
            lastNs = 0
            return
        }
        val now = System.nanoTime()
        if (lastNs != 0L) tokens = minOf(tokens + (now - lastNs) / 1e9 * rate, rate * BURST_SEC)
        lastNs = now
        if (tokens >= n) {
            tokens -= n
            return
        }
        val waitMs = ((n - tokens) / rate * 1000).toLong()
        tokens = 0.0
        if (waitMs > 0) Thread.sleep(minOf(waitMs, 1000))
        lastNs = System.nanoTime() // the wait itself must not be credited again as tokens
    }

    private companion object {
        const val BURST_SEC = 0.25
    }
}

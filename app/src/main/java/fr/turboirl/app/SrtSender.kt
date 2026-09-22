package fr.turboirl.app

import fr.turboirl.core.rtmp.Logger
import fr.turboirl.core.ts.TsMuxer
import fr.turboirl.core.ts.TsSink
import io.github.thibaultbee.srtdroid.core.enums.SockOpt
import io.github.thibaultbee.srtdroid.core.enums.Transtype
import io.github.thibaultbee.srtdroid.core.models.SrtSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * SRT caller that keeps reconnecting for as long as it runs. [write] never blocks: data is
 * dropped while disconnected, and the whole queue is dropped if the network can't keep up,
 * so that a slow uplink never stalls the camera's RTMP connection.
 */
class SrtSender(
    private val host: String,
    private val port: Int,
    private val latencyMs: Int,
    private val streamId: String,
    private val logger: Logger,
) : TsSink {

    class Stats {
        @Volatile var connected = false
        /** Send buffer is filling up: the uplink cannot keep up with the input. */
        @Volatile var congested = false
        @Volatile var rttMs = 0.0
        @Volatile var sendRateMbps = 0.0
        @Volatile var bandwidthMbps = 0.0
        @Volatile var sendBufferMs = 0
        @Volatile var flightPackets = 0
        @Volatile var sendBufferPackets = 0
        @Volatile var retransmitted = 0L
        @Volatile var dropped = 0L
        @Volatile var queueOverflows = 0L
        @Volatile var connections = 0
    }

    val stats = Stats()

    // Hysteresis on the SRT send buffer occupancy, relative to the latency budget
    private val congestOnMs = latencyMs * 2 / 5
    private val congestOffMs = latencyMs / 5

    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_PACKETS)
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread(::loop, "srt-sender").apply { start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(5000)
        thread = null
    }

    override fun write(buf: ByteArray, off: Int, len: Int) {
        if (!stats.connected) return
        if (!queue.offer(buf.copyOfRange(off, off + len))) {
            queue.clear()
            stats.queueOverflows++
        }
    }

    private fun loop() {
        var firstFailure = true
        while (running) {
            var socket: SrtSocket? = null
            try {
                socket = SrtSocket()
                socket.setSockFlag(SockOpt.TRANSTYPE, Transtype.LIVE)
                socket.setSockFlag(SockOpt.PAYLOADSIZE, TsMuxer.MAX_BATCH)
                socket.setSockFlag(SockOpt.LATENCY, latencyMs)
                socket.setSockFlag(SockOpt.CONNTIMEO, 4000)
                if (streamId.isNotEmpty()) socket.setSockFlag(SockOpt.STREAMID, streamId)
                socket.connect(host, port)

                logger.log("SRT connecté à $host:$port (latence $latencyMs ms)")
                firstFailure = true
                queue.clear()
                stats.connections++
                stats.connected = true
                pump(socket)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                // An unreachable PC is the normal state until OBS is started: log it once.
                if (stats.connected || firstFailure) logger.log("SRT : ${e.message ?: e.javaClass.simpleName}, nouvel essai toutes les ${RETRY_MS / 1000} s")
                firstFailure = false
            } finally {
                stats.connected = false
                stats.congested = false
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }
            try {
                if (running) Thread.sleep(RETRY_MS)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun pump(socket: SrtSocket) {
        var lastStatsNs = System.nanoTime()
        while (running) {
            val packet = queue.poll(100, TimeUnit.MILLISECONDS)
            if (packet != null) {
                socket.send(packet)
            } else if (!socket.isConnected) {
                throw java.net.SocketException("connexion perdue")
            }
            val now = System.nanoTime()
            if (now - lastStatsNs >= STATS_PERIOD_NS) {
                lastStatsNs = now
                val s = socket.bistats(clear = true, instantaneous = true)
                stats.rttMs = s.msRTT
                stats.sendRateMbps = s.mbpsSendRate
                stats.bandwidthMbps = s.mbpsBandwidth
                stats.sendBufferMs = s.msSndBuf
                stats.flightPackets = s.pktFlightSize
                stats.sendBufferPackets = s.pktSndBuf
                stats.retransmitted += s.pktRetrans
                stats.dropped += s.pktSndDrop
                if (s.msSndBuf >= congestOnMs) stats.congested = true
                else if (s.msSndBuf <= congestOffMs) stats.congested = false
            }
        }
    }

    private companion object {
        const val QUEUE_PACKETS = 3000 // ~4 MB, several seconds of video
        const val RETRY_MS = 2000L
        const val STATS_PERIOD_NS = 250_000_000L
    }
}

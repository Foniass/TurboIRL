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
 * dropped while disconnected, and video is dropped before audio when the network can't keep up,
 * so that a slow uplink never stalls the camera's RTMP connection.
 *
 * The backlog is kept in this class's own queue rather than in libsrt's send buffer (24/09): libsrt
 * discards whatever sat in its buffer for longer than the latency, audio included (4675 packets and
 * 100-900 ms holes in the sound on a 12 s saturation). Here the socket is only fed while its buffer
 * holds less than [SRT_BUFFER_CAP_MS]; everything else waits in the queue, where video batches can be
 * discarded and audio kept. Audio then arrives late instead of being lost; the PC absorbs the burst.
 */
class SrtSender(
    private val host: String,
    private val port: Int,
    private val latencyMs: Int,
    private val logger: Logger,
    /** Backlog (fraction of the latency) above which video gets withheld. */
    congestOnFraction: Double = 0.6,
    /** SRT stream id, e.g. `publish:turboirl:phone:<token>` for the MediaMTX relay on the VPS; empty = none. */
    private val streamId: String = "",
) : TsSink {

    class Stats {
        @Volatile var connected = false
        /** Backlog is filling up: the uplink cannot keep up with the input. */
        @Volatile var congested = false
        /** Backlog close to the latency: video is being held back, only audio goes out. */
        @Volatile var critical = false
        @Volatile var videoDroppedForAudio = 0L
        @Volatile var rttMs = 0.0
        @Volatile var sendRateMbps = 0.0
        @Volatile var bandwidthMbps = 0.0
        /** Total wait before data leaves the phone: libsrt's buffer plus the age of the oldest queued batch. */
        @Volatile var sendBufferMs = 0
        /** libsrt's own buffer only (kept under [SRT_BUFFER_CAP_MS] while the link is alive). */
        @Volatile var srtBufferMs = 0
        @Volatile var flightPackets = 0
        @Volatile var sendBufferPackets = 0
        @Volatile var retransmitted = 0L
        @Volatile var dropped = 0L
        @Volatile var queueOverflows = 0L
        @Volatile var connections = 0
    }

    val stats = Stats()

    private class Batch(val data: ByteArray, val audioOnly: Boolean) {
        val at = System.nanoTime()
    }

    // Hysteresis on the backlog, relative to the latency budget
    private val congestOnMs = (latencyMs * congestOnFraction).toInt()
    private val congestOffMs = latencyMs / 5
    // Beyond this the sound itself would soon be late by more than the latency: drop video, send audio only
    private val criticalOnMs = latencyMs * 4 / 5
    private val criticalOffMs = latencyMs / 2

    private val queue = ArrayBlockingQueue<Batch>(QUEUE_PACKETS)
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

    override fun write(buf: ByteArray, off: Int, len: Int, audioOnly: Boolean) {
        if (!stats.connected) return
        if (stats.critical && !audioOnly) {
            stats.videoDroppedForAudio++
            return
        }
        val batch = Batch(buf.copyOfRange(off, off + len), audioOnly)
        if (!queue.offer(batch)) {
            // Full (several seconds of video): make room by discarding the queued video, audio stays
            if (dropQueuedVideo() == 0 || !queue.offer(batch)) {
                queue.clear()
                stats.queueOverflows++
            }
        }
    }

    /** Removes every video batch waiting in the queue; returns how many were removed. */
    private fun dropQueuedVideo(): Int {
        var removed = 0
        val it = queue.iterator()
        while (it.hasNext()) {
            if (!it.next().audioOnly) {
                it.remove()
                removed++
            }
        }
        stats.videoDroppedForAudio += removed
        return removed
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
                // An unreachable server is the normal state until it is started: log it once.
                if (stats.connected || firstFailure) logger.log("SRT : ${e.message ?: e.javaClass.simpleName}, nouvel essai toutes les ${RETRY_MS / 1000} s")
                firstFailure = false
            } finally {
                stats.connected = false
                stats.congested = false
                stats.critical = false
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
        var lastStatsNs = 0L
        var paced = false
        while (running) {
            val now = System.nanoTime()
            if (now - lastStatsNs >= STATS_PERIOD_NS) {
                lastStatsNs = now
                val s = socket.bistats(clear = true, instantaneous = true)
                stats.rttMs = s.msRTT
                stats.sendRateMbps = s.mbpsSendRate
                stats.bandwidthMbps = s.mbpsBandwidth
                stats.srtBufferMs = s.msSndBuf
                stats.flightPackets = s.pktFlightSize
                stats.sendBufferPackets = s.pktSndBuf + queue.size
                stats.retransmitted += s.pktRetrans
                stats.dropped += s.pktSndDrop
                val queueMs = queue.peek()?.let { ((now - it.at) / 1_000_000L).toInt() } ?: 0
                val backlog = s.msSndBuf + queueMs
                stats.sendBufferMs = backlog
                paced = s.msSndBuf >= SRT_BUFFER_CAP_MS
                if (backlog >= congestOnMs) stats.congested = true
                else if (backlog <= congestOffMs) stats.congested = false
                if (!stats.critical && backlog >= criticalOnMs) {
                    stats.critical = true
                    val purged = dropQueuedVideo()
                    logger.log("Retard d'envoi à $backlog ms (SRT ${s.msSndBuf} ms) : vidéo retenue, seul le son part ($purged lots vidéo en attente jetés)")
                } else if (stats.critical && backlog <= criticalOffMs) {
                    stats.critical = false
                    logger.log("Retard d'envoi redescendu à $backlog ms : vidéo relâchée")
                }
            }
            if (paced) {
                // libsrt's buffer is full enough: let the network drain it, the backlog waits here
                if (!socket.isConnected) throw java.net.SocketException("connexion perdue")
                Thread.sleep(PACE_SLEEP_MS)
                continue
            }
            val batch = queue.poll(PACE_SLEEP_MS, TimeUnit.MILLISECONDS)
            if (batch != null) {
                socket.send(batch.data)
            } else if (!socket.isConnected) {
                throw java.net.SocketException("connexion perdue")
            }
        }
    }

    private companion object {
        const val QUEUE_PACKETS = 3000 // ~4 MB, several seconds of video or an hour of audio alone
        const val RETRY_MS = 2000L
        const val STATS_PERIOD_NS = 100_000_000L
        const val PACE_SLEEP_MS = 20L
        // libsrt discards packets older than the latency; with at most this much inside, a dead link costs at
        // most this much sound, the rest waits in the queue and is sent late but whole
        const val SRT_BUFFER_CAP_MS = 2000
    }
}

package fr.turboirl.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import fr.turboirl.core.rtmp.Logger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Splits one SRT stream over every network the phone has (its own cellular link, the Wi-Fi of the other phone's
 * hotspot) towards the bond service on the VPS, which merges them back in front of the SRT relay.
 *
 * libsrt connects to this class on 127.0.0.1:[localPort]; every SRT datagram it emits is wrapped (8-byte header:
 * "TB", type, link id, session id) and sent on a link chosen like this:
 *  - audio-only datagrams (no video TS packet inside) go on every live link: the sound survives a dead link
 *    without even a switch, for ~3 % of the data;
 *  - SRT retransmissions go on the safest link;
 *  - video goes on the healthy link that is furthest behind its target share, so that by the end of the stream
 *    each plan has carried its share of the bytes (quality first: a suspect link carries nothing but audio).
 * Link health: a ping every 100 ms per link; 3 unanswered in a row = suspect (≈ 400 ms), then the last second sent
 * on it is replayed on another link without waiting for SRT. 5 answers in a row = back in service.
 *
 * With a single network everything goes on it: same behaviour as before, plus the pings.
 * Test mode: per-link impairments (loss, delay, rate cap, periodic outages) applied on the way out.
 */
class LinkMux(
    private val context: Context,
    private val host: String,
    private val port: Int,
    private val logger: Logger,
    /** Target share of the bytes per link kind (0 = backup only); missing kinds get 0. */
    private val shares: Map<Int, Double>,
    private val impairments: Map<Int, Impairment> = emptyMap(),
) {
    /** Simulated faults for the bench (all zero = none). */
    data class Impairment(val lossPct: Double = 0.0, val delayMs: Int = 0, val kbps: Int = 0, val outageEverySec: Int = 0, val outageSec: Int = 0) {
        val active get() = lossPct > 0 || delayMs > 0 || kbps > 0 || (outageEverySec > 0 && outageSec > 0)

        companion object {
            /** "pertes%,délai ms,kb/s max,toutes les s,durée s" — any prefix, blanks = 0. */
            fun parse(s: String): Impairment {
                val p = s.split(",").map { it.trim().toDoubleOrNull() ?: 0.0 }
                fun at(i: Int) = p.getOrElse(i) { 0.0 }
                return Impairment(at(0), at(1).toInt(), at(2).toInt(), at(3).toInt(), at(4).toInt())
            }
        }
    }

    class LinkStats(
        val name: String, val kind: Int, val state: String, val rttMs: Int, val lossPct: Int,
        val sentBytes: Long, val sharePct: Int, val targetPct: Int, val kbps: Int, val outage: Boolean,
    )

    private inner class Link(val kind: Int, val name: String, val network: Network?) {
        val id = nextId++
        val socket = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(0)) }
        val impair = impairments[kind] ?: Impairment()
        val share = shares[kind] ?: 0.0
        @Volatile var sentBytes = 0L
        @Volatile var lastPongNs = 0L
        @Volatile var rttMs = 0.0
        @Volatile var baseRttMs = 0.0        // RTT plancher récent : la référence pour détecter une file qui gonfle
        @Volatile var congested = false      // RTT bien au-dessus du plancher = le lien met en file (bufferbloat)
        @Volatile var capKbps = CAP_MAX      // débit vidéo maximal confié à ce lien, abaissé quand il s'encombre
        private var congestedSinceNs = 0L
        private var clearSinceNs = 0L
        private var lastCapChangeNs = 0L
        private val sends = ArrayDeque<LongArray>()   // [ns, octets] de la dernière seconde
        private var windowBytes = 0L
        @Volatile var suspect = false
        @Volatile var justFell = false
        var misses = 0
        var streak = 0
        val pings = ArrayDeque<LongArray>()          // [sentNs, answered]
        val recent = ArrayDeque<Pair<Long, ByteArray>>()
        val outQueue = LinkedBlockingQueue<Pair<Long, ByteArray>>()
        val rng = Random(id + 1)
        var queueUntilNs = 0L
        val t0Ns = System.nanoTime()
        var rateBytes = 0L
        var rateNs = System.nanoTime()
        @Volatile var kbps = 0
        val sender = Thread({ senderLoop() }, "link-out-$name")
        val reader = Thread({ readerLoop() }, "link-in-$name")

        fun start() {
            network?.bindSocket(socket)
            sender.isDaemon = true
            reader.isDaemon = true
            sender.start()
            reader.start()
        }

        fun close() {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            sender.interrupt()
        }

        val down: Boolean
            get() {
                val o = impair
                if (o.outageEverySec <= 0 || o.outageSec <= 0) return false
                val t = (System.nanoTime() - t0Ns) / 1e9
                return t % o.outageEverySec >= o.outageEverySec - o.outageSec
            }

        /** Bytes sent over the last second (all packet kinds), the figure compared with the cap. */
        fun rateKbps(): Int {
            val now = System.nanoTime()
            synchronized(sends) {
                while (sends.isNotEmpty() && now - sends.first()[0] > 1_000_000_000L) windowBytes -= sends.removeFirst()[1]
                return (windowBytes * 8 / 1000).toInt()
            }
        }

        /** Sends with the simulated faults of test mode (none in normal use). */
        fun send(pkt: ByteArray) {
            val now = System.nanoTime()
            synchronized(sends) {
                sends.addLast(longArrayOf(now, pkt.size.toLong()))
                windowBytes += pkt.size
                while (sends.isNotEmpty() && now - sends.first()[0] > 1_000_000_000L) windowBytes -= sends.removeFirst()[1]
            }
            val o = impair
            var release = now + o.delayMs * 1_000_000L
            if (o.active) {
                if (down || (o.lossPct > 0 && rng.nextDouble() * 100 < o.lossPct)) return
                if (o.kbps > 0) {
                    // a bottleneck with a queue, like a cell: packets wait their turn (RTT grows), dropped only once
                    // the queue holds 2 s (real bufferbloat, what the cap detection must react to)
                    val serviceNs = pkt.size * 8_000_000L / o.kbps
                    queueUntilNs = maxOf(queueUntilNs, now) + serviceNs
                    if (queueUntilNs - now > 2_000_000_000L) {
                        queueUntilNs -= serviceNs
                        return
                    }
                    release = queueUntilNs + o.delayMs * 1_000_000L
                }
            }
            outQueue.offer(release to pkt)
        }

        private fun senderLoop() {
            try {
                while (running) {
                    val (release, pkt) = outQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    val addr = address ?: try {
                        InetAddress.getByName(host).also { address = it }
                    } catch (e: Exception) {
                        continue  // DNS indisponible pour l'instant : le paquet est perdu, on réessaie au suivant
                    }
                    val dest = InetSocketAddress(addr, port)
                    val wait = release - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
                    try {
                        socket.send(DatagramPacket(pkt, pkt.size, dest))
                    } catch (_: Exception) {
                    }
                }
            } catch (_: InterruptedException) {
            }
        }

        private fun readerLoop() {
            val buf = ByteArray(2048)
            val dp = DatagramPacket(buf, buf.size)
            while (running && !socket.isClosed) {
                try {
                    dp.length = buf.size  // receive() shrinks the packet to the last datagram's size: reset or truncate
                    socket.receive(dp)
                } catch (_: Exception) {
                    if (!running || socket.isClosed) return
                    continue
                }
                if (dp.length < HDR || buf[0] != 'T'.code.toByte() || buf[1] != 'B'.code.toByte()) continue
                when (buf[2].toInt()) {
                    T_PONG -> onPong(ByteBuffer.wrap(buf, HDR, 8).long)
                    T_SRT -> onReturn(buf.copyOfRange(HDR, dp.length))
                }
            }
        }

        fun ping(sid: Int) {
            val now = System.nanoTime()
            synchronized(pings) {
                // pings older than the timeout still unanswered count as misses; 3 in a row = suspect
                val timeoutNs = maxOf(250_000_000L, (rttMs * 2.5 * 1_000_000).toLong())
                var consecutive = 0
                for (p in pings.descendingIterator()) {
                    if (now - p[0] < timeoutNs) continue
                    if (p[1] == 0L) consecutive++ else break
                }
                misses = consecutive
                if (misses >= SUSPECT_MISSES && !suspect) {
                    suspect = true
                    justFell = true
                    streak = 0
                }
                pings.addLast(longArrayOf(now, 0L))
                while (pings.size > 40) pings.removeFirst()
            }
            val pkt = ByteArray(HDR + 8)
            header(pkt, T_PING, id, sid)
            ByteBuffer.wrap(pkt, HDR, 8).putLong(now)
            send(pkt)
        }

        private fun onPong(sentNs: Long) {
            val now = System.nanoTime()
            val rtt = (now - sentNs) / 1e6
            rttMs = if (rttMs == 0.0) rtt else rttMs * 0.7 + rtt * 0.3
            lastPongNs = now
            // floor of the RTT: follows drops at once, drifts up slowly (a cell that gets worse for good)
            baseRttMs = when {
                baseRttMs == 0.0 -> rtt
                rtt < baseRttMs -> rtt
                else -> baseRttMs + (rtt - baseRttMs) * 0.01
            }
            updateCongestion(now)
            synchronized(pings) {
                for (p in pings) if (p[0] == sentNs) p[1] = 1L
                streak++
                misses = 0
                if (suspect && streak >= RECOVER_PONGS) {
                    suspect = false
                    if (links.size > 1) logger.log("Lien $name rétabli (RTT ${rttMs.toInt()} ms)")
                }
            }
        }

        /**
         * Bufferbloat control. A link whose RTT climbs well above its floor is queueing our packets (a saturated
         * cell): everything on it arrives late, the relay asks for retransmissions, and the other link drowns under
         * them (26/09, home test: output 9-11 Mb/s for 3.5 Mb/s encoded, video held every 2-3 min). So the video
         * confided to that link is capped: 0.7× its current rate at each second of congestion, raised 20 % per
         * second once the RTT is back near the floor. The plan split is kept whenever both links have room.
         */
        private fun updateCongestion(now: Long) {
            val base = baseRttMs
            val high = rttMs > base + maxOf(120.0, base)
            val low = rttMs < base + maxOf(60.0, base * 0.5)
            if (high) {
                clearSinceNs = 0L
                if (congestedSinceNs == 0L) congestedSinceNs = now
                if (now - lastCapChangeNs > 1_000_000_000L) {
                    val cap = maxOf(CAP_MIN, (minOf(capKbps, maxOf(rateKbps(), 1)) * 0.7).toInt())
                    lastCapChangeNs = now
                    if (!congested) {
                        congested = true
                        logger.log("Lien $name encombré (RTT ${rttMs.toInt()} ms, plancher ${base.toInt()} ms) : vidéo plafonnée à $cap kb/s")
                    }
                    capKbps = cap
                }
            } else {
                congestedSinceNs = 0L
                if (low) {
                    if (clearSinceNs == 0L) clearSinceNs = now
                    if (now - clearSinceNs > 2_000_000_000L && capKbps < CAP_MAX && now - lastCapChangeNs > 1_000_000_000L) {
                        capKbps = minOf(CAP_MAX, (capKbps * 1.2).toInt() + 100)
                        lastCapChangeNs = now
                        if (congested) {
                            congested = false
                            logger.log("Lien $name dégagé (RTT ${rttMs.toInt()} ms) : plafond ${capKbps} kb/s, relevé de 20 % par seconde")
                        }
                    }
                } else {
                    clearSinceNs = 0L
                }
            }
        }

        fun lossPct(): Int {
            val now = System.nanoTime()
            synchronized(pings) {
                val done = pings.filter { now - it[0] > 1_000_000_000L }
                if (done.isEmpty()) return 0
                return 100 * done.count { it[1] == 0L } / done.size
            }
        }

        fun state(): String = when {
            lastPongNs == 0L && System.nanoTime() - t0Ns < 5_000_000_000L -> "démarrage"
            System.nanoTime() - lastPongNs > 5_000_000_000L -> "mort"
            suspect -> "suspect"
            lossPct() > 20 || rttMs > 1500 -> "dégradé"
            congested -> "encombré"
            else -> "ok"
        }

        fun usable() = state().let { it == "ok" || it == "dégradé" || it == "encombré" }

        fun remember(payload: ByteArray) {
            val now = System.nanoTime()
            synchronized(recent) {
                recent.addLast(now to payload)
                while (recent.isNotEmpty() && now - recent.first.first > REPLAY_NS) recent.removeFirst()
            }
        }

        fun stats(totalBytes: Long): LinkStats {
            val now = System.nanoTime()
            if (now - rateNs >= 1_000_000_000L) {
                kbps = ((sentBytes - rateBytes) * 8 / ((now - rateNs) / 1_000_000L)).toInt()
                rateBytes = sentBytes
                rateNs = now
            }
            return LinkStats(
                name, kind, state(), rttMs.toInt(), lossPct(), sentBytes,
                if (totalBytes > 0) (sentBytes * 100 / totalBytes).toInt() else 0, (share * 100).toInt(), kbps, down,
            )
        }
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    // IPv4 loopback explicitly: on Android getLoopbackAddress() can be ::1, which never sees libsrt's 127.0.0.1 packets
    private val local = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
    val localPort: Int get() = local.localPort
    private val sid = Random.nextInt()
    private val links = java.util.concurrent.CopyOnWriteArrayList<Link>()
    private var nextId = 0
    @Volatile private var running = false
    @Volatile private var libsrt: InetSocketAddress? = null
    @Volatile private var address: InetAddress? = null   // résolu hors du thread principal (Android l'interdit dessus)
    private val returnSeen = ArrayDeque<Int>()
    private val returnSet = HashSet<Int>()
    private var audioPackets = 0L
    private var videoPackets = 0L
    private var replayed = 0L
    private var retransmitted = 0L
    private val callbacks = ArrayList<ConnectivityManager.NetworkCallback>()

    fun start() {
        running = true
        Thread({ localLoop() }, "mux-local").apply { isDaemon = true }.start()
        Thread({ pingLoop() }, "mux-ping").apply { isDaemon = true }.start()
        watch(NetworkCapabilities.TRANSPORT_CELLULAR, KIND_CELL, "5G", request = true)
        watch(NetworkCapabilities.TRANSPORT_WIFI, KIND_WIFI, "Wi-Fi", request = false)
        // no network callback within 3 s (odd setups): the default route carries everything
        Thread {
            Thread.sleep(3000)
            if (running && links.isEmpty()) addLink(KIND_OTHER, "défaut", null)
        }.apply { isDaemon = true }.start()
        logger.log("Répartiteur de liens prêt (${local.localAddress.hostAddress}:$localPort → $host:$port), parts visées : " +
            shares.entries.joinToString { "${KIND_NAMES[it.key]} ${(it.value * 100).toInt()} %" })
    }

    private fun watch(transport: Int, kind: Int, name: String, request: Boolean) {
        val req = NetworkRequest.Builder().addTransportType(transport).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                addLink(kind, name, network)
            }

            override fun onLost(network: Network) {
                removeLink(network)
            }
        }
        try {
            // requestNetwork keeps the cellular link up while Wi-Fi is connected (the OS would drop it otherwise)
            if (request) cm.requestNetwork(req, cb) else cm.registerNetworkCallback(req, cb)
            callbacks.add(cb)
        } catch (e: Exception) {
            logger.log("Réseau $name : surveillance impossible (${e.message})")
        }
    }

    @Synchronized
    private fun addLink(kind: Int, name: String, network: Network?) {
        if (!running) return
        if (links.any { it.network == network && it.kind == kind }) return
        links.filter { it.kind == KIND_OTHER }.forEach { removeLink(it) }  // a real link replaces the default one
        val link = Link(kind, name, network)
        try {
            link.start()
        } catch (e: Exception) {
            logger.log("Lien $name : impossible d'ouvrir le socket (${e.message})")
            return
        }
        links.add(link)
        logger.log("Lien $name disponible" + (if (link.impair.active) " (pannes simulées ${link.impair})" else "") +
            (if (link.share == 0.0 && shares.isNotEmpty()) ", en secours (son seul)" else ""))
    }

    private fun removeLink(network: Network) {
        links.filter { it.network == network }.forEach { removeLink(it) }
    }

    @Synchronized
    private fun removeLink(link: Link) {
        if (links.remove(link)) {
            link.close()
            logger.log("Lien ${link.name} perdu (${link.sentBytes / 1_000_000} Mo envoyés)")
        }
    }

    // ---------------------------------------------------------------- libsrt → liens
    private fun localLoop() {
        val buf = ByteArray(2048)
        val dp = DatagramPacket(buf, buf.size)
        var first = true
        while (running) {
            try {
                dp.length = buf.size
                local.receive(dp)
            } catch (_: Exception) {
                if (!running) return
                continue
            }
            libsrt = InetSocketAddress(dp.address, dp.port)
            val payload = buf.copyOfRange(0, dp.length)
            if (first) {
                first = false
                logger.log("Répartiteur : premier paquet SRT reçu de libsrt (${payload.size} octets), ${links.size} lien(s)")
            }
            try {
                dispatch(payload)
            } catch (e: Exception) {
                logger.log("Répartiteur : erreur d'envoi (${e.javaClass.simpleName}: ${e.message})")
            }
        }
    }

    private fun dispatch(payload: ByteArray) {
        val all = links.toList()
        if (all.isEmpty()) return
        val alive = all.filter { it.state() != "mort" }.ifEmpty { all }
        val usable = alive.filter { it.usable() || it.state() == "démarrage" }.ifEmpty { alive }
        val targets: List<Link> = when {
            audioOnly(payload) -> { audioPackets++; alive }
            isRetransmission(payload) -> { retransmitted++; listOf(usable.minByOrNull { it.lossPct() * 10_000 + it.rttMs.toInt() }!!) }
            else -> {
                videoPackets++
                val withShare = usable.filter { it.share > 0 }.ifEmpty { usable }
                val healthy = withShare.filter { it.state() == "ok" || it.state() == "démarrage" || it.state() == "encombré" }.ifEmpty { withShare }
                // a capped link takes video only while under its cap; the plan split rules among the links with room
                val room = healthy.filter { it.rateKbps() < it.capKbps }.ifEmpty { healthy }
                listOf(room.minByOrNull { it.sentBytes / maxOf(it.share, 1e-6) }!!)
            }
        }
        for (link in targets) {
            val pkt = ByteArray(HDR + payload.size)
            header(pkt, T_SRT, link.id, sid)
            System.arraycopy(payload, 0, pkt, HDR, payload.size)
            link.sentBytes += payload.size
            link.send(pkt)
            if (targets.size == 1) link.remember(payload)
        }
    }

    // ---------------------------------------------------------------- liens → libsrt
    private fun onReturn(payload: ByteArray) {
        val dest = libsrt ?: return
        val h = payload.contentHashCode()
        synchronized(returnSeen) {
            if (!returnSet.add(h)) return   // the same return packet may arrive on several links
            returnSeen.addLast(h)
            if (returnSeen.size > 256) returnSet.remove(returnSeen.removeFirst())
        }
        try {
            local.send(DatagramPacket(payload, payload.size, dest))
        } catch (_: Exception) {
        }
    }

    private fun pingLoop() {
        while (running) {
            try {
                Thread.sleep(PING_MS)
            } catch (_: InterruptedException) {
                return
            }
            for (link in links) {
                link.ping(sid)
                if (link.justFell) {
                    link.justFell = false
                    val others = links.filter { it !== link && it.usable() }
                    val replay = synchronized(link.recent) { link.recent.map { it.second }.also { link.recent.clear() } }
                    if (others.isNotEmpty() && replay.isNotEmpty()) {
                        val o = others.minByOrNull { it.lossPct() * 10_000 + it.rttMs.toInt() }!!
                        for (payload in replay) {
                            val pkt = ByteArray(HDR + payload.size)
                            header(pkt, T_SRT, o.id, sid)
                            System.arraycopy(payload, 0, pkt, HDR, payload.size)
                            o.sentBytes += payload.size
                            o.send(pkt)
                        }
                        replayed += replay.size
                        logger.log("Lien ${link.name} suspect (${link.misses} pings sans réponse) : ${replay.size} paquets de la dernière seconde renvoyés par ${o.name}")
                    } else if (links.size > 1) {
                        // single link: every 5G hiccup would fill the journal with suspect/rétabli pairs for nothing
                        logger.log("Lien ${link.name} suspect (${link.misses} pings sans réponse)" + if (others.isEmpty()) ", aucun autre lien" else "")
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- état
    fun snapshot(): List<LinkStats> {
        val total = links.sumOf { it.sentBytes }
        return links.map { it.stats(total) }
    }

    /** One line for the journal at the end: bytes per link against the target. */
    fun bilan(): String {
        val total = links.sumOf { it.sentBytes }
        if (total == 0L) return "Bilan liens : rien envoyé"
        val parts = links.joinToString(", ") {
            "${it.name} ${"%.0f".format(it.sentBytes / 1e6)} Mo (${it.sentBytes * 100 / total} %, visé ${(it.share * 100).toInt()} %)"
        }
        return "Bilan liens : $parts ; son dupliqué $audioPackets paquets, vidéo $videoPackets, retransmissions $retransmitted, rejoués $replayed"
    }

    fun stop() {
        running = false
        for (cb in callbacks) try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
        callbacks.clear()
        for (link in links.toList()) removeLink(link)
        try {
            local.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val KIND_CELL = 0
        const val KIND_WIFI = 1
        const val KIND_OTHER = 2
        val KIND_NAMES = mapOf(KIND_CELL to "5G", KIND_WIFI to "Wi-Fi", KIND_OTHER to "défaut")
        private const val HDR = 8
        private const val T_SRT = 0
        private const val T_PING = 1
        private const val T_PONG = 2
        private const val PING_MS = 100L
        private const val SUSPECT_MISSES = 3
        private const val RECOVER_PONGS = 5
        private const val CAP_MAX = 100_000   // kb/s : plafond « sans limite » d'un lien
        private const val CAP_MIN = 300
        private const val REPLAY_NS = 1_000_000_000L
        private const val VIDEO_PID = 0x100

        private fun header(pkt: ByteArray, type: Int, link: Int, sid: Int) {
            pkt[0] = 'T'.code.toByte()
            pkt[1] = 'B'.code.toByte()
            pkt[2] = type.toByte()
            pkt[3] = link.toByte()
            pkt[4] = (sid ushr 24).toByte()
            pkt[5] = (sid ushr 16).toByte()
            pkt[6] = (sid ushr 8).toByte()
            pkt[7] = sid.toByte()
        }

        /** SRT data datagram carrying no video TS packet (audio, tables, clock): duplicated on every link. */
        fun audioOnly(p: ByteArray): Boolean {
            if (p.size < 16 || (p[0].toInt() and 0x80) != 0) return false
            val ts = p.size - 16
            if (ts % 188 != 0) return false
            var off = 16
            while (off < p.size) {
                if (p[off] != 0x47.toByte()) return false
                val pid = ((p[off + 1].toInt() and 0x1F) shl 8) or (p[off + 2].toInt() and 0xFF)
                if (pid == VIDEO_PID) return false
                off += 188
            }
            return true
        }

        /** SRT data packet re-sent by libsrt (R flag): safest link, never a suspect one. */
        fun isRetransmission(p: ByteArray): Boolean =
            p.size >= 16 && (p[0].toInt() and 0x80) == 0 && (p[4].toInt() and 0x04) != 0
    }
}

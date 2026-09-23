package fr.turboirl.core.cli

import fr.turboirl.core.FlvToTsRelay
import fr.turboirl.core.rtmp.Logger
import fr.turboirl.core.rtmp.RtmpServer
import fr.turboirl.core.ts.TsSink
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Desktop test harness for the relay core, no phone needed:
 *   --port 1935            RTMP listen port
 *   --file out.ts          write the MPEG-TS to a file
 *   --udp 127.0.0.1:9000   send the MPEG-TS over UDP
 *   --congest 10:3         pretend the uplink saturates for 3 s every 10 s (tests audio priority)
 *   --trickle              while video is withheld, still pass keyframes (no gap in the video timeline)
 */
fun main(args: Array<String>) {
    var port = 1935
    var file: String? = null
    var udp: String? = null
    var congest: String? = null
    var trickle = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> port = args[++i].toInt()
            "--file" -> file = args[++i]
            "--udp" -> udp = args[++i]
            "--congest" -> congest = args[++i]
            "--trickle" -> trickle = true
            else -> error("argument inconnu : ${args[i]}")
        }
        i++
    }

    val sinks = ArrayList<TsSink>()
    file?.let { path ->
        val out = FileOutputStream(path)
        sinks.add(TsSink { buf, off, len, _ -> out.write(buf, off, len) })
    }
    udp?.let { target ->
        val socket = DatagramSocket()
        val address = InetSocketAddress(target.substringBeforeLast(':'), target.substringAfterLast(':').toInt())
        sinks.add(TsSink { buf, off, len, _ -> socket.send(DatagramPacket(buf, off, len, address)) })
    }
    require(sinks.isNotEmpty()) { "préciser --file et/ou --udp" }

    val logger = Logger { println(it) }
    val congested: () -> Boolean = congest?.let { spec ->
        val period = spec.substringBefore(':').toLong() * 1000
        val duration = spec.substringAfter(':').toLong() * 1000
        val t0 = System.currentTimeMillis();
        { (System.currentTimeMillis() - t0) % period < duration }
    } ?: { false }
    val relay = FlvToTsRelay({ buf, off, len, a -> sinks.forEach { it.write(buf, off, len, a) } }, logger, congested)
    relay.trickleKeyframes = trickle
    val server = RtmpServer(port, relay, logger)
    server.start()

    var lastTs = 0L
    while (true) {
        Thread.sleep(5000)
        val s = relay.stats
        println("images=${s.videoFrames} clés=${s.keyframes} TS=${(s.tsBytes - lastTs) * 8 / 5000} kb/s")
        lastTs = s.tsBytes
    }
}

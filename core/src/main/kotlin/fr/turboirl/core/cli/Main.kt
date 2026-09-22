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
 */
fun main(args: Array<String>) {
    var port = 1935
    var file: String? = null
    var udp: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> port = args[++i].toInt()
            "--file" -> file = args[++i]
            "--udp" -> udp = args[++i]
            else -> error("argument inconnu : ${args[i]}")
        }
        i++
    }

    val sinks = ArrayList<TsSink>()
    file?.let { path ->
        val out = FileOutputStream(path)
        sinks.add(TsSink { buf, off, len -> out.write(buf, off, len) })
    }
    udp?.let { target ->
        val socket = DatagramSocket()
        val address = InetSocketAddress(target.substringBeforeLast(':'), target.substringAfterLast(':').toInt())
        sinks.add(TsSink { buf, off, len -> socket.send(DatagramPacket(buf, off, len, address)) })
    }
    require(sinks.isNotEmpty()) { "préciser --file et/ou --udp" }

    val logger = Logger { println(it) }
    val relay = FlvToTsRelay({ buf, off, len -> sinks.forEach { it.write(buf, off, len) } }, logger)
    RtmpServer(port, relay, logger).start()

    var lastTs = 0L
    while (true) {
        Thread.sleep(5000)
        val s = relay.stats
        println("images=${s.videoFrames} clés=${s.keyframes} TS=${(s.tsBytes - lastTs) * 8 / 5000} kb/s")
        lastTs = s.tsBytes
    }
}

package fr.turboirl.core.rtmp

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Single-publisher RTMP ingest server. A new connection always replaces the current one:
 * when the camera reconnects after a Wi-Fi drop, the old TCP socket is usually still
 * half-open and must not keep the slot.
 */
class RtmpServer(
    private val port: Int,
    private val listener: RtmpListener,
    private val logger: Logger,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var current: RtmpSession? = null
    private var currentThread: Thread? = null

    /** Bytes received on the active connection, 0 when nobody is connected. */
    val sessionBytesReceived: Long get() = current?.bytesReceived ?: 0

    @Throws(IOException::class)
    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        serverSocket = ss
        acceptThread = Thread({ acceptLoop(ss) }, "rtmp-accept").apply {
            isDaemon = true
            start()
        }
        logger.log("Serveur RTMP en écoute sur le port $port")
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        endCurrentSession()
        acceptThread?.join(2000)
        acceptThread = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val socket = try {
                ss.accept()
            } catch (e: IOException) {
                if (!ss.isClosed) logger.log("RTMP accept : ${e.message}")
                break
            }
            logger.log("RTMP : connexion de ${socket.remoteSocketAddress.toString().removePrefix("/")}")
            endCurrentSession()
            val session = RtmpSession(socket, listener, logger)
            current = session
            currentThread = Thread({
                session.run()
                if (current === session) current = null
            }, "rtmp-session").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun endCurrentSession() {
        current?.close()
        currentThread?.join(2000)
        current = null
        currentThread = null
    }
}

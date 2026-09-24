package fr.turboirl.app

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import fr.turboirl.core.rtmp.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends the telemetry (every 30 s) and the journal (at start, at the end, on request) to the VPS
 * (turboirl-api), so that nothing has to go through Discord any more. Never in the way of the stream:
 * tiny payloads, short timeouts, nothing sent while the SRT buffer is high (weak zone: the 5G is for the
 * live), and what could not be sent is kept and retried at the next tick or the next start.
 */
class Uploader(
    private val context: Context,
    private val baseUrl: String,
    private val token: String,
    private val session: String,
    private val startedAt: String,
    private val version: String,
    private val logger: Logger,
    private val telemetry: Telemetry?,
    private val srtBufferMs: () -> Int,
) {
    private val thread = HandlerThread("uploader").apply { start() }
    private val handler = Handler(thread.looper)
    private var seq = 0
    private val backlog = ArrayList<String>()   // rows not acknowledged yet, oldest first
    private var batch: List<String>? = null     // rows of the batch in flight (resent with the same seq)
    private var journalReason: String? = null   // journal to (re)send
    private var successes = 0
    private var lastFailureLog = 0L
    @Volatile private var stopped = false

    fun start() {
        journalReason = "demarrage"
        handler.post { flushJournal() }
        handler.postDelayed(::tick, TICK_MS)
    }

    /** Sends the journal now (button, while the service runs). */
    fun sendJournal(reason: String) {
        journalReason = reason
        handler.post { flushJournal() }
    }

    /** One-off journal send without a running service (after a crash): posts it, then the thread goes away. */
    fun sendJournalOnce(reason: String) {
        journalReason = reason
        handler.post {
            flushJournal()
            thread.quitSafely()
        }
    }

    /** End of the stream: last telemetry rows and the journal, then the thread goes away. */
    fun finish() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        journalReason = "fin"
        handler.post {
            flushTelemetry(force = true)
            flushJournal()
            thread.quitSafely()
        }
    }

    private var ticks = 0

    private fun tick() {
        if (stopped) return
        ticks++
        flushTelemetry(force = false)
        // the journal also goes up every 2 min so that a session can be followed live from the VPS
        if (journalReason == null && ticks % 4 == 0) journalReason = "auto"
        if (journalReason != null) flushJournal()
        handler.postDelayed(::tick, TICK_MS)
    }

    private fun flushTelemetry(force: Boolean) {
        telemetry?.drainPending()?.let { backlog.addAll(it) }
        while (backlog.size > MAX_BACKLOG) backlog.removeAt(0)
        if (backlog.isEmpty()) return
        if (!force && srtBufferMs() > 3000) return // weak zone: keep the 5G for the live, rows wait
        val rows = batch ?: backlog.take(MAX_ROWS).also { batch = it }
        val body = JSONObject()
            .put("session", session).put("device", deviceTag()).put("version", version).put("startedAt", startedAt)
            .put("seq", seq).put("header", Telemetry.HEADER).put("rows", JSONArray(rows))
        if (post("/api/turboirl/telemetry", body)) {
            repeat(rows.size) { backlog.removeAt(0) }
            batch = null
            seq++
            if (successes++ == 0) logger.log("VPS : télémétrie envoyée (${rows.size} lignes), puis toutes les 30 s")
        }
    }

    private fun flushJournal() {
        val reason = journalReason ?: return
        val text = try {
            val f = AppLog.exportForShare()
            readTail(f, MAX_JOURNAL)
        } catch (e: Exception) {
            logger.log("VPS : journal illisible (${e.message})")
            journalReason = null
            return
        }
        val body = JSONObject()
            .put("session", session).put("device", deviceTag()).put("version", version).put("startedAt", startedAt)
            .put("reason", reason).put("text", text)
        if (post("/api/turboirl/journal", body)) {
            journalReason = null
            logger.log("VPS : journal envoyé ($reason, ${text.length / 1024} Ko)")
        }
    }

    private fun post(path: String, body: JSONObject): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 200) {
                true
            } else {
                failure("$path → HTTP $code")
                false
            }
        } catch (e: Exception) {
            failure("$path → ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun failure(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastFailureLog > 300_000) { // one line per 5 min at most, the retries are silent
            lastFailureLog = now
            logger.log("VPS : envoi impossible ($msg), nouvel essai à chaque cycle")
        }
    }

    private fun deviceTag(): String = android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9]"), "").take(24).ifEmpty { "android" }

    private fun readTail(f: File, max: Int): String {
        val text = f.readText()
        return if (text.length <= max) text else text.substring(text.length - max)
    }

    companion object {
        private const val TICK_MS = 30_000L
        private const val MAX_ROWS = 600        // 10 min of rows per request at most
        private const val MAX_BACKLOG = 7200    // 2 h kept if the VPS is unreachable
        private const val MAX_JOURNAL = 1_000_000

        /** Session id: model + local start time, e.g. 24090RA29G-20260924-183012. */
        fun newSession(startedAtLocal: java.util.Date): String {
            val model = android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9]"), "").take(24).ifEmpty { "android" }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(startedAtLocal)
            return "$model-$stamp"
        }

        fun isoNow(): String =
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.ROOT).format(java.util.Date())

        /** Published versions: `{ stable: { app, pc }, test: { app, pc }, files: [...] }`, or null when unreachable. */
        fun fetchRelease(baseUrl: String, token: String): JSONObject? =
            try {
                http(baseUrl.trimEnd('/') + "/api/turboirl/release", token, null)
            } catch (_: Exception) {
                null
            }

        /** Asks the PC receiver (through the VPS) to start or stop the OBS stream. Result on the calling thread's callback. */
        fun postCommand(baseUrl: String, token: String, action: String, done: (String) -> Unit) {
            Thread {
                val msg = try {
                    val body = JSONObject().put("device", android.os.Build.MODEL).put("action", action).toString()
                    http(baseUrl.trimEnd('/') + "/api/turboirl/command", token, body)?.let { "commande $action envoyée (#${it.optInt("id")})" }
                        ?: "commande refusée par le VPS"
                } catch (e: Exception) {
                    "VPS injoignable (${e.message})"
                }
                done(msg)
            }.start()
        }

        /** OBS state as published by the PC receiver (null if the VPS is unreachable). */
        fun fetchObsStatus(baseUrl: String, token: String): JSONObject? =
            try {
                http(baseUrl.trimEnd('/') + "/api/turboirl/obs", token, null)
            } catch (_: Exception) {
                null
            }

        private fun http(url: String, token: String, body: String?): JSONObject? {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = if (body != null) "POST" else "GET"
                connectTimeout = 5000
                readTimeout = 8000
                setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            try {
                if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode != 200) return null
                return JSONObject(conn.inputStream.bufferedReader().readText())
            } finally {
                conn.disconnect()
            }
        }
    }
}

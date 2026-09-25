package fr.turboirl.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Twitch sign-in for the built-in chat, through the OAuth "device code" flow: the app shows a code, the user signs in
 * on twitch.tv/activate in a real browser (the login page refuses WebViews), the app polls for the tokens. Public
 * client: no secret, refresh tokens work with the client id alone (one use each, 30 days of inactivity max).
 */
object TwitchAuth {
    /** Twitch application registered at dev.twitch.tv/console/apps (client type « Public »). Empty = sign-in off. */
    const val CLIENT_ID = "k6q6nj31qrhg82oy08hp6kd9e49wvl"
    const val SCOPES = "chat:read chat:edit"

    class DeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String, val intervalS: Int, val expiresInS: Int)
    class Tokens(val access: String, val refresh: String)

    private fun post(url: String, form: Map<String, String>): Pair<Int, JSONObject> {
        val body = form.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        c.disconnect()
        return code to (try { JSONObject(text) } catch (_: Exception) { JSONObject() })
    }

    fun startDevice(): DeviceCode {
        val (code, j) = post("https://id.twitch.tv/oauth2/device", mapOf("client_id" to CLIENT_ID, "scopes" to SCOPES))
        if (code != 200) throw IOException("Twitch : ${j.optString("message", "erreur $code")}")
        return DeviceCode(j.getString("device_code"), j.getString("user_code"), j.getString("verification_uri"),
            j.optInt("interval", 5), j.optInt("expires_in", 1800))
    }

    /** Tokens once the user has approved; null while pending. Throws when the code expired or was refused. */
    fun pollDevice(dc: DeviceCode): Tokens? {
        val (code, j) = post("https://id.twitch.tv/oauth2/token", mapOf("client_id" to CLIENT_ID, "device_code" to dc.deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
        if (code == 200) return Tokens(j.getString("access_token"), j.optString("refresh_token"))
        val msg = j.optString("message")
        if (msg.contains("authorization_pending")) return null
        throw IOException("Twitch : $msg")
    }

    fun refresh(refreshToken: String): Tokens? {
        val (code, j) = post("https://id.twitch.tv/oauth2/token", mapOf("client_id" to CLIENT_ID, "grant_type" to "refresh_token",
            "refresh_token" to refreshToken))
        return if (code == 200) Tokens(j.getString("access_token"), j.optString("refresh_token", refreshToken)) else null
    }

    /** Login name of the token's user, or null when the token is no longer valid. */
    fun validate(access: String): String? {
        val c = URL("https://id.twitch.tv/oauth2/validate").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Authorization", "OAuth $access")
        return try {
            if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText()).optString("login").ifEmpty { null } else null
        } finally {
            c.disconnect()
        }
    }
}

/**
 * Twitch chat over IRC (WebSocket): anonymous when no account (read only), signed in with the OAuth token (read and
 * write). Reconnects on its own; messages are delivered on the caller's listener from a network thread.
 */
class TwitchIrc(
    private val channel: String,
    private val login: String?,       // null = anonymous
    private val token: String?,
    private val listener: Listener,
) {
    class Message(val name: String, val color: Int, val text: String, val badges: String, val own: Boolean = false)

    interface Listener {
        fun onMessage(m: Message)
        fun onState(text: String)
        /** Twitch refused the token: the caller refreshes it and reconnects. */
        fun onAuthFailed()
    }

    private val http = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var closed = false
    @Volatile var joined = false
        private set
    private var attempts = 0

    val authenticated get() = login != null && token != null

    fun connect() {
        closed = false
        open()
    }

    private fun open() {
        if (closed) return
        val req = Request.Builder().url("wss://irc-ws.chat.twitch.tv:443").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempts = 0
                webSocket.send("CAP REQ :twitch.tv/tags twitch.tv/commands")
                if (authenticated) {
                    webSocket.send("PASS oauth:$token")
                    webSocket.send("NICK $login")
                } else {
                    webSocket.send("NICK justinfan${(10000..99999).random()}")
                }
                webSocket.send("JOIN #$channel")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                for (line in text.split("\r\n")) if (line.isNotEmpty()) handle(webSocket, line)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                joined = false
                listener.onState("tchat déconnecté (${t.message ?: t.javaClass.simpleName})")
                retry()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                joined = false
                if (!closed) retry()
            }
        })
    }

    private fun retry() {
        if (closed) return
        attempts++
        Thread {
            try { Thread.sleep(minOf(30_000L, 1000L shl minOf(attempts, 5))) } catch (_: InterruptedException) { return@Thread }
            open()
        }.start()
    }

    private fun handle(webSocket: WebSocket, raw: String) {
        if (raw.startsWith("PING")) {
            webSocket.send("PONG :tmi.twitch.tv")
            return
        }
        // [@tags] [:prefix] COMMAND [params] [:trailing]
        var rest = raw
        val tags = HashMap<String, String>()
        if (rest.startsWith("@")) {
            val sp = rest.indexOf(' ')
            for (kv in rest.substring(1, sp).split(';')) {
                val eq = kv.indexOf('=')
                if (eq > 0) tags[kv.substring(0, eq)] = kv.substring(eq + 1)
            }
            rest = rest.substring(sp + 1)
        }
        var prefix = ""
        if (rest.startsWith(":")) {
            val sp = rest.indexOf(' ')
            prefix = rest.substring(1, sp)
            rest = rest.substring(sp + 1)
        }
        val colon = rest.indexOf(" :")
        val trailing = if (colon >= 0) rest.substring(colon + 2) else ""
        val parts = (if (colon >= 0) rest.substring(0, colon) else rest).split(' ')
        when (parts[0]) {
            "PRIVMSG" -> {
                val user = prefix.substringBefore('!')
                val name = tags["display-name"].orEmpty().ifEmpty { user }
                val color = tags["color"]?.takeIf { it.startsWith("#") && it.length == 7 }
                    ?.let { (0xFF000000L or it.substring(1).toLong(16)).toInt() } ?: 0xFFBBBBBB.toInt()
                listener.onMessage(Message(name, color, unescapeIrc(trailing), tags["badges"].orEmpty()))
            }
            "JOIN" -> {
                joined = true
                listener.onState(if (authenticated) "connecté : $login" else "lecture seule")
            }
            "NOTICE" -> {
                if (trailing.contains("authentication failed", ignoreCase = true)) {
                    closed = true
                    webSocket.close(1000, "auth")
                    listener.onAuthFailed()
                } else {
                    listener.onState(trailing)
                }
            }
            "RECONNECT" -> webSocket.close(1000, "reconnect")
            "USERNOTICE" -> {
                val sys = tags["system-msg"]?.let { unescapeIrc(it) }.orEmpty()
                if (sys.isNotEmpty()) listener.onMessage(Message("Twitch", 0xFF9146FF.toInt(), sys + (if (trailing.isNotEmpty()) " : $trailing" else ""), ""))
            }
        }
    }

    /** Sends to the channel; Twitch does not echo one's own messages, so the caller shows it locally. */
    fun send(text: String): Boolean {
        val s = ws ?: return false
        if (!authenticated || !joined) return false
        return s.send("PRIVMSG #$channel :$text")
    }

    fun close() {
        closed = true
        joined = false
        ws?.close(1000, "bye")
        ws = null
    }

    companion object {
        /** IRCv3 tag values escape spaces, semicolons and backslashes. */
        fun unescapeIrc(s: String): String {
            if (!s.contains('\\')) return s
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        's' -> out.append(' ')
                        ':' -> out.append(';')
                        '\\' -> out.append('\\')
                        'r' -> out.append('\r')
                        'n' -> out.append('\n')
                        else -> out.append(s[i + 1])
                    }
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
    }
}

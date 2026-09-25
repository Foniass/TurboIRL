package fr.turboirl.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var srtHost: EditText
    private lateinit var srtPort: EditText
    private lateinit var srtLatency: EditText
    private lateinit var toggle: Button
    private lateinit var battery: Button
    private lateinit var share: Button
    private lateinit var tethering: Button
    private lateinit var sendVps: Button
    private lateinit var update: Button
    private lateinit var versions: TextView
    private lateinit var testChannel: CheckBox
    private lateinit var linksStatus: TextView
    private lateinit var tabSetup: TextView
    private lateinit var tabLive: TextView
    private lateinit var setupTab: View
    private lateinit var liveTab: View
    private lateinit var liveStatus: TextView
    private lateinit var blur: Button
    private lateinit var orderPrice: EditText
    private lateinit var orderGo: Button
    private lateinit var orderStatus: TextView
    private lateinit var chat: android.webkit.WebView
    private lateinit var chatHint: TextView
    private lateinit var twitchChannel: EditText
    private var chatChannel = ""
    @Volatile private var orderCurrent: org.json.JSONObject? = null
    @Volatile private var orderBusy = false
    private lateinit var cellPlanGb: EditText
    private lateinit var wifiPlanGb: EditText
    private lateinit var testSource: CheckBox
    private lateinit var impairCell: EditText
    private lateinit var impairWifi: EditText
    /** Version the phone should run (from the VPS, on the chosen channel); null = unknown or up to date. */
    @Volatile private var targetVersion: String? = null
    @Volatile private var releaseText = ""
    @Volatile private var downloading = false
    private lateinit var obsStart: Button
    private lateinit var obsStop: Button
    private lateinit var obsStatus: TextView
    @Volatile private var obsPolling = false
    private lateinit var vpsToken: EditText
    private lateinit var outMaxKbps: EditText
    private lateinit var outMaxHeight: EditText
    private lateinit var audioKbps: EditText
    private lateinit var goproEnabled: CheckBox
    private lateinit var goproSsid: EditText
    private lateinit var goproPassword: EditText
    private lateinit var goproResolution: EditText
    private lateinit var goproMaxKbps: EditText
    private lateinit var goproStatus: TextView
    private lateinit var rtmpUrl: TextView
    private lateinit var status: TextView
    private lateinit var rtmpBlock: View
    private lateinit var showSecrets: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        srtHost = findViewById(R.id.srtHost)
        srtPort = findViewById(R.id.srtPort)
        srtLatency = findViewById(R.id.srtLatency)
        toggle = findViewById(R.id.toggle)
        battery = findViewById(R.id.battery)
        share = findViewById(R.id.share)
        tethering = findViewById(R.id.tethering)
        sendVps = findViewById(R.id.sendVps)
        update = findViewById(R.id.update)
        versions = findViewById(R.id.versions)
        testChannel = findViewById(R.id.testChannel)
        linksStatus = findViewById(R.id.linksStatus)
        tabSetup = findViewById(R.id.tabSetup)
        tabLive = findViewById(R.id.tabLive)
        setupTab = findViewById(R.id.setupTab)
        liveTab = findViewById(R.id.liveTab)
        liveStatus = findViewById(R.id.liveStatus)
        blur = findViewById(R.id.blur)
        orderPrice = findViewById(R.id.orderPrice)
        orderGo = findViewById(R.id.orderGo)
        orderStatus = findViewById(R.id.orderStatus)
        chat = findViewById(R.id.chat)
        chatHint = findViewById(R.id.chatHint)
        twitchChannel = findViewById(R.id.twitchChannel)
        cellPlanGb = findViewById(R.id.cellPlanGb)
        wifiPlanGb = findViewById(R.id.wifiPlanGb)
        testSource = findViewById(R.id.testSource)
        impairCell = findViewById(R.id.impairCell)
        impairWifi = findViewById(R.id.impairWifi)
        obsStart = findViewById(R.id.obsStart)
        obsStop = findViewById(R.id.obsStop)
        obsStatus = findViewById(R.id.obsStatus)
        vpsToken = findViewById(R.id.vpsToken)
        outMaxKbps = findViewById(R.id.outMaxKbps)
        outMaxHeight = findViewById(R.id.outMaxHeight)
        audioKbps = findViewById(R.id.audioKbps)
        goproEnabled = findViewById(R.id.goproEnabled)
        goproSsid = findViewById(R.id.goproSsid)
        goproPassword = findViewById(R.id.goproPassword)
        goproResolution = findViewById(R.id.goproResolution)
        goproMaxKbps = findViewById(R.id.goproMaxKbps)
        goproStatus = findViewById(R.id.goproStatus)
        rtmpUrl = findViewById(R.id.rtmpUrl)
        status = findViewById(R.id.status)
        rtmpBlock = findViewById(R.id.rtmpBlock)
        showSecrets = findViewById(R.id.showSecrets)

        val config = Config.load(this)
        srtHost.setText(config.srtHost)
        srtPort.setText(config.srtPort.toString())
        srtLatency.setText(config.srtLatencyMs.toString())
        outMaxKbps.setText(config.outMaxKbps.toString())
        outMaxHeight.setText(config.outMaxHeight.toString())
        audioKbps.setText(config.audioKbps.toString())
        goproEnabled.isChecked = config.goproEnabled
        goproSsid.setText(config.goproSsid)
        goproPassword.setText(config.goproPassword)
        goproResolution.setText(config.goproResolution.toString())
        goproMaxKbps.setText(config.goproMaxKbps.toString())
        vpsToken.setText(config.vpsToken)

        setupSections()
        showSecrets.setOnCheckedChangeListener { _, checked -> applySecretMask(!checked) }
        applySecretMask(true)
        goproEnabled.setOnCheckedChangeListener { _, checked ->
            if (checked) requestBluetoothPermissions()
            rtmpBlock.visibility = if (checked) View.GONE else View.VISIBLE
        }
        rtmpBlock.visibility = if (config.goproEnabled) View.GONE else View.VISIBLE

        toggle.setOnClickListener { if (RelayService.instance != null) RelayService.stop(this) else startRelay() }
        battery.setOnClickListener { requestBatteryExemption() }
        share.setOnClickListener { shareLog() }
        tethering.setOnClickListener { openTetheringSettings() }
        sendVps.setOnClickListener { sendJournalToVps() }
        update.setOnClickListener { targetVersion?.let { installUpdate(it) } }
        testChannel.isChecked = config.testChannel
        testChannel.setOnCheckedChangeListener { _, checked ->
            Config.load(this).copy(testChannel = checked).save(this)
            showTestSection(checked)
            Thread { checkRelease() }.start()
        }
        twitchChannel.setText(config.twitchChannel)
        // saved as typed: the chat needs it without pressing Démarrer (the other fields are saved at start)
        twitchChannel.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val v = s.toString().trim().trimStart('@')
                val c = Config.load(this@MainActivity)
                if (c.twitchChannel != v) c.copy(twitchChannel = v).save(this@MainActivity)
            }
        })
        setupLive()
        cellPlanGb.setText(config.cellPlanGb.toString())
        wifiPlanGb.setText(config.wifiPlanGb.toString())
        testSource.isChecked = config.testSource
        impairCell.setText(config.impairCell)
        impairWifi.setText(config.impairWifi)
        showTestSection(config.testChannel)
        obsStart.setOnClickListener { confirmObs("start") }
        obsStop.setOnClickListener { confirmObs("stop") }

        val wanted = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        wanted.add(Manifest.permission.ACCESS_FINE_LOCATION)
        wanted.add(Manifest.permission.READ_PHONE_STATE)
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    // ---------------------------------------------------------------- sections dépliables (état mémorisé)

    private val sections = listOf(
        Triple(R.id.secStateHeader, R.id.secStateBody, "state"),
        Triple(R.id.secDestHeader, R.id.secDestBody, "dest"),
        Triple(R.id.secVideoHeader, R.id.secVideoBody, "video"),
        Triple(R.id.secGoproHeader, R.id.secGoproBody, "gopro"),
        Triple(R.id.secVpsHeader, R.id.secVpsBody, "vps"),
        Triple(R.id.secConnHeader, R.id.secConnBody, "conn"),
        Triple(R.id.secTestHeader, R.id.secTestBody, "test"),
    )

    /** The bench section only exists on the test channel (never shown to the friend). */
    private fun showTestSection(shown: Boolean) {
        findViewById<View>(R.id.secTestHeader).visibility = if (shown) View.VISIBLE else View.GONE
        val prefs = getSharedPreferences("ui", Context.MODE_PRIVATE)
        findViewById<View>(R.id.secTestBody).visibility = if (shown && prefs.getBoolean("test", true)) View.VISIBLE else View.GONE
    }

    private fun setupSections() {
        val prefs = getSharedPreferences("ui", Context.MODE_PRIVATE)
        for ((headerId, bodyId, key) in sections) {
            val header = findViewById<TextView>(headerId)
            val body = findViewById<View>(bodyId)
            val title = header.text.toString()
            fun apply(open: Boolean) {
                body.visibility = if (open) View.VISIBLE else View.GONE
                header.text = (if (open) "▾  " else "▸  ") + title
            }
            apply(prefs.getBoolean(key, true))
            header.setOnClickListener {
                val open = body.visibility != View.VISIBLE
                prefs.edit().putBoolean(key, open).apply()
                apply(open)
            }
        }
    }

    /** The screen may appear on stream: address, passwords and token are shown as dots unless asked. */
    private fun applySecretMask(masked: Boolean) {
        val type = if (masked) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        for (f in listOf(srtHost, goproPassword, vpsToken)) {
            f.inputType = type
            f.setSelection(f.text.length)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        obsPolling = true
        Thread { pollObs() }.start()
        Thread { checkRelease() }.start()
    }

    override fun onPause() {
        obsPolling = false
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    // ---------------------------------------------------------------- onglet LIVE : onglets, flou, commandes, tchat

    private fun setupLive() {
        tabSetup.setOnClickListener { showTab(false) }
        tabLive.setOnClickListener { showTab(true) }
        showTab(getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("liveTab", false))
        blur.setOnClickListener {
            val service = RelayService.instance
            if (service == null) {
                Toast.makeText(this, "Démarre le relais d'abord (onglet SETUP)", Toast.LENGTH_SHORT).show()
            } else {
                service.setBlur(!service.blurred)
                applyBlurButton()
            }
        }
        orderGo.setOnClickListener { orderAction() }
        val ws = chat.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.mediaPlaybackRequiresUserGesture = true
        // the Twitch popout chat (and its login page) exist on the desktop site only; the login page also rejects
        // browsers it finds too old, so the Chrome version is the installed WebView's own (kept current by Play)
        val chrome = Regex("Chrome/(\\d+)").find(ws.userAgentString)?.groupValues?.get(1) ?: "140"
        ws.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome.0.0.0 Safari/537.36"
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(chat, true)
        chat.webViewClient = android.webkit.WebViewClient()
        chat.webChromeClient = android.webkit.WebChromeClient()
        loadChat()
    }

    private fun showTab(live: Boolean) {
        setupTab.visibility = if (live) View.GONE else View.VISIBLE
        liveTab.visibility = if (live) View.VISIBLE else View.GONE
        tabLive.setTextColor(if (live) 0xFFFF6D00.toInt() else 0xFF888888.toInt())
        tabSetup.setTextColor(if (live) 0xFF888888.toInt() else 0xFFFF6D00.toInt())
        tabLive.setBackgroundColor(if (live) 0x33FF6D00 else 0)
        tabSetup.setBackgroundColor(if (live) 0 else 0x33FF6D00)
        getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("liveTab", live).apply()
        if (live) {
            loadChat()
            applyBlurButton()
        }
    }

    /** Twitch popout chat of the configured channel; reloaded only when the channel changes. */
    private fun loadChat() {
        val channel = Config.load(this).twitchChannel.trim().trimStart('@').lowercase()
        chatHint.visibility = if (channel.isEmpty()) View.VISIBLE else View.GONE
        if (channel.isEmpty() || channel == chatChannel) return
        chatChannel = channel
        chat.loadUrl("https://www.twitch.tv/popout/$channel/chat?popout=")
    }

    private fun applyBlurButton() {
        val on = RelayService.instance?.blurred ?: false
        blur.text = if (on) "DÉFLOUTER" else "FLOUTER"
        blur.setBackgroundColor(if (on) 0xFFB71C1C.toInt() else 0xFF424242.toInt())
    }

    /** GO with a price = start an order ; then the same button ends it. */
    private fun orderAction() {
        if (orderBusy) return
        val config = Config.load(this)
        val token = vpsToken.text.toString().trim().ifEmpty { config.vpsToken }
        if (token.isEmpty()) {
            Toast.makeText(this, "Jeton VPS manquant (SETUP)", Toast.LENGTH_SHORT).show()
            return
        }
        val body = org.json.JSONObject()
        if (orderCurrent != null) {
            body.put("action", "finish")
        } else {
            val price = orderPrice.text.toString().trim().replace(',', '.').toDoubleOrNull()
            if (price == null || price < 0) {
                Toast.makeText(this, "Entre le prix de la commande", Toast.LENGTH_SHORT).show()
                return
            }
            body.put("action", "start").put("price", price)
        }
        // the PC delays the OBS texts by the video delay; the phone → VPS SRT buffer is its biggest part
        body.put("latencyMs", config.srtLatencyMs)
        orderBusy = true
        orderGo.isEnabled = false
        Uploader.postOrders(config.vpsUrl, token, body) { r ->
            handler.post {
                orderBusy = false
                orderGo.isEnabled = true
                if (r == null) Toast.makeText(this, "VPS injoignable, réessaie", Toast.LENGTH_SHORT).show()
                else {
                    if (body.optString("action") == "start") orderPrice.setText("")
                    applyOrders(r)
                }
            }
        }
    }

    private fun euros(v: Double): String = String.format(java.util.Locale.FRANCE, "%.2f €", v).replace(",00 €", " €")

    private fun applyOrders(o: org.json.JSONObject?) {
        if (o == null) return
        val cur = o.optJSONObject("current")
        orderCurrent = cur
        val goal = if (o.optBoolean("goalEnabled") && !o.isNull("goal") && o.optDouble("goal") > 0) " / " + euros(o.optDouble("goal")) else ""
        orderStatus.text = "${o.optInt("count")} commande(s) finie(s) · total ${euros(o.optDouble("total"))}$goal" +
            (cur?.let { "\nCommande #${it.optInt("id")} en cours : ${euros(it.optDouble("price"))}" } ?: "")
        if (cur != null) {
            orderPrice.visibility = View.GONE
            orderGo.text = "COMMANDE FINIE"
            orderGo.setBackgroundColor(0xFF2E7D32.toInt())
        } else {
            orderPrice.visibility = View.VISIBLE
            orderGo.text = "GO"
            orderGo.setBackgroundColor(0xFF424242.toInt())
        }
    }

    /** Every 2 s while the screen is visible: OBS state on the PC, as published by the receiver through the VPS. */
    private fun pollObs() {
        while (obsPolling) {
            val token = vpsToken.text.toString().trim()
            val st = if (token.isEmpty()) null else Uploader.fetchObsStatus(Config.load(this).vpsUrl, token)
            val text = if (token.isEmpty()) {
                "OBS PC : renseigne le jeton VPS pour piloter le stream"
            } else {
                when {
                    st == null -> "OBS PC : VPS injoignable"
                    !st.optBoolean("receiverOk") -> "OBS PC : récepteur PC absent (PC éteint ou récepteur non lancé)"
                    !st.optBoolean("obsOpen") -> "OBS PC : OBS fermé sur le PC"
                    st.optBoolean("streaming") -> "OBS PC : EN DIRECT depuis ${st.optString("timecode")} · ${st.optInt("kbps")} kb/s"
                    else -> "OBS PC : prêt, pas en direct"
                } + (st?.optJSONObject("lastCommand")?.let { c ->
                    if (c.optBoolean("done")) "\n  dernière commande ${c.optString("action")} : ${c.optString("result")}"
                    else "\n  commande ${c.optString("action")} en attente du PC…"
                } ?: "") + (st?.optJSONObject("relay")?.let { r ->
                    "\nRelais VPS : " + if (r.optBoolean("ready")) "flux du téléphone reçu, ${r.optInt("readers")} lecteur(s)" else "aucun flux"
                } ?: "") + (st?.optString("version")?.takeIf { it.isNotEmpty() }?.let { v ->
                    "\nLogiciel PC : version $v sur ${st.optString("device")}"
                } ?: "")
            }
            val live = when {
                token.isEmpty() -> "Twitch : jeton VPS manquant"
                st == null -> "Twitch : VPS injoignable"
                !st.optBoolean("receiverOk") -> "Twitch : PC absent"
                !st.optBoolean("obsOpen") -> "Twitch : OBS fermé"
                st.optBoolean("streaming") -> "Twitch : EN DIRECT depuis ${st.optString("timecode")} · ${st.optInt("kbps")} kb/s"
                else -> "Twitch : stream arrêté (Lancer OBS dans SETUP)"
            }
            val orders = if (token.isEmpty()) null else Uploader.fetchOrders(Config.load(this).vpsUrl, token)
            handler.post {
                obsStatus.text = text
                liveStatus.text = live
                applyOrders(orders)
            }
            try {
                Thread.sleep(2000)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun confirmObs(action: String) {
        val token = vpsToken.text.toString().trim()
        if (token.isEmpty()) {
            Toast.makeText(this, "Jeton VPS manquant", Toast.LENGTH_LONG).show()
            return
        }
        val label = if (action == "start") "Lancer le stream OBS sur le PC ?" else "Arrêter le stream OBS sur le PC ?"
        android.app.AlertDialog.Builder(this)
            .setMessage(label)
            .setPositiveButton("Oui") { _, _ ->
                val url = Config.load(this).copy(vpsToken = token).also { it.save(this) }.vpsUrl
                Uploader.postCommand(url, token, action) { msg -> handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } }
            }
            .setNegativeButton("Non", null)
            .show()
    }

    // ---------------------------------------------------------------- versions (canal stable / test sur le VPS)

    private fun installedVersion(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }

    /** Asks the VPS which version this phone should run; the screen adapts on the UI thread. */
    private fun checkRelease() {
        val config = Config.load(this)
        val token = vpsToken.text.toString().trim().ifEmpty { config.vpsToken }
        val installed = installedVersion()
        if (token.isEmpty()) {
            releaseText = "Appli $installed"
            targetVersion = null
        } else {
            val r = Uploader.fetchRelease(config.vpsUrl, token)
            if (r == null) {
                // Unreachable: nothing can be checked, so nothing is blocked (noted in the journal)
                releaseText = "Appli $installed · versions du VPS inconnues (injoignable)"
                if (targetVersion == null) AppLog.log("Versions : VPS injoignable, impossible de vérifier la version stable")
                targetVersion = null
            } else {
                val stable = r.optJSONObject("stable")?.optString("app").orEmpty()
                val test = r.optJSONObject("test")?.optString("app").orEmpty()
                val wanted = if (config.testChannel) test else stable
                releaseText = "Appli $installed · stable ${stable.ifEmpty { "-" }} · test ${test.ifEmpty { "-" }}" +
                    (if (config.testChannel) " (canal test)" else "")
                val newTarget = if (wanted.isNotEmpty() && wanted != installed) wanted else null
                if (newTarget != null && newTarget != targetVersion) AppLog.log("Mise à jour disponible : $newTarget (installée $installed)")
                targetVersion = newTarget
            }
        }
        handler.post { refresh() }
    }

    /** Downloads the APK from the VPS (same token) and hands it to the Android installer: two taps for the user. */
    private fun installUpdate(target: String) {
        if (downloading) return
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Autorise TurboIRL à installer des applis, puis réappuie sur Mettre à jour", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val config = Config.load(this)
        val token = vpsToken.text.toString().trim().ifEmpty { config.vpsToken }
        downloading = true
        update.isEnabled = false
        Thread {
            var error: String? = null
            val dir = java.io.File(filesDir, "updates").apply { mkdirs() }
            val file = java.io.File(dir, "TurboIRL-$target.apk")
            try {
                val conn = (java.net.URL(config.vpsUrl.trimEnd('/') + "/api/turboirl/releases/TurboIRL-$target.apk").openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 60000
                    setRequestProperty("Authorization", "Bearer $token")
                }
                try {
                    if (conn.responseCode != 200) throw java.io.IOException("réponse ${conn.responseCode} du VPS")
                    val total = conn.contentLengthLong
                    var done = 0L
                    conn.inputStream.use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) handler.post { update.text = "Téléchargement ${done * 100 / total} %" }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                AppLog.log("Mise à jour $target téléchargée (${file.length() / 1024} Ko), installation demandée")
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                file.delete()
            }
            handler.post {
                downloading = false
                update.isEnabled = true
                if (error != null) {
                    update.text = "Mettre à jour vers $target"
                    Toast.makeText(this, "Téléchargement impossible : $error", Toast.LENGTH_LONG).show()
                } else {
                    update.text = "Installer $target"
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                    startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }.start()
    }

    private fun startRelay() {
        val host = srtHost.text.toString().trim().split(Regex("""\s+""")).first()
        if (host != srtHost.text.toString()) srtHost.setText(host)
        val port = srtPort.text.toString().toIntOrNull()
        val latency = srtLatency.text.toString().toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535 || latency == null || latency !in 120..15000) {
            Toast.makeText(this, "Adresse, port (1-65535) et latence (120-15000 ms) requis", Toast.LENGTH_LONG).show()
            return
        }
        val outMax = outMaxKbps.text.toString().toIntOrNull()
        val outH = outMaxHeight.text.toString().toIntOrNull()
        val aKbps = audioKbps.text.toString().toIntOrNull()
        if (aKbps == null || aKbps !in 32..160) {
            Toast.makeText(this, "Débit du son entre 32 et 160 kb/s", Toast.LENGTH_LONG).show()
            return
        }
        if (outMax == null || outH == null || outMax !in 500..8000 || outH !in setOf(480, 720, 1080)) {
            Toast.makeText(this, "Débit max entre 500 et 8000 kb/s, résolution max 480, 720 ou 1080", Toast.LENGTH_LONG).show()
            return
        }
        val goproOn = goproEnabled.isChecked
        val resolution = goproResolution.text.toString().toIntOrNull()
        val maxKbps = goproMaxKbps.text.toString().toIntOrNull()
        if (goproOn) {
            if (goproSsid.text.isBlank() || goproPassword.text.length < 8) {
                Toast.makeText(this, "Nom et mot de passe du hotspot du téléphone requis pour piloter la GoPro", Toast.LENGTH_LONG).show()
                return
            }
            if (resolution !in setOf(480, 720, 1080) || maxKbps == null || maxKbps !in 800..10000) {
                Toast.makeText(this, "Résolution 480/720/1080 et débit max entre 800 et 10000 kb/s", Toast.LENGTH_LONG).show()
                return
            }
            if (!hasBluetoothPermissions()) {
                requestBluetoothPermissions()
                Toast.makeText(this, "Autorise le Bluetooth puis réappuie sur Démarrer", Toast.LENGTH_LONG).show()
                return
            }
        }
        Config.load(this).copy(
            srtHost = host, srtPort = port, srtLatencyMs = latency,
            outMaxKbps = outMax,
            outMaxHeight = outH,
            audioKbps = aKbps,
            goproEnabled = goproOn,
            goproSsid = goproSsid.text.toString().trim(),
            goproPassword = goproPassword.text.toString(),
            // Only validated when the camera is driven by the app: keep a sane value otherwise
            goproResolution = resolution ?: 720,
            goproMaxKbps = maxKbps ?: 4000,
            vpsToken = vpsToken.text.toString().trim(),
            twitchChannel = twitchChannel.text.toString().trim().trimStart('@'),
            testChannel = testChannel.isChecked,
            cellPlanGb = cellPlanGb.text.toString().toIntOrNull() ?: 0,
            wifiPlanGb = wifiPlanGb.text.toString().toIntOrNull() ?: 0,
            testSource = testSource.isChecked,
            impairCell = impairCell.text.toString().trim(),
            impairWifi = impairWifi.text.toString().trim(),
        ).save(this)
        RelayService.start(this)
    }

    private fun refresh() {
        val service = RelayService.instance
        val running = service != null
        toggle.text = if (running) "Arrêter" else "Démarrer"
        // Not on the wanted version: Démarrer is replaced by the update button, unless a stream is already running
        // (never interrupt it; the update waits below the buttons)
        val target = targetVersion
        update.visibility = if (target != null) View.VISIBLE else View.GONE
        toggle.visibility = if (target != null && !running) View.GONE else View.VISIBLE
        if (target != null && !downloading) update.text = "Mettre à jour vers $target"
        versions.text = releaseText
        for (field in listOf(srtHost, srtPort, srtLatency, goproSsid, goproPassword, goproResolution, goproMaxKbps, outMaxKbps, outMaxHeight, audioKbps, vpsToken, twitchChannel)) {
            field.isEnabled = !running
        }
        goproEnabled.isEnabled = !running
        val links = service?.snapshot?.links.orEmpty()
        linksStatus.text = if (links.isEmpty()) "" else "Liens : " + links.joinToString(" · ") {
            "${it.name} ${it.state}" + (if (it.rttMs > 0) " ${it.rttMs} ms" else "") + " ${it.kbps} kb/s, ${it.sharePct} % (visé ${it.targetPct} %)" + (if (it.outage) " COUPURE SIMULÉE" else "")
        }
        val gp = service?.snapshot?.gopro
        goproStatus.text = when {
            gp != null -> "GoPro ${gp.cameraName} : ${gp.state.label}" +
                (if (gp.detail.isNotEmpty()) "\n  ${gp.detail}" else "") +
                (if (gp.cameraBitrateKbps > 0) "\n  débit caméra ${gp.cameraBitrateKbps} kb/s" else "")
            running -> "Pilotage désactivé : la GoPro se règle à la main (URL ci-dessous)"
            else -> ""
        }

        val rtmpPort = Config.load(this).rtmpPort
        val addresses = NetUtil.localAddresses()
        // Hotspot off while the relay runs: one tap to the tethering settings
        tethering.visibility = if (running && addresses.none { NetUtil.isHotspot(it.iface) }) View.VISIBLE else View.GONE
        rtmpUrl.text = if (addresses.isEmpty()) {
            "Aucune adresse locale : active le partage de connexion."
        } else {
            addresses.joinToString("\n") { "rtmp://${it.ip}:$rtmpPort/live/gopro   (${it.iface})" }
        }

        status.text = when {
            service != null -> describe(service.snapshot)
            RelayService.lastError != null -> RelayService.lastError
            else -> "Arrêté"
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        battery.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE

        handler.postDelayed(::refresh, 1000)
    }

    private fun describe(s: RelayService.Snapshot?): String {
        if (s == null) return "Démarrage…"
        val camera = if (s.cameraConnected) {
            "GoPro  ✓ connectée  ${s.videoInfo}\n       reçu ${s.inKbps} kb/s"
        } else {
            "GoPro  ✗ en attente de la caméra"
        }
        val srt = if (s.srt.connected) {
            "SRT    ✓ connecté   envoyé ${s.outKbps} kb/s\n" +
                "       RTT ${"%.0f".format(s.srt.rttMs)} ms · tampon ${s.srt.sendBufferMs} ms\n" +
                "       retransmis ${s.srt.retransmitted} · perdus ${s.srt.dropped} · saturations ${s.srt.queueOverflows}"
        } else {
            "SRT    ✗ PC injoignable, nouvel essai en cours"
        }
        val enc = s.transcoder?.let { t ->
            "\n       encodeur ${s.encoderTargetKbps} kb/s cible · ${s.encoderOutKbps} kb/s réel · ${t.stats.width}x${t.stats.height}" +
                " · ${30 / t.stats.frameDivider.coerceAtLeast(1)} i/s" + " · images perdues ${t.stats.framesDropped}" +
                (if (!t.active) "\n       ⚠ réencodage abandonné, vidéo caméra directe" else "")
        } ?: ""
        val pauses = if (s.videoSuspensions > 0) " · vidéo en pause ${s.videoSuspensions}× (${s.videoSuspendedMs / 1000} s)" else ""
        return "$camera\n$srt$enc\n       connexions : caméra ${s.cameraSessions} · SRT ${s.srt.connections}$pauses"
    }

    /** Journal to the VPS now: through the running service, or on its own with the last session after a crash. */
    private fun sendJournalToVps() {
        val token = vpsToken.text.toString().trim()
        if (token.isEmpty()) {
            Toast.makeText(this, "Jeton VPS manquant", Toast.LENGTH_LONG).show()
            return
        }
        val config = Config.load(this).copy(vpsToken = token).also { it.save(this) }
        val service = RelayService.instance
        if (service?.uploader != null) {
            service.uploader?.sendJournal("manuel")
        } else {
            val session = config.lastSession.ifEmpty { Uploader.newSession(java.util.Date()) }
            val startedAt = config.lastStartedAt.ifEmpty { Uploader.isoNow() }
            val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
            Uploader(this, config.vpsUrl, token, session, startedAt, version, fr.turboirl.core.rtmp.Logger { AppLog.log(it) }, null) { 0 }.sendJournalOnce("manuel")
        }
        Toast.makeText(this, "Journal en cours d'envoi (voir le journal)", Toast.LENGTH_SHORT).show()
    }

    private fun openTetheringSettings() {
        try {
            startActivity(Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Ouvre les réglages du partage de connexion à la main", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun shareLog() {
        val uris = ArrayList<Uri>()
        uris.add(FileProvider.getUriForFile(this, "$packageName.files", AppLog.exportForShare()))
        for (f in AppLog.extraFiles(this)) uris.add(FileProvider.getUriForFile(this, "$packageName.files", f))
        val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("text/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .putExtra(Intent.EXTRA_SUBJECT, "Journal TurboIRL")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Envoyer le journal"))
    }

    private fun bluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasBluetoothPermissions() =
        bluetoothPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requestBluetoothPermissions() {
        if (!hasBluetoothPermissions()) requestPermissions(bluetoothPermissions(), 2)
    }

    @SuppressLint("BatteryLife") // sideloaded personal tool, the exemption is the whole point
    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

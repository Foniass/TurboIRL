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
    private lateinit var log: TextView

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
        log = findViewById(R.id.log)

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
        goproEnabled.setOnCheckedChangeListener { _, checked -> if (checked) requestBluetoothPermissions() }

        toggle.setOnClickListener { if (RelayService.instance != null) RelayService.stop(this) else startRelay() }
        battery.setOnClickListener { requestBatteryExemption() }
        share.setOnClickListener { shareLog() }
        tethering.setOnClickListener { openTetheringSettings() }
        sendVps.setOnClickListener { sendJournalToVps() }
        obsStart.setOnClickListener { confirmObs("start") }
        obsStop.setOnClickListener { confirmObs("stop") }

        val wanted = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        wanted.add(Manifest.permission.ACCESS_FINE_LOCATION)
        wanted.add(Manifest.permission.READ_PHONE_STATE)
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        obsPolling = true
        Thread { pollObs() }.start()
    }

    override fun onPause() {
        obsPolling = false
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    /** Every 5 s while the screen is visible: OBS state on the PC, as published by the receiver through the VPS. */
    private fun pollObs() {
        while (obsPolling) {
            val token = vpsToken.text.toString().trim()
            val text = if (token.isEmpty()) {
                "OBS PC : renseigne le jeton VPS pour piloter le stream"
            } else {
                val st = Uploader.fetchObsStatus(Config.load(this).vpsUrl, token)
                when {
                    st == null -> "OBS PC : VPS injoignable"
                    !st.optBoolean("receiverOk") -> "OBS PC : récepteur PC absent (PC éteint ou récepteur non lancé)"
                    !st.optBoolean("obsOpen") -> "OBS PC : OBS fermé sur le PC"
                    st.optBoolean("streaming") -> "OBS PC : EN DIRECT depuis ${st.optString("timecode")} · ${st.optInt("kbps")} kb/s"
                    else -> "OBS PC : prêt, pas en direct"
                } + (st?.optJSONObject("lastCommand")?.let { c ->
                    if (c.optBoolean("done")) "\n  dernière commande ${c.optString("action")} : ${c.optString("result")}"
                    else "\n  commande ${c.optString("action")} en attente du PC…"
                } ?: "")
            }
            handler.post { obsStatus.text = text }
            try {
                Thread.sleep(5000)
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
        ).save(this)
        RelayService.start(this)
    }

    private fun refresh() {
        val service = RelayService.instance
        val running = service != null
        toggle.text = if (running) "Arrêter" else "Démarrer"
        for (field in listOf(srtHost, srtPort, srtLatency, goproSsid, goproPassword, goproResolution, goproMaxKbps, outMaxKbps, outMaxHeight, audioKbps, vpsToken)) {
            field.isEnabled = !running
        }
        goproEnabled.isEnabled = !running
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
        log.text = AppLog.recent()

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

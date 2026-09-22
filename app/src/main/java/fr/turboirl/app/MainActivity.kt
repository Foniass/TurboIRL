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
    private lateinit var srtStreamId: EditText
    private lateinit var toggle: Button
    private lateinit var battery: Button
    private lateinit var share: Button
    private lateinit var adaptive: CheckBox
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
        srtStreamId = findViewById(R.id.srtStreamId)
        toggle = findViewById(R.id.toggle)
        battery = findViewById(R.id.battery)
        share = findViewById(R.id.share)
        adaptive = findViewById(R.id.adaptive)
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
        srtStreamId.setText(config.srtStreamId)
        adaptive.isChecked = config.adaptive
        goproEnabled.isChecked = config.goproEnabled
        goproSsid.setText(config.goproSsid)
        goproPassword.setText(config.goproPassword)
        goproResolution.setText(config.goproResolution.toString())
        goproMaxKbps.setText(config.goproMaxKbps.toString())
        goproEnabled.setOnCheckedChangeListener { _, checked -> if (checked) requestBluetoothPermissions() }

        toggle.setOnClickListener { if (RelayService.instance != null) RelayService.stop(this) else startRelay() }
        battery.setOnClickListener { requestBatteryExemption() }
        share.setOnClickListener { shareLog() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun startRelay() {
        val host = srtHost.text.toString().trim()
        val port = srtPort.text.toString().toIntOrNull()
        val latency = srtLatency.text.toString().toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535 || latency == null || latency !in 120..15000) {
            Toast.makeText(this, "Adresse, port (1-65535) et latence (120-15000 ms) requis", Toast.LENGTH_LONG).show()
            return
        }
        val goproOn = goproEnabled.isChecked
        val resolution = goproResolution.text.toString().toIntOrNull()
        val maxKbps = goproMaxKbps.text.toString().toIntOrNull()
        if (goproOn) {
            if (goproSsid.text.isBlank() || goproPassword.text.length < 8) {
                Toast.makeText(this, "Nom et mot de passe du hotspot requis pour piloter la GoPro", Toast.LENGTH_LONG).show()
                return
            }
            if (resolution !in setOf(480, 720, 1080) || maxKbps == null || maxKbps !in 800..8000) {
                Toast.makeText(this, "Résolution 480/720/1080 et débit max entre 800 et 8000 kb/s", Toast.LENGTH_LONG).show()
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
            srtStreamId = srtStreamId.text.toString().trim(),
            adaptive = adaptive.isChecked,
            goproEnabled = goproOn,
            goproSsid = goproSsid.text.toString().trim(),
            goproPassword = goproPassword.text.toString(),
            goproResolution = resolution ?: 720,
            goproMaxKbps = maxKbps ?: 2500,
        ).save(this)
        RelayService.start(this)
    }

    private fun refresh() {
        val service = RelayService.instance
        val running = service != null
        toggle.text = if (running) "Arrêter" else "Démarrer"
        for (field in listOf(srtHost, srtPort, srtLatency, srtStreamId, goproSsid, goproPassword, goproResolution, goproMaxKbps)) {
            field.isEnabled = !running
        }
        goproEnabled.isEnabled = !running
        adaptive.isEnabled = !running
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
        val brake = if (s.cameraLimitKbps > 0) "\n       frein caméra ${s.cameraLimitKbps} kb/s" else ""
        val pauses = if (s.videoSuspensions > 0) " · vidéo en pause ${s.videoSuspensions}× (${s.videoSuspendedMs / 1000} s)" else ""
        return "$camera\n$srt$brake\n       connexions : caméra ${s.cameraSessions} · SRT ${s.srt.connections}$pauses"
    }

    private fun shareLog() {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", AppLog.exportForShare())
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
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

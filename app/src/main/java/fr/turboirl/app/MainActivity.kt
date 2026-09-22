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
        rtmpUrl = findViewById(R.id.rtmpUrl)
        status = findViewById(R.id.status)
        log = findViewById(R.id.log)

        val config = Config.load(this)
        srtHost.setText(config.srtHost)
        srtPort.setText(config.srtPort.toString())
        srtLatency.setText(config.srtLatencyMs.toString())
        srtStreamId.setText(config.srtStreamId)

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
        Config.load(this).copy(
            srtHost = host, srtPort = port, srtLatencyMs = latency,
            srtStreamId = srtStreamId.text.toString().trim(),
        ).save(this)
        RelayService.start(this)
    }

    private fun refresh() {
        val service = RelayService.instance
        val running = service != null
        toggle.text = if (running) "Arrêter" else "Démarrer"
        for (field in listOf(srtHost, srtPort, srtLatency, srtStreamId)) field.isEnabled = !running

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
                "       RTT ${"%.0f".format(s.srt.rttMs)} ms · tampon ${s.srt.sendBufferMs} ms · " +
                "lien ~${"%.1f".format(s.srt.bandwidthMbps)} Mb/s\n" +
                "       retransmis ${s.srt.retransmitted} · perdus ${s.srt.dropped} · saturations ${s.srt.queueOverflows}"
        } else {
            "SRT    ✗ PC injoignable, nouvel essai en cours"
        }
        return "$camera\n$srt\n       connexions : caméra ${s.cameraSessions} · SRT ${s.srt.connections}"
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

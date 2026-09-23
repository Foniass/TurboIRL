package fr.turboirl.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import fr.turboirl.app.gopro.GoProController
import fr.turboirl.app.transcode.AdaptiveBitrate
import fr.turboirl.app.transcode.AudioTranscoder
import fr.turboirl.app.transcode.VideoTranscoder
import fr.turboirl.core.FlvToTsRelay
import fr.turboirl.core.rtmp.RtmpServer

/** Foreground service that owns the whole pipeline: RTMP ingest → MPEG-TS → SRT. */
class RelayService : Service() {

    /** Point-in-time view of the pipeline for the UI and the notification. */
    class Snapshot(
        val cameraConnected: Boolean,
        val videoInfo: String,
        val inKbps: Long,
        val outKbps: Long,
        val cameraSessions: Int,
        val videoSuspended: Boolean,
        val videoSuspensions: Int,
        val videoSuspendedMs: Long,
        val gopro: GoProController?,
        val cameraLimitKbps: Int,
        val transcoder: VideoTranscoder?,
        val encoderTargetKbps: Int,
        val encoderOutKbps: Long,
        val srt: SrtSender.Stats,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val logger = AppLog.logger

    private var rtmpServer: RtmpServer? = null
    private var relay: FlvToTsRelay? = null
    private var srtSender: SrtSender? = null
    private var gopro: GoProController? = null
    @Volatile private var stopping = false
    private var transcoder: VideoTranscoder? = null
    private var abr: AdaptiveBitrate? = null
    private var audioTranscoder: AudioTranscoder? = null
    private var lastEncBytes = 0L
    private var telemetry: Telemetry? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastInBytes = 0L
    private var lastOutBytes = 0L
    private var lastTickNs = 0L
    private var ticks = 0
    private var lastRetrans = 0L
    private var lastDropped = 0L
    private var lastOverflows = 0L

    @Volatile
    var snapshot: Snapshot? = null
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) logger.log("Service relancé par le système après un arrêt forcé")
        if (rtmpServer != null) return START_STICKY // already running

        val tele = Telemetry(this)
        var fgType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (tele.hasLocationPermission) fgType = fgType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Démarrage…"), fgType)
        tele.start()
        telemetry = tele
        instance = this

        // After a system restart the intent is null: the saved config is the source of truth.
        val config = Config.load(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TurboIRL:relay").apply { acquire() }

        // Fixed since 0.9 (validated outdoors): phone-side re-encode in H.265 with the bitrate controller reacting
        // first, audio re-encoded, audio priority as the last resort (video withheld at 60 % of the latency).
        val sender = SrtSender(config.srtHost, config.srtPort, config.srtLatencyMs, "", logger, 0.6)
        val flvRelay = FlvToTsRelay(sender, logger) { sender.stats.congested }
        flvRelay.trickleKeyframes = true
        val server = RtmpServer(config.rtmpPort, flvRelay, logger, 0)
        val minKbps = 400
        val tc = VideoTranscoder(flvRelay, logger, (config.outMaxKbps * 4 / 10).coerceAtLeast(minKbps), config.outMaxHeight, true)
        flvRelay.processor = tc
        flvRelay.growingHold = false
        transcoder = tc
        val at = AudioTranscoder(flvRelay, logger, config.audioKbps)
        flvRelay.audioProcessor = at
        audioTranscoder = at
        abr = AdaptiveBitrate(sender, tc, config.srtLatencyMs, minKbps, config.outMaxKbps, config.outMaxHeight, config.audioKbps * 13 / 10 + 40, { flvRelay.stats.videoSuspended }, logger).also { it.start() }
        logger.log("Réencodage : vidéo ${minKbps}-${config.outMaxKbps} kb/s jusqu'à ${config.outMaxHeight}p en H.265, son ${config.audioKbps} kb/s")
        try {
            server.start()
        } catch (e: Exception) {
            lastError = "Impossible d'écouter sur le port ${config.rtmpPort} : ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        sender.start()
        srtSender = sender
        relay = flvRelay
        rtmpServer = server
        lastError = null
        logger.log("Relais démarré → srt://${config.srtHost}:${config.srtPort}")

        if (config.goproEnabled) startCamera(config, flvRelay)

        lastTickNs = System.nanoTime()
        handler.postDelayed(::tick, 1000)
        return START_STICKY
    }

    /**
     * The camera joins the phone's own tethering hotspot (name/password from the settings). Hotspots the app
     * could open itself (Android local-only hotspot, Wi-Fi Direct group) were tried on 24/09: the camera saw
     * them but never managed to associate, whereas it joins the phone's hotspot fine. So this stays manual:
     * the screen offers a shortcut to the tethering settings while the hotspot is off.
     */
    private fun startCamera(config: Config, flvRelay: FlvToTsRelay) {
        startController(config, flvRelay, config.goproSsid, config.goproPassword, automatic = false, fixedIp = null)
    }

    private fun startController(config: Config, flvRelay: FlvToTsRelay, ssid: String, password: String, automatic: Boolean, fixedIp: String?) {
        gopro?.let { old ->
            gopro = null
            Thread { old.stop() }.start()
        }
        val controller = GoProController(
            this,
            GoProController.Settings(
                ssid = ssid, password = password,
                resolution = config.goproResolution, maxKbps = config.goproMaxKbps,
                knownAddress = config.goproAddress.ifEmpty { null },
            ),
            logger,
            rtmpUrl = {
                if (fixedIp != null) return@GoProController "rtmp://$fixedIp:${config.rtmpPort}/live/gopro"
                val addresses = NetUtil.localAddresses()
                // Soft AP interface first; the app's own hotspot may use an unusual name, so anything but the
                // phone's Wi-Fi client (wlan0) will do then.
                val a = addresses.firstOrNull { NetUtil.isHotspot(it.iface) }
                    ?: if (automatic) addresses.firstOrNull { it.iface != "wlan0" } else null
                a?.let { "rtmp://${it.ip}:${config.rtmpPort}/live/gopro" }
            },
            cameraPublishing = { flvRelay.stats.publishing },
            onDeviceLearnt = { address, name ->
                Config.load(this).copy(goproAddress = address, goproName = name).save(this)
            },
        )
        controller.start()
        gopro = controller
        logger.log("Pilotage GoPro activé (hotspot du téléphone « $ssid »)")
    }

    override fun onDestroy() {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        telemetry?.stop()
        telemetry = null
        val server = rtmpServer
        val sender = srtSender
        val controller = gopro
        val tc = transcoder
        val at = audioTranscoder
        val ab = abr
        audioTranscoder = null
        transcoder = null
        abr = null
        rtmpServer = null
        srtSender = null
        relay = null
        gopro = null
        // Socket teardown can block for a few seconds: keep it off the main thread.
        Thread {
            ab?.stop()
            tc?.release()
            at?.release()
            controller?.stop()
            server?.stop()
            sender?.stop()
        }.start()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        snapshot = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun tick() {
        val flvRelay = relay ?: return
        val sender = srtSender ?: return
        val now = System.nanoTime()
        val seconds = (now - lastTickNs) / 1e9
        lastTickNs = now

        val stats = flvRelay.stats
        val inBytes = stats.videoBytes + stats.audioBytes
        val outBytes = stats.tsBytes
        val snap = Snapshot(
            cameraConnected = stats.publishing,
            videoInfo = stats.videoInfo,
            inKbps = ((inBytes - lastInBytes) * 8 / 1000 / seconds).toLong(),
            outKbps = ((outBytes - lastOutBytes) * 8 / 1000 / seconds).toLong(),
            cameraSessions = stats.sessions,
            videoSuspended = stats.videoSuspended,
            videoSuspensions = stats.videoSuspensions,
            videoSuspendedMs = stats.videoSuspendedMs,
            gopro = gopro,
            cameraLimitKbps = 0,
            transcoder = transcoder,
            encoderTargetKbps = abr?.targetKbps ?: 0,
            encoderOutKbps = transcoder?.let { t -> val b = t.stats.bytesOut; val r = ((b - lastEncBytes) * 8 / 1000 / seconds).toLong(); lastEncBytes = b; r } ?: 0,
            srt = sender.stats,
        )
        lastInBytes = inBytes
        lastOutBytes = outBytes
        snapshot = snap
        telemetry?.row(snap)

        ticks++
        if (ticks % STATS_EVERY_TICKS == 0 && (snap.cameraConnected || snap.srt.connected)) {
            val st = snap.srt
            logger.log(
                "Stats : reçu ${snap.inKbps} kb/s · envoyé ${snap.outKbps} kb/s · " +
                    if (st.connected) {
                        "SRT sortie ${"%.0f".format(st.sendRateMbps * 1000)} kb/s, RTT ${"%.0f".format(st.rttMs)} ms, " +
                            "en vol ${st.flightPackets} pq, tampon ${st.sendBufferMs} ms/${st.sendBufferPackets} pq, " +
                            "retransmis +${st.retransmitted - lastRetrans}, perdus +${st.dropped - lastDropped}, " +
                            "saturations +${st.queueOverflows - lastOverflows}" +
                            (if (snap.srt.critical) ", VIDÉO RETENUE (son seul)" else "") +
                            (if (snap.cameraLimitKbps > 0) ", frein caméra ${snap.cameraLimitKbps} kb/s" else "") +
                            (snap.transcoder?.let { t -> ", encodeur ${snap.encoderTargetKbps} kb/s (réel ${snap.encoderOutKbps}, ${t.stats.width}x${t.stats.height}${if (t.stats.frameDivider > 1) " 1/${t.stats.frameDivider} cadence" else ""}, entrées ${t.stats.framesIn} décodées ${t.stats.framesDecoded} reçues GL ${t.scalerFramesReceived()} dessinées ${t.scalerFramesDrawn()} sorties ${t.stats.framesOut}, attente encodeur ${t.scalerSwapWaitMs()} ms, perdues ${t.stats.framesDropped})" } ?: "") +
                            (if (snap.videoSuspended) ", VIDÉO SUSPENDUE" else "")
                    } else "SRT déconnecté"
            )
            lastRetrans = st.retransmitted
            lastDropped = st.dropped
            lastOverflows = st.queueOverflows
        }

        val camera = if (snap.cameraConnected) "GoPro ✓ ${snap.inKbps} kb/s" + (if (snap.videoSuspended) " (son seul)" else "") else "GoPro ✗"
        val srt = if (snap.srt.connected) "SRT ✓ ${"%.0f".format(snap.srt.rttMs)} ms" else "SRT ✗"
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification("$camera · $srt"))
        handler.postDelayed(::tick, 1000)
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Relais actif", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RelayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("TurboIRL")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Arrêter", stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "relay"
        private const val NOTIFICATION_ID = 1
        private const val STATS_EVERY_TICKS = 5
        private const val ACTION_STOP = "fr.turboirl.app.STOP"

        /** The running service, if any. Same process, so the UI just reads it. */
        @Volatile
        var instance: RelayService? = null
            private set

        /** Why the last start attempt failed, null if it didn't. */
        @Volatile
        var lastError: String? = null
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RelayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayService::class.java))
        }
    }
}

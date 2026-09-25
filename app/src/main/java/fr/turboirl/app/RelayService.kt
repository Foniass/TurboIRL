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
import fr.turboirl.app.net.LinkMux
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
        val transcoder: VideoTranscoder?,
        val encoderTargetKbps: Int,
        val encoderOutKbps: Long,
        val srt: SrtSender.Stats,
        val links: List<LinkMux.LinkStats>,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val logger = AppLog.logger

    private var rtmpServer: RtmpServer? = null
    private var relay: FlvToTsRelay? = null
    private var srtSender: SrtSender? = null
    private var mux: LinkMux? = null
    private var fakeCamera: FakeCamera? = null
    private var gopro: GoProController? = null
    private var transcoder: VideoTranscoder? = null
    private var abr: AdaptiveBitrate? = null
    private var audioTranscoder: AudioTranscoder? = null
    private var lastEncBytes = 0L
    private var telemetry: Telemetry? = null
    var uploader: Uploader? = null
        private set
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastInBytes = 0L
    private var lastOutBytes = 0L
    private var lastTickNs = 0L
    private var ticks = 0
    private var lastRetrans = 0L
    private var lastDropped = 0L
    private var lastOverflows = 0L
    private var lastVideoDroppedForAudio = 0L

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
        val streamId = Config.publishStreamId(config.vpsToken)
        if (streamId.isEmpty()) logger.log("Pas de jeton VPS : le relais SRT du VPS refusera le flux (jeton à saisir dans l'écran)")
        // Every network of the phone carries a part of the SRT stream to the VPS (bond service), see LinkMux
        val impair = if (config.testChannel) mapOf(
            LinkMux.KIND_CELL to LinkMux.Impairment.parse(config.impairCell),
            LinkMux.KIND_WIFI to LinkMux.Impairment.parse(config.impairWifi),
        ) else emptyMap()
        val linkMux = LinkMux(this, config.srtHost, config.srtPort, logger, Config.linkShares(config.cellPlanGb, config.wifiPlanGb), impair)
        try {
            linkMux.start()
        } catch (e: Exception) {
            lastError = "Répartiteur de liens impossible : ${e.message ?: e.javaClass.simpleName}"
            logger.log(lastError!!)
            stopSelf()
            return START_NOT_STICKY
        }
        mux = linkMux
        val sender = SrtSender("127.0.0.1", linkMux.localPort, config.srtLatencyMs, logger, 0.6, streamId)
        val flvRelay = FlvToTsRelay(sender, logger) { sender.stats.congested }
        flvRelay.trickleKeyframes = true
        val server = RtmpServer(config.rtmpPort, flvRelay, logger)
        val minKbps = 400
        val tc = VideoTranscoder(flvRelay, logger, (config.outMaxKbps * 4 / 10).coerceAtLeast(minKbps), config.outMaxHeight)
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

        // One session per start: telemetry every 30 s and the journal to the VPS (turboirl-api)
        val startedAt = Uploader.isoNow()
        val session = Uploader.newSession(java.util.Date())
        config.copy(lastSession = session, lastStartedAt = startedAt).save(this)
        if (config.vpsToken.isNotBlank()) {
            uploader = Uploader(this, config.vpsUrl, config.vpsToken, session, startedAt, appVersion(), logger, tele) {
                srtSender?.stats?.sendBufferMs ?: 0
            }.also { it.start() }
            logger.log("Envoi automatique vers le VPS activé (session $session)")
        } else {
            logger.log("Pas de jeton VPS : télémétrie et journal restent sur le téléphone")
        }

        if (config.testChannel && config.testSource) startFakeCamera(config, flvRelay)
        else if (config.goproEnabled) startCamera(config, flvRelay)

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
        gopro?.let { old ->
            gopro = null
            Thread { old.stop() }.start()
        }
        val ssid = config.goproSsid
        val controller = GoProController(
            this,
            GoProController.Settings(
                ssid = ssid, password = config.goproPassword,
                resolution = config.goproResolution, maxKbps = config.goproMaxKbps,
                knownAddress = config.goproAddress.ifEmpty { null },
            ),
            logger,
            rtmpUrl = {
                NetUtil.localAddresses().firstOrNull { NetUtil.isHotspot(it.iface) }
                    ?.let { "rtmp://${it.ip}:${config.rtmpPort}/live/gopro" }
            },
            cameraPublishing = { flvRelay.stats.publishing },
            onDeviceLearnt = { address, _ ->
                Config.load(this).copy(goproAddress = address).save(this)
            },
        )
        controller.start()
        gopro = controller
        logger.log("Pilotage GoPro activé (hotspot du téléphone « $ssid »)")
    }

    /** Bench: the synthetic video (downloaded once from the VPS) plays in a loop in place of the camera. */
    private fun startFakeCamera(config: Config, flvRelay: FlvToTsRelay) {
        val file = java.io.File(java.io.File(filesDir, "bench").apply { mkdirs() }, "TurboIRL-bench.mp4")
        Thread {
            try {
                if (!file.exists() || file.length() < 1_000_000) {
                    logger.log("Source de test : téléchargement de la vidéo synthétique depuis le VPS…")
                    val conn = (java.net.URL(config.vpsUrl.trimEnd('/') + "/api/turboirl/releases/TurboIRL-bench.mp4").openConnection() as java.net.HttpURLConnection)
                    conn.connectTimeout = 10000
                    conn.readTimeout = 60000
                    conn.setRequestProperty("Authorization", "Bearer ${config.vpsToken}")
                    try {
                        if (conn.responseCode != 200) throw java.io.IOException("réponse ${conn.responseCode}")
                        val tmp = java.io.File(file.path + ".part")
                        conn.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                        if (!tmp.renameTo(file)) throw java.io.IOException("renommage impossible")
                    } finally {
                        conn.disconnect()
                    }
                    logger.log("Source de test : vidéo reçue (${file.length() / 1_000_000} Mo)")
                }
                if (relay === flvRelay) {
                    fakeCamera = FakeCamera(file, flvRelay, logger).also { it.start() }
                }
            } catch (e: Exception) {
                logger.log("Source de test indisponible : ${e.message ?: e.javaClass.simpleName}")
            }
        }.start()
    }

    private fun appVersion(): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        uploader?.finish()
        uploader = null
        telemetry?.stop()
        telemetry = null
        mux?.let { logger.log(it.bilan()) }
        val server = rtmpServer
        val sender = srtSender
        val controller = gopro
        val linkMux = mux
        val fake = fakeCamera
        mux = null
        fakeCamera = null
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
            fake?.stop()
            server?.stop()
            sender?.stop()
            linkMux?.stop()
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
            transcoder = transcoder,
            encoderTargetKbps = abr?.targetKbps ?: 0,
            encoderOutKbps = transcoder?.let { t -> val b = t.stats.bytesOut; val r = ((b - lastEncBytes) * 8 / 1000 / seconds).toLong(); lastEncBytes = b; r } ?: 0,
            srt = sender.stats,
            links = mux?.snapshot() ?: emptyList(),
        )
        lastInBytes = inBytes
        lastOutBytes = outBytes
        snapshot = snap
        telemetry?.row(snap)

        ticks++
        if (ticks % STATS_EVERY_TICKS == 0 && (snap.cameraConnected || snap.srt.connected)) {
            val st = snap.srt
            val link = if (st.connected) {
                "SRT sortie ${"%.0f".format(st.sendRateMbps * 1000)} kb/s, RTT ${"%.0f".format(st.rttMs)} ms, " +
                    "en vol ${st.flightPackets} pq, tampon ${st.sendBufferMs} ms/${st.sendBufferPackets} pq, " +
                    "retransmis +${st.retransmitted - lastRetrans}, perdus +${st.dropped - lastDropped}, " +
                    "saturations +${st.queueOverflows - lastOverflows}" +
                    (if (snap.srt.critical) ", VIDÉO RETENUE (son seul)" else "") +
                    (snap.transcoder?.let { t -> ", encodeur ${snap.encoderTargetKbps} kb/s (réel ${snap.encoderOutKbps}, ${t.stats.width}x${t.stats.height}${if (t.stats.frameDivider > 1) " 1/${t.stats.frameDivider} cadence" else ""}, entrées ${t.stats.framesIn} décodées ${t.stats.framesDecoded} reçues GL ${t.scalerFramesReceived()} dessinées ${t.scalerFramesDrawn()} sorties ${t.stats.framesOut}, attente encodeur ${t.scalerSwapWaitMs()} ms, perdues ${t.stats.framesDropped})" } ?: "") +
                    (if (snap.videoSuspended) ", VIDÉO SUSPENDUE" else "") +
                    (if (snap.links.isNotEmpty()) ", liens " + snap.links.joinToString(" / ") { "${it.name} ${it.state} ${it.kbps} kb/s RTT ${it.rttMs} ms pertes ${it.lossPct} % part ${it.sharePct} %" } else "")
            } else "SRT déconnecté"
            val restarts = snap.transcoder?.stats?.restarts ?: 0
            logger.log(
                "Stats : reçu ${snap.inKbps} kb/s · envoyé ${snap.outKbps} kb/s · " + link +
                    ", vidéo jetée (mode critique) +${st.videoDroppedForAudio - lastVideoDroppedForAudio}" +
                    (audioTranscoder?.let { a -> ", son réencodé ${a.stats.framesIn}→${a.stats.framesOut} (perdues ${a.stats.dropped})" } ?: "") +
                    (if (restarts > 0) ", redémarrages transcodeur $restarts" else "")
            )
            lastRetrans = st.retransmitted
            lastDropped = st.dropped
            lastOverflows = st.queueOverflows
            lastVideoDroppedForAudio = st.videoDroppedForAudio
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

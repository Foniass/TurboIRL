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
        val srt: SrtSender.Stats,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val logger = AppLog.logger

    private var rtmpServer: RtmpServer? = null
    private var relay: FlvToTsRelay? = null
    private var srtSender: SrtSender? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastInBytes = 0L
    private var lastOutBytes = 0L
    private var lastTickNs = 0L

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

        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("Démarrage…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        instance = this

        // After a system restart the intent is null: the saved config is the source of truth.
        val config = Config.load(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TurboIRL:relay").apply { acquire() }

        val sender = SrtSender(config.srtHost, config.srtPort, config.srtLatencyMs, config.srtStreamId, logger)
        val flvRelay = FlvToTsRelay(sender, logger)
        val server = RtmpServer(config.rtmpPort, flvRelay, logger)
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

        lastTickNs = System.nanoTime()
        handler.postDelayed(::tick, 1000)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        val server = rtmpServer
        val sender = srtSender
        rtmpServer = null
        srtSender = null
        relay = null
        // Socket teardown can block for a few seconds: keep it off the main thread.
        Thread {
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
            srt = sender.stats,
        )
        lastInBytes = inBytes
        lastOutBytes = outBytes
        snapshot = snap

        val camera = if (snap.cameraConnected) "GoPro ✓ ${snap.inKbps} kb/s" else "GoPro ✗"
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

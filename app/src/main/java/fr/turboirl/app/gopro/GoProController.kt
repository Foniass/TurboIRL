package fr.turboirl.app.gopro

import android.bluetooth.BluetoothDevice
import android.content.Context
import fr.turboirl.core.rtmp.Logger
import java.util.Calendar

/**
 * Drives the camera over BLE so that the rider never touches it: connect, join the phone's
 * hotspot, configure the RTMP live stream towards the relay, start it, and restart it whenever
 * it drops. Runs its own thread; [stop] tears everything down.
 */
class GoProController(
    context: Context,
    private val settings: Settings,
    private val logger: Logger,
    /** RTMP URL the camera must stream to (null while the hotspot has no address yet). */
    private val rtmpUrl: () -> String?,
    /** Is the camera currently publishing to the relay? */
    private val cameraPublishing: () -> Boolean,
    /** Called when the camera's BLE address is learnt, to remember it. */
    private val onDeviceLearnt: (address: String, name: String) -> Unit,
) {
    class Settings(
        val ssid: String,
        val password: String,
        val resolution: Int, // 480, 720 or 1080
        val maxKbps: Int,
        val knownAddress: String?,
    )

    enum class State(val label: String) {
        OFF("arrêté"),
        SCANNING("recherche de la caméra…"),
        CONNECTING("connexion Bluetooth…"),
        INITIALISING("initialisation…"),
        WIFI("connexion de la caméra au hotspot…"),
        CONFIGURING("configuration du live…"),
        WAITING_READY("caméra en préparation…"),
        STREAMING("en direct"),
        RETRYING("nouvel essai…"),
        ERROR("erreur"),
    }

    @Volatile var state = State.OFF
        private set

    @Volatile var detail = ""
        private set

    @Volatile var cameraName = ""
        private set

    @Volatile var cameraBitrateKbps = 0
        private set

    private val ble = GoProBle(context, logger)

    @Volatile private var running = false
    private var thread: Thread? = null
    private var keepAlive: Thread? = null

    // Live stream status as last reported by the camera (NotifyLiveStreamStatus)
    @Volatile private var liveStatus = -1
    @Volatile private var liveError = 0
    @Volatile private var liveStatusAt = 0L

    // Network provisioning notifications
    @Volatile private var provisioning = -1
    @Volatile private var scanState = -1
    @Volatile private var scanId = -1

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "gopro-control").apply { start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(8000)
        thread = null
    }

    private fun set(state: State, detail: String = "") {
        this.state = state
        this.detail = detail
    }

    private fun loop() {
        ble.listener = GoProBle.Listener(::onNotification)
        var failures = 0
        try {
            while (running) {
                try {
                    connectCamera()
                    initCamera()
                    joinHotspot()
                    failures = 0
                    runLiveStream() // returns when the stream needs to be set up again
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (!running) break
                    val msg = e.message ?: e.javaClass.simpleName
                    set(State.ERROR, msg)
                    if (ble.connected) {
                        // The camera is still with us (hotspot not visible yet, live refused…): just try again.
                        logger.log("GoPro : $msg — nouvel essai dans 5 s")
                        Thread.sleep(5000)
                    } else {
                        failures++
                        logger.log("GoPro : $msg")
                        stopKeepAlive()
                        ble.close()
                        Thread.sleep(minOf(5000L * failures, 30_000L))
                    }
                    set(State.RETRYING)
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            stopKeepAlive()
            try {
                if (ble.connected) ble.command(CMD_SET_SHUTTER, byteArrayOf(1, 0), 3000)
            } catch (_: Exception) {
            }
            ble.close()
            set(State.OFF)
        }
    }

    // ---------------------------------------------------------------- steps

    private fun connectCamera() {
        if (ble.connected) return
        if (!ble.isBluetoothOn) throw GoProBle.BleException("Bluetooth désactivé sur le téléphone")
        set(State.SCANNING)
        val known = settings.knownAddress
        var device = known?.let { ble.deviceFor(it) }?.takeIf { it.bondState == BluetoothDevice.BOND_BONDED }
        if (device == null) {
            device = ble.scan(15_000, known)
                ?: throw GoProBle.BleException("aucune GoPro trouvée (allumée ? en mode appairage ?)")
        }
        cameraName = device.name ?: "GoPro"
        set(State.CONNECTING, cameraName)
        logger.log("GoPro : connexion à $cameraName (${device.address})")
        ble.connect(device, 20_000)
        onDeviceLearnt(device.address, cameraName)
        logger.log("GoPro : Bluetooth connecté")
    }

    private fun initCamera() {
        set(State.INITIALISING)
        // The camera needs a moment after connecting: poll hardware info until it answers.
        var ready = false
        for (i in 0 until 10) {
            try {
                val r = ble.command(CMD_GET_HW_INFO, timeoutMs = 3000)
                if (r.isNotEmpty() && r[0].toInt() == 0) {
                    ready = true
                    break
                }
            } catch (_: GoProBle.BleException) {
            }
            Thread.sleep(1000)
        }
        if (!ready) throw GoProBle.BleException("la caméra ne répond pas")
        try {
            ble.command(CMD_THIRD_PARTY_CLIENT_INFO)
        } catch (_: GoProBle.BleException) {
        }
        // Tell the camera pairing is done (harmless if it already knows)
        try {
            ble.proto(
                GoProBle.CM_NET_MGMT_COMM, GoProBle.FEATURE_WIRELESS, ACT_SET_PAIRING_STATE, ACT_SET_PAIRING_STATE_RSP,
                Proto.Writer().varint(1, 0).string(2, "TurboIRL").toByteArray(), 4000,
            )
        } catch (_: GoProBle.BleException) {
        }
        val c = Calendar.getInstance()
        val year = c.get(Calendar.YEAR)
        val dt = byteArrayOf(
            7, (year shr 8).toByte(), year.toByte(), (c.get(Calendar.MONTH) + 1).toByte(),
            c.get(Calendar.DAY_OF_MONTH).toByte(), c.get(Calendar.HOUR_OF_DAY).toByte(),
            c.get(Calendar.MINUTE).toByte(), c.get(Calendar.SECOND).toByte(),
        )
        try {
            ble.command(CMD_SET_DATE_TIME, dt)
        } catch (_: GoProBle.BleException) {
        }
        startKeepAlive()
    }

    private fun joinHotspot() {
        set(State.WIFI, settings.ssid)
        provisioning = -1
        scanState = -1
        scanId = -1
        var r = Proto.decode(
            ble.proto(GoProBle.CM_NET_MGMT_COMM, GoProBle.FEATURE_NETWORK, ACT_SCAN, ACT_SCAN_RSP, ByteArray(0))
        )
        if (r.int(1) != RESULT_SUCCESS) throw GoProBle.BleException("la caméra refuse de scanner le Wi-Fi (${r.int(1)})")
        waitFor(30_000, "fin du scan Wi-Fi") { scanState == SCANNING_SUCCESS }
        r = Proto.decode(
            ble.proto(
                GoProBle.CM_NET_MGMT_COMM, GoProBle.FEATURE_NETWORK, ACT_GET_AP_ENTRIES, ACT_GET_AP_ENTRIES_RSP,
                Proto.Writer().varint(1, 0).varint(2, 100).varint(3, scanId.toLong()).toByteArray(),
            )
        )
        val entries = r.messages(3)
        val entry = entries.firstOrNull { it.string(1) == settings.ssid }
            ?: throw GoProBle.BleException(
                "hotspot « ${settings.ssid} » non vu par la caméra (réseaux vus : " +
                    "${entries.mapNotNull { it.string(1) }.take(6).joinToString()})"
            )
        val flags = entry.int(5) ?: 0
        if (flags and FLAG_ASSOCIATED != 0) {
            logger.log("GoPro : déjà connectée au hotspot")
            return
        }
        val resp = if (flags and FLAG_CONFIGURED != 0) {
            logger.log("GoPro : connexion au hotspot (réseau connu)")
            ble.proto(
                GoProBle.CM_NET_MGMT_COMM, GoProBle.FEATURE_NETWORK, ACT_CONNECT, ACT_CONNECT_RSP,
                Proto.Writer().string(1, settings.ssid).toByteArray(),
            )
        } else {
            logger.log("GoPro : enregistrement du hotspot dans la caméra")
            ble.proto(
                GoProBle.CM_NET_MGMT_COMM, GoProBle.FEATURE_NETWORK, ACT_CONNECT_NEW, ACT_CONNECT_NEW_RSP,
                Proto.Writer().string(1, settings.ssid).string(2, settings.password).toByteArray(),
            )
        }
        val cr = Proto.decode(resp)
        if (cr.int(1) != RESULT_SUCCESS) throw GoProBle.BleException("connexion au hotspot refusée (${cr.int(1)})")
        val timeout = ((cr.int(3) ?: 30).coerceIn(10, 90)) * 1000L
        waitFor(timeout, "connexion de la caméra au hotspot") {
            when (provisioning) {
                PROV_SUCCESS_NEW, PROV_SUCCESS_OLD -> true
                PROV_ERR_ASSOCIATE -> throw GoProBle.BleException("la caméra n'arrive pas à joindre le hotspot")
                PROV_ERR_PASSWORD -> throw GoProBle.BleException("mot de passe du hotspot refusé")
                PROV_ERR_NO_INTERNET -> true // the relay is local, no internet needed
                PROV_ERR_UNSUPPORTED -> throw GoProBle.BleException("type de réseau non supporté par la caméra")
                else -> false
            }
        }
        logger.log("GoPro : connectée au hotspot")
    }

    /** Configures and starts the live stream, then watches it. Returns when it must be redone. */
    private fun runLiveStream() {
        val url = waitForUrl()
        set(State.CONFIGURING, url)
        try {
            ble.command(CMD_SET_SHUTTER, byteArrayOf(1, 0))
        } catch (_: GoProBle.BleException) {
        }
        val window = when (settings.resolution) {
            480 -> 4L
            1080 -> 12L
            else -> 7L
        }
        val max = settings.maxKbps.coerceIn(800, 8000).toLong()
        val mode = Proto.Writer()
            .string(1, url)
            .bool(2, false) // don't also record to the SD card
            .varint(3, window)
            .varint(7, 800) // camera-side floor
            .varint(8, max)
            .varint(9, max)
            .toByteArray()
        var r = Proto.decode(
            ble.proto(GoProBle.CQ_COMMAND, GoProBle.FEATURE_COMMAND, ACT_SET_LIVESTREAM_MODE, ACT_SET_LIVESTREAM_MODE_RSP, mode)
        )
        if (r.int(1) != RESULT_SUCCESS) throw GoProBle.BleException("configuration du live refusée (${r.int(1)})")
        logger.log("GoPro : live configuré → $url (${settings.resolution}p, max $max kb/s)")

        liveStatus = -1
        r = Proto.decode(
            ble.proto(
                GoProBle.CQ_QUERY, GoProBle.FEATURE_QUERY, ACT_GET_LIVESTREAM_STATUS, ACT_LIVESTREAM_STATUS_RSP,
                Proto.Writer().varint(1, 1).varint(1, 2).varint(1, 4).toByteArray(),
            )
        )
        applyLiveStatus(r)
        set(State.WAITING_READY)
        waitFor(60_000, "caméra prête à streamer") {
            when (liveStatus) {
                LIVE_READY, LIVE_STREAMING -> true
                LIVE_FAILED -> throw GoProBle.BleException("le live a échoué côté caméra (${errorLabel(liveError)})")
                LIVE_UNAVAILABLE -> throw GoProBle.BleException("live indisponible (réglage d'objectif ?)")
                else -> false
            }
        }
        Thread.sleep(2000)
        if (liveStatus != LIVE_STREAMING) {
            val s = ble.command(CMD_SET_SHUTTER, byteArrayOf(1, 1))
            if (s.isEmpty() || s[0].toInt() != 0) throw GoProBle.BleException("démarrage refusé (statut ${s.getOrNull(0)})")
        }
        logger.log("GoPro : live démarré")
        set(State.STREAMING)

        var notPublishingSince = 0L
        while (running) {
            Thread.sleep(1000)
            if (!ble.connected) throw GoProBle.BleException("Bluetooth perdu")
            when (liveStatus) {
                LIVE_STREAMING, LIVE_READY -> {}
                LIVE_RECONNECTING -> set(State.STREAMING, "la caméra se reconnecte…")
                else -> if (System.currentTimeMillis() - liveStatusAt > 5000) {
                    logger.log("GoPro : le live s'est arrêté (${statusLabel(liveStatus)}, ${errorLabel(liveError)}), relance")
                    return
                }
            }
            // The camera says it streams but nothing reaches the relay: the hotspot IP probably changed.
            if (cameraPublishing()) {
                notPublishingSince = 0
                if (liveStatus == LIVE_STREAMING) set(State.STREAMING)
            } else {
                if (notPublishingSince == 0L) {
                    notPublishingSince = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - notPublishingSince > 30_000) {
                    logger.log("GoPro : aucun flux reçu depuis 30 s alors que la caméra dit streamer, reconfiguration")
                    return
                }
            }
        }
    }

    private fun waitForUrl(): String {
        var warned = false
        while (running) {
            rtmpUrl()?.let { return it }
            if (!warned) {
                warned = true
                logger.log("GoPro : en attente de l'adresse du hotspot (partage de connexion activé ?)")
            }
            set(State.CONFIGURING, "en attente du hotspot")
            Thread.sleep(2000)
        }
        throw InterruptedException()
    }

    private inline fun waitFor(timeoutMs: Long, what: String, ready: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!ready()) {
            if (!running) throw InterruptedException()
            if (!ble.connected) throw GoProBle.BleException("Bluetooth perdu")
            if (System.currentTimeMillis() > deadline) throw GoProBle.BleException("$what : délai dépassé")
            Thread.sleep(250)
        }
    }

    // ---------------------------------------------------------------- notifications

    private fun onNotification(featureId: Int, actionId: Int, payload: ByteArray) {
        try {
            val m = Proto.decode(payload)
            when {
                featureId == GoProBle.FEATURE_QUERY && actionId == ACT_LIVESTREAM_STATUS_NOTIF -> applyLiveStatus(m)
                featureId == GoProBle.FEATURE_NETWORK && actionId == ACT_NOTIF_PROVISIONING -> provisioning = m.int(1) ?: -1
                featureId == GoProBle.FEATURE_NETWORK && actionId == ACT_NOTIF_SCAN -> {
                    scanState = m.int(1) ?: -1
                    m.int(2)?.let { scanId = it }
                }
            }
        } catch (e: IllegalArgumentException) {
            logger.log("GoPro : notification illisible (${e.message})")
        }
    }

    private fun applyLiveStatus(m: Proto.Message) {
        m.int(1)?.let {
            if (it != liveStatus) {
                liveStatus = it
                liveStatusAt = System.currentTimeMillis()
                logger.log("GoPro : état du live → ${statusLabel(it)}")
            }
        }
        m.int(2)?.let { liveError = it }
        m.int(4)?.let {
            if (it != cameraBitrateKbps) {
                cameraBitrateKbps = it
                // While the camera reconnects the value flaps 0/2500 every second: not worth a line each
                if (liveStatus == LIVE_STREAMING && it > 0) logger.log("GoPro : débit d'encodage $it kb/s")
            }
        }
    }

    // ---------------------------------------------------------------- keep-alive

    private fun startKeepAlive() {
        stopKeepAlive()
        keepAlive = Thread({
            try {
                while (running && ble.connected) {
                    Thread.sleep(3000)
                    try {
                        ble.setSetting(SETTING_LED, 66, 3000)
                    } catch (_: GoProBle.BleException) {
                        // the control loop will notice the disconnection
                    }
                }
            } catch (_: InterruptedException) {
            }
        }, "gopro-keepalive").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopKeepAlive() {
        keepAlive?.interrupt()
        keepAlive = null
    }

    private fun statusLabel(s: Int) = when (s) {
        0 -> "inactif"
        1 -> "configuration"
        2 -> "prêt"
        3 -> "en direct"
        4 -> "terminé"
        5 -> "échec"
        6 -> "reconnexion"
        7 -> "indisponible"
        else -> "inconnu ($s)"
    }

    private fun errorLabel(e: Int) = when (e) {
        0 -> "pas d'erreur"
        1 -> "erreur réseau"
        2 -> "URL ou serveur RTMP injoignable"
        3 -> "mémoire caméra"
        4 -> "flux interne"
        5 -> "pas d'internet"
        6 -> "connexion fermée par le serveur"
        7 -> "Wi-Fi injoignable"
        8 -> "SSL"
        9 -> "caméra bloquée"
        40 -> "carte SD pleine"
        41 -> "carte SD retirée"
        else -> "erreur $e"
    }

    private companion object {
        const val CMD_SET_SHUTTER = 0x01
        const val CMD_SET_DATE_TIME = 0x0D
        const val CMD_GET_HW_INFO = 0x3C
        const val CMD_THIRD_PARTY_CLIENT_INFO = 0x50
        const val SETTING_LED = 91

        const val ACT_SET_PAIRING_STATE = 0x01
        const val ACT_SET_PAIRING_STATE_RSP = 0x81
        const val ACT_SCAN = 0x02
        const val ACT_SCAN_RSP = 0x82
        const val ACT_GET_AP_ENTRIES = 0x03
        const val ACT_GET_AP_ENTRIES_RSP = 0x83
        const val ACT_CONNECT = 0x04
        const val ACT_CONNECT_RSP = 0x84
        const val ACT_CONNECT_NEW = 0x05
        const val ACT_CONNECT_NEW_RSP = 0x85
        const val ACT_NOTIF_SCAN = 0x0B
        const val ACT_NOTIF_PROVISIONING = 0x0C
        const val ACT_GET_LIVESTREAM_STATUS = 0x74
        const val ACT_LIVESTREAM_STATUS_RSP = 0xF4
        const val ACT_LIVESTREAM_STATUS_NOTIF = 0xF5
        const val ACT_SET_LIVESTREAM_MODE = 0x79
        const val ACT_SET_LIVESTREAM_MODE_RSP = 0xF9

        const val RESULT_SUCCESS = 1
        const val SCANNING_SUCCESS = 5
        const val FLAG_CONFIGURED = 0x02
        const val FLAG_ASSOCIATED = 0x08
        const val PROV_SUCCESS_NEW = 5
        const val PROV_SUCCESS_OLD = 6
        const val PROV_ERR_ASSOCIATE = 7
        const val PROV_ERR_PASSWORD = 8
        const val PROV_ERR_NO_INTERNET = 10
        const val PROV_ERR_UNSUPPORTED = 11
        const val LIVE_READY = 2
        const val LIVE_STREAMING = 3
        const val LIVE_FAILED = 5
        const val LIVE_RECONNECTING = 6
        const val LIVE_UNAVAILABLE = 7
    }
}

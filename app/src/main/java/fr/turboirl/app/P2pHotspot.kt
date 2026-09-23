package fr.turboirl.app

import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import fr.turboirl.core.rtmp.Logger

/**
 * A Wi-Fi Direct group owned by the phone: to the camera it is an ordinary WPA2 access point, with a name,
 * a password and a band the app chooses (2.4 GHz, the safest for the camera) and the fixed address
 * 192.168.49.1. Preferred over Android's local-only hotspot, whose name changes every time and whose
 * security mode / band cannot be chosen (24/09: the camera saw it but could not associate).
 */
class P2pHotspot(private val context: Context, private val logger: Logger) {

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    @Volatile var active = false
        private set

    fun start(handler: Handler, onReady: (ssid: String, password: String) -> Unit, onFailed: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 29) {
            onFailed("Android trop ancien pour choisir le nom du groupe")
            return
        }
        val m = context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (m == null) {
            onFailed("Wi-Fi Direct indisponible")
            return
        }
        val ch = m.initialize(context.applicationContext, handler.looper) { logger.log("Wi-Fi Direct : canal perdu") }
        manager = m
        channel = ch
        // A group left over from a previous run makes createGroup fail with BUSY: clear it first.
        m.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = create(m, ch, handler, onReady, onFailed)
            override fun onFailure(reason: Int) = create(m, ch, handler, onReady, onFailed)
        })
    }

    private fun create(
        m: WifiP2pManager, ch: WifiP2pManager.Channel, handler: Handler,
        onReady: (String, String) -> Unit, onFailed: (String) -> Unit,
    ) {
        val config = try {
            WifiP2pConfig.Builder()
                .setNetworkName(NAME)
                .setPassphrase(PASSPHRASE)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                .enablePersistentMode(false)
                .build()
        } catch (e: IllegalArgumentException) {
            onFailed("configuration du groupe refusée (${e.message})")
            return
        }
        try {
            m.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = waitForGroup(m, ch, handler, 0, onReady, onFailed)
                override fun onFailure(reason: Int) = onFailed(reasonLabel(reason))
            })
        } catch (e: SecurityException) {
            onFailed("autorisation Wi-Fi refusée (${e.message})")
        }
    }

    /** The group is up a moment after createGroup succeeds: poll its info (name, passphrase, band). */
    private fun waitForGroup(
        m: WifiP2pManager, ch: WifiP2pManager.Channel, handler: Handler, attempt: Int,
        onReady: (String, String) -> Unit, onFailed: (String) -> Unit,
    ) {
        try {
            m.requestGroupInfo(ch) { g ->
                val pass = g?.passphrase
                if (g != null && g.isGroupOwner && !pass.isNullOrEmpty() && !g.networkName.isNullOrEmpty()) {
                    active = true
                    val band = if (Build.VERSION.SDK_INT >= 29 && g.frequency > 0) " (${if (g.frequency < 3000) "2,4 GHz" else "5 GHz"}, canal ${g.frequency} MHz)" else ""
                    logger.log("Hotspot Wi-Fi Direct créé : « ${g.networkName} »$band, adresse $OWNER_IP")
                    onReady(g.networkName, pass)
                } else if (attempt < 20) {
                    handler.postDelayed({ waitForGroup(m, ch, handler, attempt + 1, onReady, onFailed) }, 500)
                } else {
                    onFailed("groupe créé mais sans informations")
                }
            }
        } catch (e: SecurityException) {
            onFailed("autorisation Wi-Fi refusée (${e.message})")
        }
    }

    fun stop() {
        active = false
        val m = manager
        val ch = channel
        if (m != null && ch != null) {
            try {
                m.removeGroup(ch, null)
            } catch (_: Exception) {
            }
            if (Build.VERSION.SDK_INT >= 27) {
                try {
                    ch.close()
                } catch (_: Exception) {
                }
            }
        }
        manager = null
        channel = null
    }

    private fun reasonLabel(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct non supporté"
        WifiP2pManager.BUSY -> "Wi-Fi Direct occupé (Wi-Fi éteint ?)"
        WifiP2pManager.ERROR -> "erreur Wi-Fi Direct (Wi-Fi éteint ?)"
        else -> "code $reason"
    }

    companion object {
        /** Fixed on purpose: the camera keeps it as a known network and reconnects without provisioning. */
        const val NAME = "DIRECT-GP-TurboIRL"
        const val PASSPHRASE = "turboirl2026"
        /** A Wi-Fi Direct group owner always has this address. */
        const val OWNER_IP = "192.168.49.1"
    }
}

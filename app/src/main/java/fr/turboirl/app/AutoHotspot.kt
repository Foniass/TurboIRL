package fr.turboirl.app

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import fr.turboirl.core.rtmp.Logger

/**
 * A hotspot the app opens itself (Android "local-only hotspot"): random name and password that the
 * app knows and hands to the camera over Bluetooth, so nobody has to switch on the phone's tethering
 * or type anything. "Local only" means no internet through it, which the camera does not need: it only
 * has to reach the phone's RTMP server. Stopped by the system if the user turns on regular tethering.
 */
class AutoHotspot(private val context: Context, private val logger: Logger) {

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @Volatile var active = false
        private set

    fun start(handler: Handler, onReady: (ssid: String, password: String) -> Unit, onFailed: (String) -> Unit, onStopped: () -> Unit) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    active = true
                    val (ssid, password, band) = credentials(r)
                    if (ssid.isNullOrEmpty() || password.isNullOrEmpty()) {
                        onFailed("identifiants du hotspot illisibles")
                        return
                    }
                    logger.log("Hotspot automatique créé : « $ssid »$band")
                    onReady(ssid, password)
                }

                override fun onFailed(reason: Int) {
                    onFailed(reasonLabel(reason))
                }

                override fun onStopped() {
                    active = false
                    reservation = null
                    onStopped()
                }
            }, handler)
        } catch (e: Exception) { // SecurityException (permission), IllegalStateException (already requested)
            onFailed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun stop() {
        active = false
        try {
            reservation?.close()
        } catch (_: Exception) {
        }
        reservation = null
    }

    @Suppress("DEPRECATION")
    private fun credentials(r: WifiManager.LocalOnlyHotspotReservation): Triple<String?, String?, String> {
        // The band (2.4 / 5 GHz) is chosen by the system and not readable from a normal app.
        if (Build.VERSION.SDK_INT >= 30) {
            val c = r.softApConfiguration
            return Triple(c.ssid, c.passphrase, "")
        }
        val c = r.wifiConfiguration ?: return Triple(null, null, "")
        return Triple(c.SSID?.trim('"'), c.preSharedKey?.trim('"'), "")
    }

    private fun reasonLabel(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "aucun canal Wi-Fi disponible"
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> "erreur Wi-Fi (localisation désactivée ?)"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "le partage de connexion du téléphone est déjà allumé, l'éteindre"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "partage de connexion interdit sur ce téléphone"
        else -> "code $reason"
    }
}

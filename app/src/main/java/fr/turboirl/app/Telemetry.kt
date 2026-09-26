package fr.turboirl.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One CSV row per second with everything that helps explain a bad minute afterwards: link
 * state, encoder state, camera, phone health, radio and position. Shared along with the journal.
 */
class Telemetry(private val context: Context) {

    private val file = File(context.filesDir, "telemetrie.csv")
    private val previous = File(context.filesDir, "telemetrie-precedente.csv")
    /** A file written with an older set of columns is parked here (not shared) rather than mixed in. */
    private val outdated = File(context.filesDir, "telemetrie-ancien.csv")
    private val time = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Volatile private var location: Location? = null
    @Volatile private var displayNetwork = ""
    private var displayCallback: TelephonyCallback? = null
    private val locationListener = LocationListener { location = it }

    private var lastRetrans = 0L
    private var lastDropped = 0L

    private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Fine or approximate: Android 12+ lets the user grant « approximate » only, which still gives a network fix. */
    val hasLocationPermission: Boolean
        get() = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** For the journal: what the phone lets us have (26/09: a phone sent no position at all, silently). */
    fun locationState(): String {
        val perm = when {
            granted(Manifest.permission.ACCESS_FINE_LOCATION) -> "permission précise"
            granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> "permission approximative seulement"
            else -> "permission refusée"
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
        return "$perm, " + (if (providers.isEmpty()) "localisation désactivée sur le téléphone" else "fournisseurs ${providers.joinToString("+")}")
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (file.length() > MAX_BYTES) {
            previous.delete()
            file.renameTo(previous)
        }
        // The header is only written once per file: if the columns changed since, start a fresh file
        if (file.exists() && file.length() > 0 && firstLine() != HEADER) {
            outdated.delete()
            file.renameTo(outdated)
        }
        if (!file.exists() || file.length() == 0L) write(HEADER + "\n")
        write("# session ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(Date())}\n")
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                // Orange 5G is NSA: the data type says LTE while the phone shows 5G; this callback tells the truth
                val cb = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                    override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                        displayNetwork = when (info.overrideNetworkType) {
                            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "5G NSA"
                            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G+"
                            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "4G+"
                            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "4G++"
                            else -> ""
                        }
                    }
                }
                telephony.registerTelephonyCallback(context.mainExecutor, cb)
                displayCallback = cb
            } catch (_: Exception) {
            }
        }
        if (hasLocationPermission) {
            try {
                for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                    if (locationManager.isProviderEnabled(provider)) {
                        locationManager.requestLocationUpdates(provider, 2000L, 0f, locationListener)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 31) displayCallback?.let { telephony.unregisterTelephonyCallback(it) }
        displayCallback = null
    }

    private fun firstLine(): String? =
        try {
            file.bufferedReader().use { it.readLine() }
        } catch (_: IOException) {
            null
        }

    @SuppressLint("MissingPermission")
    fun row(s: RelayService.Snapshot) {
        val srt = s.srt
        val retrans = srt.retransmitted - lastRetrans
        val dropped = srt.dropped - lastDropped
        lastRetrans = srt.retransmitted
        lastDropped = srt.dropped

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pct = battery?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) level * 100 / scale else -1
        } ?: -1
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.let { if (it > 0) it / 10.0 else null }
        val thermal = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1

        var dbm = ""
        var network = ""
        try {
            dbm = telephony.signalStrength?.cellSignalStrengths?.firstOrNull()?.dbm?.toString().orEmpty()
            if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                network = displayNetwork.ifEmpty { networkName(telephony.dataNetworkType) }
            }
        } catch (_: Exception) {
        }
        val loc = location?.takeIf { System.currentTimeMillis() - it.time < 15_000 }
        val t = s.transcoder?.stats
        val g = s.gopro
        val cell = s.links.firstOrNull { it.kind == fr.turboirl.app.net.LinkMux.KIND_CELL }
        val wifi = s.links.firstOrNull { it.kind == fr.turboirl.app.net.LinkMux.KIND_WIFI }

        write(
            listOf(
                time.format(Date()),
                if (srt.connected) 1 else 0,
                srt.sendBufferMs,
                "%.0f".format(Locale.ROOT, srt.rttMs),
                "%.0f".format(Locale.ROOT, srt.sendRateMbps * 1000),
                retrans,
                dropped,
                s.inKbps,
                s.outKbps,
                if (s.videoSuspended) 1 else 0,
                s.encoderTargetKbps,
                s.encoderOutKbps,
                t?.width ?: "",
                t?.height ?: "",
                t?.frameDivider ?: "",
                t?.framesDropped ?: "",
                g?.state?.name ?: "",
                g?.cameraBitrateKbps ?: "",
                pct,
                temp?.let { "%.1f".format(Locale.ROOT, it) } ?: "",
                thermal,
                dbm,
                network,
                loc?.let { "%.6f".format(Locale.ROOT, it.latitude) } ?: "",
                loc?.let { "%.6f".format(Locale.ROOT, it.longitude) } ?: "",
                loc?.let { "%.0f".format(Locale.ROOT, it.speed * 3.6) } ?: "",
                loc?.let { "%.0f".format(Locale.ROOT, it.accuracy) } ?: "",
                cell?.state ?: "", cell?.kbps ?: "", cell?.rttMs ?: "", cell?.sharePct ?: "",
                wifi?.state ?: "", wifi?.kbps ?: "", wifi?.rttMs ?: "", wifi?.sharePct ?: "",
            ).joinToString(",") + "\n"
        )
    }

    private fun networkName(type: Int) = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> ""
        else -> type.toString()
    }

    private val pending = ArrayList<String>()

    /** Rows written since the last call (for the uploader); header and « # session » lines excluded. */
    @Synchronized
    fun drainPending(): List<String> {
        val out = ArrayList(pending)
        pending.clear()
        return out
    }

    @Synchronized
    private fun write(line: String) {
        if (!line.startsWith("#") && !line.startsWith("heure,")) {
            pending.add(line.trimEnd('\n'))
            if (pending.size > 7200) pending.removeAt(0)
        }
        try {
            file.appendText(line)
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val MAX_BYTES = 4 * 1024 * 1024
        // diviseur_cadence : 1 = 30 i/s, 2 = 15 i/s, 6 = 5 i/s (remplace demi_cadence)
        const val HEADER =
            "heure,srt,tampon_ms,rtt_ms,srt_sortie_kbps,retransmis,perdus,recu_kbps,envoye_kbps,video_suspendue," +
                "enc_cible_kbps,enc_reel_kbps,enc_largeur,enc_hauteur,diviseur_cadence,images_perdues," +
                "gopro_etat,gopro_kbps,batterie_pct,temp_c,thermique,signal_dbm,reseau,lat,lon,vitesse_kmh,precision_m," +
                "lien_5g_etat,lien_5g_kbps,lien_5g_rtt,lien_5g_pct,lien_wifi_etat,lien_wifi_kbps,lien_wifi_rtt,lien_wifi_pct"
    }
}

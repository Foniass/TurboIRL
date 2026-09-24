package fr.turboirl.app

import android.content.Context

data class Config(
    val srtHost: String,
    val srtPort: Int,
    val srtLatencyMs: Int,
    val rtmpPort: Int,
    val outMaxKbps: Int,
    val outMaxHeight: Int,
    val audioKbps: Int,
    val goproEnabled: Boolean,
    /** The phone's own tethering hotspot, which the camera joins. */
    val goproSsid: String,
    val goproPassword: String,
    val goproResolution: Int,
    val goproMaxKbps: Int,
    val goproAddress: String,
    /** VPS (turboirl-api): write token typed once; empty = automatic upload off. URL not in the UI. */
    val vpsToken: String,
    val vpsUrl: String,
    /** Follow the « test » channel (latest published) instead of « stable » (promoted by Fonias). */
    val testChannel: Boolean,
    /** Data plans, in GB: the phone's own SIM and the plan behind the Wi-Fi hotspot of the other phone. Their ratio
     *  is the target share of the bytes per link (0 = backup only). */
    val cellPlanGb: Int,
    val wifiPlanGb: Int,
    /** Test mode (test channel only): synthetic source instead of the camera, simulated faults per link. */
    val testSource: Boolean,
    val impairCell: String,
    val impairWifi: String,
    /** Last session started by the service, so that the journal can be sent by hand after a crash. */
    val lastSession: String,
    val lastStartedAt: String,
) {
    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("latencyV3", true)
            .putString("srtHost", srtHost)
            .putInt("srtPort", srtPort)
            .putInt("srtLatencyMs", srtLatencyMs)
            .putInt("rtmpPort", rtmpPort)
            .putInt("outMaxKbps", outMaxKbps)
            .putInt("outMaxHeight", outMaxHeight)
            .putInt("audioKbps", audioKbps)
            .putBoolean("goproEnabled", goproEnabled)
            .putString("goproSsid", goproSsid)
            .putString("goproPassword", goproPassword)
            .putInt("goproResolution", goproResolution)
            .putInt("goproMaxKbps", goproMaxKbps)
            .putString("goproAddress", goproAddress)
            .putString("vpsToken", vpsToken)
            .putString("vpsUrl", vpsUrl)
            .putBoolean("testChannel", testChannel)
            .putInt("cellPlanGb", cellPlanGb)
            .putInt("wifiPlanGb", wifiPlanGb)
            .putBoolean("testSource", testSource)
            .putString("impairCell", impairCell)
            .putString("impairWifi", impairWifi)
            .putString("lastSession", lastSession)
            .putString("lastStartedAt", lastStartedAt)
            .apply()
    }

    companion object {
        private const val PREFS = "config"
        const val DEFAULT_VPS_URL = "https://turboirl.mathisjacqueline.com"
        /** MediaMTX on the VPS: the phone publishes there, the PCs read from there (no port forwarding anywhere). */
        const val DEFAULT_SRT_HOST = "turboirl.mathisjacqueline.com"
        const val DEFAULT_SRT_PORT = 8891   // service bond (fusion des liens) devant MediaMTX
        const val RELAY_PATH = "turboirl"

        /** Stream id MediaMTX expects from the phone (user `phone`, password = the VPS write token). */
        fun publishStreamId(vpsToken: String): String =
            if (vpsToken.isEmpty()) "" else "publish:$RELAY_PATH:phone:$vpsToken"

        /** Target share per link kind from the plan sizes; no plans at all = everything on whatever link exists. */
        fun linkShares(cellPlanGb: Int, wifiPlanGb: Int): Map<Int, Double> {
            val c = cellPlanGb.coerceAtLeast(0).toDouble()
            val w = wifiPlanGb.coerceAtLeast(0).toDouble()
            val t = c + w
            return if (t <= 0) mapOf(fr.turboirl.app.net.LinkMux.KIND_CELL to 1.0, fr.turboirl.app.net.LinkMux.KIND_WIFI to 1.0, fr.turboirl.app.net.LinkMux.KIND_OTHER to 1.0)
            else mapOf(fr.turboirl.app.net.LinkMux.KIND_CELL to c / t, fr.turboirl.app.net.LinkMux.KIND_WIFI to w / t, fr.turboirl.app.net.LinkMux.KIND_OTHER to 1.0)
        }

        fun load(context: Context): Config {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!p.contains("relayV1")) {
                // 2.0: the stream goes through the VPS relay; a host typed for the old direct-to-PC setup is replaced
                p.edit().putBoolean("relayV1", true).remove("srtHost").remove("srtPort").apply()
            }
            if (!p.contains("relayV2")) {
                // 2.5: the phone talks to the bond service (8891), which feeds MediaMTX (8890)
                val e = p.edit().putBoolean("relayV2", true)
                if (p.getInt("srtPort", DEFAULT_SRT_PORT) == 8890) e.remove("srtPort")
                e.apply()
            }
            return Config(
                srtHost = p.getString("srtHost", "").orEmpty().ifEmpty { DEFAULT_SRT_HOST },
                srtPort = p.getInt("srtPort", DEFAULT_SRT_PORT),
                // 12 s of SRT latency: short 4G dips are absorbed instead of freezing (≈ 15 s end to end)
                // 12 s (v3): the 23/09 test showed a 12 s near-total outage overflowing 8 s
                srtLatencyMs = if (p.contains("latencyV3")) p.getInt("srtLatencyMs", 12000) else 12000,
                rtmpPort = p.getInt("rtmpPort", 1935),
                outMaxKbps = p.getInt("outMaxKbps", 3500),
                outMaxHeight = p.getInt("outMaxHeight", 720),
                audioKbps = p.getInt("audioKbps", 64),
                goproEnabled = p.getBoolean("goproEnabled", true),
                goproSsid = p.getString("goproSsid", "").orEmpty(),
                goproPassword = p.getString("goproPassword", "").orEmpty(),
                goproResolution = p.getInt("goproResolution", 720),
                goproMaxKbps = p.getInt("goproMaxKbps", 4000),
                goproAddress = p.getString("goproAddress", "").orEmpty(),
                vpsToken = p.getString("vpsToken", "").orEmpty(),
                vpsUrl = p.getString("vpsUrl", "").orEmpty().ifEmpty { DEFAULT_VPS_URL },
                testChannel = p.getBoolean("testChannel", false),
                cellPlanGb = p.getInt("cellPlanGb", 200),
                wifiPlanGb = p.getInt("wifiPlanGb", 130),
                testSource = p.getBoolean("testSource", false),
                impairCell = p.getString("impairCell", "").orEmpty(),
                impairWifi = p.getString("impairWifi", "").orEmpty(),
                lastSession = p.getString("lastSession", "").orEmpty(),
                lastStartedAt = p.getString("lastStartedAt", "").orEmpty(),
            )
        }
    }
}

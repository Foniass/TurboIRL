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
            .putString("lastSession", lastSession)
            .putString("lastStartedAt", lastStartedAt)
            .apply()
    }

    companion object {
        private const val PREFS = "config"
        const val DEFAULT_VPS_URL = "https://turboirl.mathisjacqueline.com"

        fun load(context: Context): Config {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return Config(
                srtHost = p.getString("srtHost", "").orEmpty(),
                srtPort = p.getInt("srtPort", 9000),
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
                lastSession = p.getString("lastSession", "").orEmpty(),
                lastStartedAt = p.getString("lastStartedAt", "").orEmpty(),
            )
        }
    }
}

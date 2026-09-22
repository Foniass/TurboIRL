package fr.turboirl.app

import android.content.Context

data class Config(
    val srtHost: String,
    val srtPort: Int,
    val srtLatencyMs: Int,
    val srtStreamId: String,
    val rtmpPort: Int,
    val adaptive: Boolean,
    val transcode: Boolean,
    val outMaxKbps: Int,
    val goproEnabled: Boolean,
    val goproSsid: String,
    val goproPassword: String,
    val goproResolution: Int,
    val goproMaxKbps: Int,
    val goproAddress: String,
    val goproName: String,
) {
    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("srtHost", srtHost)
            .putInt("srtPort", srtPort)
            .putInt("srtLatencyMs", srtLatencyMs)
            .putString("srtStreamId", srtStreamId)
            .putInt("rtmpPort", rtmpPort)
            .putBoolean("adaptive", adaptive)
            .putBoolean("transcode", transcode)
            .putInt("outMaxKbps", outMaxKbps)
            .putBoolean("goproEnabled", goproEnabled)
            .putString("goproSsid", goproSsid)
            .putString("goproPassword", goproPassword)
            .putInt("goproResolution", goproResolution)
            .putInt("goproMaxKbps", goproMaxKbps)
            .putString("goproAddress", goproAddress)
            .putString("goproName", goproName)
            .apply()
    }

    companion object {
        private const val PREFS = "config"

        fun load(context: Context): Config {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return Config(
                srtHost = p.getString("srtHost", "").orEmpty(),
                srtPort = p.getInt("srtPort", 9000),
                srtLatencyMs = p.getInt("srtLatencyMs", 2000),
                srtStreamId = p.getString("srtStreamId", "").orEmpty(),
                rtmpPort = p.getInt("rtmpPort", 1935),
                adaptive = p.getBoolean("adaptive", false),
                transcode = p.getBoolean("transcode", false),
                outMaxKbps = p.getInt("outMaxKbps", 3000),
                goproEnabled = p.getBoolean("goproEnabled", false),
                goproSsid = p.getString("goproSsid", "").orEmpty(),
                goproPassword = p.getString("goproPassword", "").orEmpty(),
                goproResolution = p.getInt("goproResolution", 720),
                goproMaxKbps = p.getInt("goproMaxKbps", 2500),
                goproAddress = p.getString("goproAddress", "").orEmpty(),
                goproName = p.getString("goproName", "").orEmpty(),
            )
        }
    }
}

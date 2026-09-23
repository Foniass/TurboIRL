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
    val outMaxHeight: Int,
    val hevc: Boolean,
    val audioTranscode: Boolean,
    val audioKbps: Int,
    val goproEnabled: Boolean,
    val goproSsid: String,
    val goproPassword: String,
    val goproResolution: Int,
    val goproMaxKbps: Int,
    val goproAddress: String,
    val goproName: String,
    val goproRecord: Boolean,
) {
    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("latencyV2", true)
            .putString("srtHost", srtHost)
            .putInt("srtPort", srtPort)
            .putInt("srtLatencyMs", srtLatencyMs)
            .putString("srtStreamId", srtStreamId)
            .putInt("rtmpPort", rtmpPort)
            .putBoolean("adaptive", adaptive)
            .putBoolean("transcode", transcode)
            .putInt("outMaxKbps", outMaxKbps)
            .putInt("outMaxHeight", outMaxHeight)
            .putBoolean("hevc", hevc)
            .putBoolean("audioTranscode", audioTranscode)
            .putInt("audioKbps", audioKbps)
            .putBoolean("goproEnabled", goproEnabled)
            .putString("goproSsid", goproSsid)
            .putString("goproPassword", goproPassword)
            .putInt("goproResolution", goproResolution)
            .putInt("goproMaxKbps", goproMaxKbps)
            .putString("goproAddress", goproAddress)
            .putString("goproName", goproName)
            .putBoolean("goproRecord", goproRecord)
            .apply()
    }

    companion object {
        private const val PREFS = "config"

        fun load(context: Context): Config {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return Config(
                srtHost = p.getString("srtHost", "").orEmpty(),
                srtPort = p.getInt("srtPort", 9000),
                // 8 s of SRT latency: short 4G dips are absorbed instead of freezing (≈ 15 s end to end)
                srtLatencyMs = if (p.contains("latencyV2")) p.getInt("srtLatencyMs", 8000) else 8000,
                srtStreamId = p.getString("srtStreamId", "").orEmpty(),
                rtmpPort = p.getInt("rtmpPort", 1935),
                adaptive = p.getBoolean("adaptive", false),
                transcode = p.getBoolean("transcode", true),
                outMaxKbps = p.getInt("outMaxKbps", 3500),
                outMaxHeight = p.getInt("outMaxHeight", 720),
                hevc = p.getBoolean("hevc", true),
                audioTranscode = p.getBoolean("audioTranscode", true),
                audioKbps = p.getInt("audioKbps", 64),
                goproEnabled = p.getBoolean("goproEnabled", true),
                goproSsid = p.getString("goproSsid", "").orEmpty(),
                goproPassword = p.getString("goproPassword", "").orEmpty(),
                goproResolution = p.getInt("goproResolution", 720),
                goproMaxKbps = p.getInt("goproMaxKbps", 4000),
                goproAddress = p.getString("goproAddress", "").orEmpty(),
                goproName = p.getString("goproName", "").orEmpty(),
                goproRecord = p.getBoolean("goproRecord", false),
            )
        }
    }
}

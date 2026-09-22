package fr.turboirl.app

import android.content.Context

data class Config(
    val srtHost: String,
    val srtPort: Int,
    val srtLatencyMs: Int,
    val srtStreamId: String,
    val rtmpPort: Int,
) {
    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("srtHost", srtHost)
            .putInt("srtPort", srtPort)
            .putInt("srtLatencyMs", srtLatencyMs)
            .putString("srtStreamId", srtStreamId)
            .putInt("rtmpPort", rtmpPort)
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
            )
        }
    }
}

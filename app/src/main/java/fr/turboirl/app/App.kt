package fr.turboirl.app

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            AppLog.log("CRASH dans le thread ${thread.name} :\n${Log.getStackTraceString(e)}")
            previous?.uncaughtException(thread, e)
        }

        val version = packageManager.getPackageInfo(packageName, 0).versionName
        AppLog.log(
            "Process démarré — TurboIRL $version, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                "${Build.MANUFACTURER} ${Build.MODEL}, ${Build.DISPLAY}"
        )
        logPreviousExits()
    }

    /** Android remembers why our last processes died: this is how we catch HyperOS killing us. */
    private fun logPreviousExits() {
        if (Build.VERSION.SDK_INT < 30) return
        val prefs = getSharedPreferences("diag", Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong("lastExitTimestamp", 0)
        var newest = lastSeen
        val format = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE)
        try {
            val am = getSystemService(ActivityManager::class.java)
            for (info in am.getHistoricalProcessExitReasons(packageName, 0, 10).reversed()) {
                if (info.timestamp <= lastSeen) continue
                newest = maxOf(newest, info.timestamp)
                AppLog.log(
                    "Fin du process précédent le ${format.format(Date(info.timestamp))} : ${reasonName(info.reason)}" +
                        (info.description?.let { " — $it" } ?: "") +
                        " (statut ${info.status}, importance ${info.importance}, RSS ${info.rss * 4 / 1024} Mo)"
                )
            }
        } catch (e: Exception) {
            AppLog.log("Historique des fins de process indisponible : ${e.message}")
        }
        prefs.edit().putLong("lastExitTimestamp", newest).apply()
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "arrêt volontaire"
        ApplicationExitInfo.REASON_SIGNALED -> "tué par signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "mémoire insuffisante"
        ApplicationExitInfo.REASON_CRASH -> "crash Java"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash natif"
        ApplicationExitInfo.REASON_ANR -> "ANR (bloqué)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "échec d'initialisation"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "changement de permission"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "consommation excessive"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "arrêté par l'utilisateur (ou le système constructeur)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "utilisateur stoppé"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dépendance morte"
        ApplicationExitInfo.REASON_OTHER -> "autre (système)"
        14 -> "freezer"
        15 -> "changement d'état du paquet"
        16 -> "mise à jour de l'appli"
        else -> "inconnu ($reason)"
    }
}

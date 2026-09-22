package fr.turboirl.app

import android.content.Context
import android.util.Log
import fr.turboirl.core.rtmp.Logger
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Process-wide journal: in memory for the screen, on disk so that it survives a kill and can
 * be shared (Discord…) from the phone. The tester has no adb: this is our only telemetry.
 */
object AppLog {
    private const val TAG = "TurboIRL"
    private const val MAX_FILE_BYTES = 512 * 1024
    private const val MAX_MEMORY_LINES = 300

    private val lines = ArrayDeque<String>()
    private val format = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE)
    private lateinit var file: File
    private lateinit var previousFile: File
    private lateinit var shareFile: File

    val logger = Logger { log(it) }

    fun init(context: Context) {
        file = File(context.filesDir, "journal.txt")
        previousFile = File(context.filesDir, "journal-precedent.txt")
        shareFile = File(context.filesDir, "turboirl-journal.txt")
    }

    @Synchronized
    fun log(msg: String) {
        Log.i(TAG, msg)
        val line = "${format.format(Date())}  $msg"
        if (lines.size >= MAX_MEMORY_LINES) lines.removeFirst()
        lines.addLast(line)
        try {
            if (file.length() > MAX_FILE_BYTES) {
                previousFile.delete()
                file.renameTo(previousFile)
            }
            file.appendText(line + "\n")
        } catch (_: IOException) {
        }
    }

    @Synchronized
    fun recent(): String = lines.joinToString("\n")

    /** Concatenates the rotated and current journal into one file to hand to a share intent. */
    @Synchronized
    fun exportForShare(): File {
        shareFile.outputStream().use { out ->
            for (f in listOf(previousFile, file)) {
                if (f.exists()) f.inputStream().use { it.copyTo(out) }
            }
        }
        return shareFile
    }
}

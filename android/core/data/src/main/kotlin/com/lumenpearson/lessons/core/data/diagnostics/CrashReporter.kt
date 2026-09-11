package com.lumenpearson.lessons.core.data.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps a readable record of why the app died.
 *
 * A port of `LogManager` from [Essentials](https://github.com/sameerasw/essentials)
 * (MIT) — the same design: a rolling buffer of the last few hundred log lines, a
 * default uncaught-exception handler that writes a dated report next to them,
 * and a cap of five reports on disk so the feature cannot grow without bound.
 *
 * It exists because this app is handed out as an APK inside one school. There is
 * no Play Console and no crash service to look at, so when a pupil says "оно
 * закрылось", the only alternative to this file is a shrug. The report names the
 * build, the device, the Android version and the stack trace, which is exactly
 * what a fix needs and no more.
 *
 * **It is off until the user turns it on.** [install] does nothing while
 * `AppSettings.debugMode` is false, and nothing is written in that state. A
 * stack trace is not sensitive, but the device model and the app's own recent
 * activity are somebody's, and they should not land on disk because a developer
 * would find them convenient.
 *
 * Reports go to the app's external files directory, which is app-private but
 * reachable over MTP — so a report can be pulled off the phone by plugging it
 * in, without the app needing a single storage permission.
 */
object CrashReporter {

    /** How many log lines are kept in memory to attach to a crash. */
    private const val MAX_BUFFERED_LINES = 300

    /** How many of those lines go into the report. */
    private const val LINES_IN_REPORT = 60

    /** How many reports are kept on disk. */
    private const val MAX_REPORTS = 5

    private const val REPORTS_DIR = "crash_reports"
    private const val TAG = "CrashReporter"

    private val installed = AtomicBoolean(false)
    private val buffer = ArrayDeque<String>()

    @Volatile
    private var enabled = false

    private val fileStamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val readableStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * Installs the handler, once per process.
     *
     * The previous handler is kept and called afterwards: it is the one that
     * actually ends the process and shows the system's "app has stopped"
     * dialogue, and swallowing it would leave the app frozen instead of gone.
     */
    fun install(context: Context) {
        if (installed.getAndSet(true)) return
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Wrapped: a failure while recording a crash must not replace the
            // crash, or the report costs the user the system dialogue as well.
            runCatching { if (enabled) write(appContext, thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Turns recording on or off; the handler stays installed either way. */
    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) synchronized(buffer) { buffer.clear() }
    }

    /** Whether anything is being recorded right now. */
    fun isEnabled(): Boolean = enabled

    /**
     * Adds a line to the buffer that a crash report would carry.
     *
     * A no-op while recording is off, so a caller never has to ask first and the
     * buffer cannot fill with data the user did not agree to keep.
     */
    fun log(tag: String, message: String) {
        Log.i(tag, message)
        if (!enabled) return
        synchronized(buffer) {
            if (buffer.size >= MAX_BUFFERED_LINES) buffer.removeFirst()
            buffer.addLast("${readableStamp.format(Date())}  $tag: $message")
        }
    }

    /** Every report on disk, newest first. */
    fun reports(context: Context): List<File> =
        directory(context).listFiles { file -> file.isFile && file.extension == "log" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    /** Deletes every report. */
    fun clear(context: Context) {
        runCatching { directory(context).listFiles()?.forEach { it.delete() } }
            .onFailure { Log.e(TAG, "Failed to clear crash reports", it) }
    }

    /**
     * Writes a report by hand, for "this looks wrong" rather than "this died".
     *
     * Returns the file so a caller can offer to share it.
     */
    fun writeManualReport(context: Context, note: String): File? =
        if (enabled) write(context, Thread.currentThread().name, error = null, note = note) else null

    private fun directory(context: Context): File {
        // External files rather than internal: app-private either way, but this
        // one is visible over MTP, so a report can be pulled off the phone with
        // a cable and no permission at all.
        val dir = context.getExternalFilesDir(REPORTS_DIR) ?: File(context.filesDir, REPORTS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun write(
        context: Context,
        threadName: String,
        error: Throwable?,
        note: String? = null,
    ): File? {
        val now = Date()
        val report = buildString {
            appendLine("Время: ${readableStamp.format(now)}")
            appendLine("Сборка: ${versionOf(context)}")
            appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Поток: $threadName")
            if (error != null) {
                appendLine("Исключение: ${error.javaClass.name}")
                appendLine("Сообщение: ${error.message}")
                appendLine("Стек:")
                appendLine(stackTraceOf(error))
            }
            if (!note.isNullOrBlank()) {
                appendLine("Заметка:")
                appendLine(note)
            }
            appendLine()
            appendLine("--- Последние записи ---")
            synchronized(buffer) { buffer.toList() }.takeLast(LINES_IN_REPORT).forEach(::appendLine)
        }

        return runCatching {
            val file = File(directory(context), "crash_${fileStamp.format(now)}.log")
            file.writeText(report)
            prune(context)
            file
        }.onFailure { Log.e(TAG, "Failed to write crash report", it) }.getOrNull()
    }

    private fun prune(context: Context) {
        runCatching { reports(context).drop(MAX_REPORTS).forEach { it.delete() } }
    }

    private fun stackTraceOf(error: Throwable): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    private fun versionOf(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    }.getOrDefault("неизвестна")
}

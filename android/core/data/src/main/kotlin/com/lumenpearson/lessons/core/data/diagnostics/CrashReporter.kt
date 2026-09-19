package com.lumenpearson.lessons.core.data.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
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

    /**
     * How many reports one second of wall time can hold.
     *
     * The name used to be the stamp alone, and `writeText` truncates: two
     * threads dying in the same second — which is the ordinary shape of a
     * crash, one failure taking two threads with it — wrote two reports to one
     * file and the first was gone. Widening the stamp to milliseconds would
     * only have narrowed the window; the name is made unique by *claiming* it
     * instead, and this is how many claims are tried before giving up.
     *
     * Far past the [MAX_REPORTS] that would survive anyway. A hundredth crash
     * inside one second is a crash loop, and the hundredth report of it says
     * what the first did.
     */
    private const val MAX_SAME_SECOND = 100

    private const val REPORTS_DIR = "crash_reports"
    private const val TAG = "CrashReporter"

    private val installed = AtomicBoolean(false)
    private val buffer = ArrayDeque<String>()

    @Volatile
    private var enabled = false

    // DateTimeFormatter rather than SimpleDateFormat, which is what this used
    // to be and which is not thread-safe: it keeps a Calendar between calls.
    // `log` runs on whatever thread logged — the sync worker, an alarm — while
    // `write` runs on whichever thread died, or on the UI thread for a manual
    // report, and the two shared one formatter under different locks. The
    // failure is a garbled timestamp, or an exception thrown from inside the
    // crash handler: the one place in the app that must not have one.
    private val fileStamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val readableStamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * Device wall time, not the school's.
     *
     * The rest of the app reads the clock in the class's zone, because a bell
     * rings at 08:30 wherever the pupil is. A crash report is the opposite: it
     * is read next to the phone's own logs and next to what its owner remembers
     * doing, so it wants the time the phone was showing.
     */
    internal fun stamp(
        formatter: DateTimeFormatter,
        at: Instant,
        // device clock: a crash report is read beside the phone's own logs and
        // beside what its owner remembers doing, so it wants the time the phone
        // was showing — not the school's. The SimpleDateFormat this replaced
        // took the same zone implicitly, where this guard could not see it.
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = formatter.format(at.atZone(zone))

    /** @see stamp */
    internal val reportStamp: DateTimeFormatter get() = readableStamp

    /** @see stamp */
    internal val nameStamp: DateTimeFormatter get() = fileStamp

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
            buffer.addLast("${stamp(readableStamp, Instant.now())}  $tag: $message")
        }
    }

    /** Every report on disk, newest first. */
    fun reports(context: Context): List<File> = newestFirst(
        directory(context).listFiles { file -> file.isFile && file.extension == "log" }.orEmpty().toList(),
    )

    /**
     * Newest first, with the name as the tiebreak.
     *
     * `lastModified` alone is not enough once two reports can be written in
     * the same second, and it is the ordering [prune] deletes by: a
     * filesystem that stamps in whole seconds — or simply two writes inside
     * one millisecond — ties, `sortedByDescending` is stable, and the order
     * that decides which report is destroyed becomes whatever order the
     * directory happened to list in.
     *
     * The name breaks the tie because it is built to: `yyyy-MM-dd_HH-mm-ss`
     * sorts lexicographically in the order it runs, and the counter after it
     * is zero-padded so `_02` sorts after `_01` rather than `_10` sorting
     * between them.
     */
    internal fun newestFirst(files: List<File>): List<File> =
        files.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })

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
        val now = Instant.now()
        val report = buildString {
            appendLine("Время: ${stamp(readableStamp, now)}")
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
            val file = reportFile(directory(context), now)
            file.writeText(report)
            prune(context)
            file
        }.onFailure { Log.e(TAG, "Failed to write crash report", it) }.getOrNull()
    }

    /**
     * A file of this second that nothing else holds, created here and now.
     *
     * `createNewFile` is the whole of it: it tests and claims in one atomic
     * step, where `exists()` followed by `writeText` is two steps with another
     * thread's entire report in between. So the name is unique by
     * construction rather than by hoping two crashes do not land on the same
     * digits — and a millisecond stamp would only have been a smaller hope.
     *
     * The counter is always present and always two digits, because
     * [newestFirst] falls back to the name and a name is only a useful
     * tiebreak if it sorts in the order it was written.
     *
     * Internal so the claiming can be tested with a real directory; nothing
     * outside this object has any business naming a report.
     */
    internal fun reportFile(directory: File, at: Instant): File {
        val base = "crash_${stamp(fileStamp, at)}"
        var candidate = File(directory, name(base, 0))
        for (attempt in 0 until MAX_SAME_SECOND) {
            candidate = File(directory, name(base, attempt))
            val claimed = try {
                candidate.createNewFile()
            } catch (failure: IOException) {
                // Not "this name is taken" — the directory is gone, or is not
                // writable. Retrying ninety-nine more names cannot help, and
                // the caller already treats a failed write as a lost report.
                Log.w(TAG, "Could not claim a crash report file", failure)
                return candidate
            }
            if (claimed) return candidate
        }
        // A hundred in one second: let the last one be overwritten rather than
        // grow a name that sorts nowhere. See [MAX_SAME_SECOND].
        return candidate
    }

    private fun name(base: String, attempt: Int): String =
        "%s_%02d.log".format(Locale.US, base, attempt)

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

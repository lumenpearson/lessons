package com.lumenpearson.lessons.core.data.github

import android.os.Build
import com.lumenpearson.lessons.core.data.repository.IssueDraft

/**
 * Turns what the user typed into the issue that gets filed.
 *
 * Pure on purpose — no context, no I/O — so the sheet can show the exact device
 * block it is about to send, and a test can check the layout without a phone.
 *
 * The block is the one from the Essentials "Bug report" sheet, key for key.
 * Those keys are what a maintainer needs to reproduce a layout bug on the same
 * hardware, and nothing that identifies the phone: no serial, no account, no
 * network. The contact line is the one exception, and it is there only when
 * the user typed it.
 */
object BugReportComposer {

    /** GitHub cuts longer titles in its lists anyway, and mid-word. */
    private const val TITLE_LIMIT = 72

    private const val FALLBACK_TITLE = "Отчёт из приложения"

    /**
     * @param contactEmail included only when non-blank. The repository is
     *   public, so this lands in a public issue; the sheet says so next to the
     *   field, and leaving it empty is the default.
     */
    fun compose(description: String, contactEmail: String?, installedVersion: String): IssueDraft {
        val text = description.trim()
        val title = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.let { shorten(it) }
            ?: FALLBACK_TITLE

        val body = buildString {
            if (text.isNotEmpty()) {
                append(text)
                append("\n\n")
            }
            appendLine("| Параметр | Значение |")
            appendLine("| --- | --- |")
            deviceInfoLines(installedVersion).forEach { (key, value) ->
                appendLine("| $key | ${value.replace("|", "\\|")} |")
            }
            val email = contactEmail?.trim().orEmpty()
            if (email.isNotEmpty()) {
                appendLine()
                appendLine("Контакт: $email")
            }
        }.trimEnd()

        return IssueDraft(title = title, body = body)
    }

    /**
     * The device block as key-value pairs, in the order the issue lists them,
     * so the sheet can render the same thing it sends.
     */
    fun deviceInfoLines(installedVersion: String): List<Pair<String, String>> = listOf(
        "Manufacturer" to Build.MANUFACTURER,
        "Model" to Build.MODEL,
        "Brand" to Build.BRAND,
        "Device" to Build.DEVICE,
        "Board" to Build.BOARD,
        "Hardware" to Build.HARDWARE,
        "AndroidVersion" to Build.VERSION.RELEASE,
        "SDK" to Build.VERSION.SDK_INT.toString(),
        "SecurityPatch" to Build.VERSION.SECURITY_PATCH,
        "AppVersionName" to installedVersion,
    )

    /** Cut a character short of the limit so the ellipsis fits inside it. */
    private fun shorten(line: String): String =
        if (line.length <= TITLE_LIMIT) line else line.take(TITLE_LIMIT - 1).trimEnd() + "…"
}

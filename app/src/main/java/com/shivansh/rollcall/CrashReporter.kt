package com.shivansh.rollcall

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the last crash to a file the app can show on its next launch.
 *
 * Without a cable there is no logcat, so an uncaught exception otherwise leaves
 * nothing behind but a closed app. This keeps the stack trace and the device
 * details that usually matter for reproducing it.
 */
object CrashReporter {

    private const val FILE = "last_crash.txt"

    /** Recent pipeline milestones, included in the report to show how far it got. */
    private val breadcrumbs = ArrayDeque<String>()

    @Synchronized
    fun note(message: String) {
        breadcrumbs.addLast("${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}  $message")
        while (breadcrumbs.size > 40) breadcrumbs.removeFirst()
    }

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(context, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun lastReport(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        val runtime = Runtime.getRuntime()
        val report = buildString {
            appendLine("Roll Call crash report")
            appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine()
            appendLine("Device:  ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread:  ${thread.name}")
            appendLine(
                "Memory:  used ${(runtime.totalMemory() - runtime.freeMemory()) shr 20}MB / " +
                    "max ${runtime.maxMemory() shr 20}MB"
            )
            appendLine()
            if (breadcrumbs.isNotEmpty()) {
                appendLine("Last steps:")
                breadcrumbs.forEach { appendLine("  $it") }
                appendLine()
            }
            append(stack)
        }
        File(context.filesDir, FILE).writeText(report)
    }
}

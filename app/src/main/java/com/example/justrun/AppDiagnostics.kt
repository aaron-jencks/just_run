package com.example.justrun

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.aaronjencks.justrun.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class JustRunApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppDiagnostics.init(this)
        AppDiagnostics.log("phone process started")
    }
}

object AppDiagnostics {
    private const val TAG = "JustRunDiagnostics"
    private const val MAX_LOG_BYTES = 1_000_000L
    private const val DIAGNOSTICS_DIR = "diagnostics"
    private const val ACTIVITY_LOG = "activity.log"
    private const val CRASH_LOG = "crashes.log"

    private val lock = Any()
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var previousCrashHandler: Thread.UncaughtExceptionHandler? = null
    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            appContext = context.applicationContext
            previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                recordCrash(thread, throwable)
                previousCrashHandler?.uncaughtException(thread, throwable) ?: run {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
            initialized = true
        }
    }

    fun log(message: String) {
        Log.d(TAG, message)
        val context = appContext ?: return
        append(context, ACTIVITY_LOG, "${timestamp()} $message\n")
    }

    private fun recordCrash(thread: Thread, throwable: Throwable) {
        val context = appContext ?: return
        val body = buildString {
            appendLine()
            appendLine("${timestamp()} Uncaught exception")
            appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            appendLine("thread=${thread.name}")
            appendLine(throwable.stackTraceToString())
        }
        append(context, CRASH_LOG, body)
    }

    private fun append(context: Context, fileName: String, text: String) {
        synchronized(lock) {
            val file = diagnosticsFile(context, fileName)
            rotateIfNeeded(file)
            runCatching { file.appendText(text) }
        }
    }

    private fun diagnosticsFile(context: Context, fileName: String): File {
        val baseDir = context.getExternalFilesDir(DIAGNOSTICS_DIR)
            ?: File(context.filesDir, DIAGNOSTICS_DIR)
        if (!baseDir.exists()) baseDir.mkdirs()
        return File(baseDir, fileName)
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() < MAX_LOG_BYTES) return
        val backup = File(file.parentFile, "${file.name}.bak")
        if (backup.exists()) backup.delete()
        file.renameTo(backup)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US).format(Date())
}

package com.rusty.museecho.gadget

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The app's recent errors and crashes, kept in a small file so they survive
 * the crash itself; device.health reports them, so Muse (or a person asking
 * Muse) can tell when something on the Echo has gone wrong.
 */
class ErrorLog(private val file: File) {
    private val json = Json
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun add(message: String) {
        val list = (read() + "${stamp.format(Date())}  ${message.take(300)}").takeLast(MAX)
        runCatching { file.writeText(json.encodeToString(ListSerializer(String.serializer()), list)) }
    }

    @Synchronized
    fun read(): List<String> = runCatching {
        json.decodeFromString(ListSerializer(String.serializer()), file.readText())
    }.getOrDefault(emptyList())

    /**
     * Records uncaught exceptions before the process dies, and has the app
     * reopen itself shortly after, unless it crashed within the last minute
     * too (so a crash loop can't keep relaunching it).
     */
    fun installCrashHandler(context: android.content.Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            add("CRASH in ${thread.name}: ${e.javaClass.simpleName}: ${e.message} @ ${e.stackTrace.firstOrNull()}")
            runCatching { scheduleRelaunch(app) }
            previous?.uncaughtException(thread, e)
        }
    }

    companion object {
        const val MAX = 25
        private const val RELAUNCH_GAP_MS = 60_000L

        /** Reopen the app in two seconds, at most once a minute. */
        fun scheduleRelaunch(context: android.content.Context) {
            val prefs = context.getSharedPreferences("museecho", android.content.Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - prefs.getLong("last_crash_relaunch", 0) < RELAUNCH_GAP_MS) return
            prefs.edit().putLong("last_crash_relaunch", now).commit()
            val intent = android.content.Intent(context, com.rusty.museecho.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = android.app.PendingIntent.getActivity(context, 7, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            context.getSystemService(android.app.AlarmManager::class.java)
                .set(android.app.AlarmManager.RTC, now + 2_000, pending)
        }
    }

}

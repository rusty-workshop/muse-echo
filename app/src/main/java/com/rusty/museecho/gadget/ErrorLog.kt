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

    /** Records uncaught exceptions before the process dies. */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            add("CRASH in ${thread.name}: ${e.javaClass.simpleName}: ${e.message} @ ${e.stackTrace.firstOrNull()}")
            previous?.uncaughtException(thread, e)
        }
    }

    companion object {
        const val MAX = 25
    }
}

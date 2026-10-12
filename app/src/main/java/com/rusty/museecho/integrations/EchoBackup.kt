package com.rusty.museecho.integrations

import android.content.Context
import android.util.Base64
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Everything worth keeping if the Echo is reflashed: settings, pairing and
 * identity, notes, agenda, quick actions, timers, countdowns and the rest of
 * the small files in the app's private folder. Voice models and the wake-word
 * model are left out (they download again).
 */
object EchoBackup {
    private const val PREFS = "museecho"
    private const val MAX_FILE = 1024 * 1024
    /** Never restored: the backup key must stay this Echo's own, and the rest is runtime state. */
    private val KEEP_LOCAL = setOf("hub_backup_key", "ui_in_front", "last_backup_ms", "last_version_seen")

    /** Left out of a shareable export (the settings page's download): pairing, identity and tokens. */
    private val SECRET_FILES = setOf("identity.json", "pairing.json", "sdk_token", "errors.json")

    private fun secretPref(k: String) = "token" in k || k in KEEP_LOCAL || k.endsWith("_key") || k == "laptop_mac"

    /**
     * [shareable]: for the settings page's download (Puff's world and settings, nothing secret);
     * the nightly backup to the Latitude keeps everything.
     */
    fun collect(context: Context, versionName: String, shareable: Boolean = false): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.filterKeys { !shareable || !secretPref(it) }
        val files = context.filesDir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.startsWith("profileinstaller") && it.length() in 1..MAX_FILE }
            .filter { !shareable || it.name !in SECRET_FILES }
        return buildJsonObject {
            put("format", 1)
            put("shareable", shareable)
            put("app_version", versionName)
            put("made_at", System.currentTimeMillis())
            put("prefs", buildJsonObject {
                prefs.forEach { (k, v) ->
                    if (k in KEEP_LOCAL) return@forEach
                    when (v) {
                        is Boolean -> put(k, buildJsonObject { put("t", "b"); put("v", v) })
                        is Int -> put(k, buildJsonObject { put("t", "i"); put("v", v) })
                        is Long -> put(k, buildJsonObject { put("t", "l"); put("v", v) })
                        is Float -> put(k, buildJsonObject { put("t", "f"); put("v", v) })
                        is String -> put(k, buildJsonObject { put("t", "s"); put("v", v) })
                        is Set<*> -> put(k, buildJsonObject {
                            put("t", "set")
                            put("v", buildJsonArray { v.filterIsInstance<String>().forEach { add(JsonPrimitive(it)) } })
                        })
                    }
                }
            })
            put("files", buildJsonObject {
                files.forEach { f -> put(f.name, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)) }
            })
        }.toString()
    }

    /** Writes the backup's files and settings back. Returns how many files were restored. */
    fun apply(context: Context, bundle: String, shareable: Boolean = false): Int {
        val root = Json.parseToJsonElement(bundle) as JsonObject
        require((root["format"] as? JsonPrimitive)?.contentOrNull == "1") { "unknown backup format" }
        val files = root["files"] as? JsonObject ?: JsonObject(emptyMap())
        var n = 0
        files.forEach { (name, data) ->
            if ("/" in name || name.startsWith(".")) return@forEach
            // An import from a file never replaces this Echo's pairing or identity.
            if (shareable && name in SECRET_FILES) return@forEach
            val bytes = Base64.decode((data as JsonPrimitive).content, Base64.NO_WRAP)
            val tmp = File(context.filesDir, "$name.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(File(context.filesDir, name))
            n++
        }
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        (root["prefs"] as? JsonObject)?.forEach { (k, entry) ->
            if (k in KEEP_LOCAL || (shareable && secretPref(k))) return@forEach
            val o = entry as? JsonObject ?: return@forEach
            val v = o["v"] ?: return@forEach
            when ((o["t"] as? JsonPrimitive)?.contentOrNull) {
                "b" -> (v as JsonPrimitive).booleanOrNull?.let { edit.putBoolean(k, it) }
                "i" -> (v as JsonPrimitive).contentOrNull?.toIntOrNull()?.let { edit.putInt(k, it) }
                "l" -> (v as JsonPrimitive).contentOrNull?.toLongOrNull()?.let { edit.putLong(k, it) }
                "f" -> (v as JsonPrimitive).contentOrNull?.toFloatOrNull()?.let { edit.putFloat(k, it) }
                "s" -> (v as JsonPrimitive).contentOrNull?.let { edit.putString(k, it) }
                "set" -> edit.putStringSet(k, (v as JsonArray).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet())
            }
        }
        edit.commit()
        return n
    }
}

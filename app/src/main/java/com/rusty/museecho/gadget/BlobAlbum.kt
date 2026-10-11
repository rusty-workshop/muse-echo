package com.rusty.museecho.gadget

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One snapshot of the blob: the day, what it was doing, and its picture file (in the album folder). */
@Serializable
data class AlbumShot(val date: String, val caption: String, val file: String, val atMs: Long)

/** The blob's photo album: at most one snapshot a day, about three months kept. */
class BlobAlbum(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = kotlinx.serialization.builtins.ListSerializer(AlbumShot.serializer())
    private val index get() = File(dir, "album.json")

    fun load(): List<AlbumShot> = runCatching { json.decodeFromString(ser, index.readText()) }.getOrDefault(emptyList())
        .filter { File(dir, it.file).isFile }

    fun file(shot: AlbumShot) = File(dir, shot.file)

    fun hasToday(): Boolean = load().any { it.date == java.time.LocalDate.now().toString() }

    /** Saves [jpeg] as today's snapshot; returns the new list. */
    fun add(jpeg: ByteArray, caption: String): List<AlbumShot> {
        dir.mkdirs()
        val date = java.time.LocalDate.now().toString()
        val name = "$date.jpg"
        File(dir, name).writeBytes(jpeg)
        var list = load().filter { it.date != date } + AlbumShot(date, caption.take(80), name, System.currentTimeMillis())
        // Oldest pictures go after about three months.
        while (list.size > 90) {
            File(dir, list.first().file).delete()
            list = list.drop(1)
        }
        index.writeText(json.encodeToString(ser, list))
        return list
    }
}

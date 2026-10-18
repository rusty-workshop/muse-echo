package com.rusty.museecho.gadget

import android.content.Context

/** Something the blob wears or holds. */
enum class Accessory {
    NONE, PARTY_HAT, SANTA_HAT, WITCH_HAT, SUNGLASSES, SCARF, HEADPHONES, CROWN, BOW, FLOWER, POPCORN,
    // The monthly costumes.
    EARMUFFS, HEART_GLASSES, CLOVER_HAT, BUNNY_EARS, FLOWER_CROWN, SUNHAT, STAR_GLASSES, SNORKEL, GRAD_CAP,
    SCARECROW_HAT, LEAF_CROWN, ANTLERS,
}

/**
 * Pixel's choice of look (blob.style): a colour and/or an accessory, until
 * [untilMs] (0 = until changed). Null fields mean "automatic".
 */
data class BlobStyle(val color: Long? = null, val accessory: Accessory? = null, val untilMs: Long = 0) {
    fun active(now: Long) = untilMs == 0L || now < untilMs
}

object BlobStyles {
    val COLORS = linkedMapOf(
        "violet" to 0xFF8B6CFFL, "teal" to 0xFF45D3C7L, "coral" to 0xFFFF7A8AL, "amber" to 0xFFFFC266L,
        "green" to 0xFF6EDB8FL, "blue" to 0xFF5AA9FFL, "pink" to 0xFFFF8FD8L, "red" to 0xFFFF5A5AL,
        "orange" to 0xFFFF9F4AL, "yellow" to 0xFFFFE066L, "white" to 0xFFF3EEF8L,
    )

    /** A colour name from [COLORS] or #RRGGBB; null if it's neither. */
    fun parseColor(s: String): Long? {
        val t = s.trim().lowercase()
        COLORS[t]?.let { return it }
        val hex = t.removePrefix("#")
        if (hex.length != 6) return null
        return hex.toLongOrNull(16)?.let { 0xFF000000L or it }
    }

    private const val KEY = "blob_style"

    fun save(context: Context, style: BlobStyle) {
        context.getSharedPreferences("museecho", Context.MODE_PRIVATE).edit()
            .putString(KEY, "${style.color ?: ""}|${style.accessory?.name ?: ""}|${style.untilMs}").apply()
    }

    fun load(context: Context): BlobStyle {
        val raw = context.getSharedPreferences("museecho", Context.MODE_PRIVATE).getString(KEY, null) ?: return BlobStyle()
        val parts = raw.split('|')
        if (parts.size != 3) return BlobStyle()
        return BlobStyle(
            color = parts[0].toLongOrNull(),
            accessory = Accessory.entries.firstOrNull { it.name == parts[1] },
            untilMs = parts[2].toLongOrNull() ?: 0,
        )
    }
}

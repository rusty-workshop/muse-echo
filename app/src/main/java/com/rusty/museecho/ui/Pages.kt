package com.rusty.museecho.ui

/**
 * The pages you swipe to from the clock, in their default order. The clock itself always comes
 * first; the rest can be reordered and hidden (Setup > Pages, or the laptop settings page).
 */
enum class HomePage(val id: Int, val label: String) {
    AGENDA(1, "Schedule"),
    FORECAST(3, "Forecast"),
    BLOB(8, "Blob"),
    TV(4, "TV remote"),
    LAPTOP(5, "Laptop"),
    SPOTIFY(7, "Spotify"),
    HOMELAB(2, "Homelab"),
    ;

    companion object {
        /** [saved] names in the chosen order; anything missing (new pages) goes at its default place. */
        fun ordered(saved: List<String>): List<HomePage> {
            val known = saved.mapNotNull { n -> entries.firstOrNull { it.name == n } }.distinct()
            val missing = entries.filter { it !in known }
            val out = known.toMutableList()
            missing.forEach { m -> out.add(entries.indexOf(m).coerceAtMost(out.size), m) }
            return out
        }
    }
}

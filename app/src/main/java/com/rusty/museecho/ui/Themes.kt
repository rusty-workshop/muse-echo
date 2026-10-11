package com.rusty.museecho.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** The colours the whole app draws with. */
internal data class ThemeColors(
    val night: Color, val dusk: Color, val ink: Color, val muted: Color,
    val violet: Color, val coral: Color, val teal: Color, val amber: Color, val card: Color,
)

/** Whole-app colour themes (Setup > Theme, or the laptop settings page). */
enum class AppTheme(val label: String, internal val colors: ThemeColors) {
    MIDNIGHT("Midnight", ThemeColors(
        night = Color(0xFF0E0B14), dusk = Color(0xFF1B1526), ink = Color(0xFFF3EEF8), muted = Color(0xFF9C93AB),
        violet = Color(0xFF8B6CFF), coral = Color(0xFFFF7A8A), teal = Color(0xFF45D3C7), amber = Color(0xFFFFC266), card = Color(0xFF231C30))),
    FOREST("Forest", ThemeColors(
        night = Color(0xFF0B1410), dusk = Color(0xFF13241B), ink = Color(0xFFEEF5EF), muted = Color(0xFF8FA597),
        violet = Color(0xFF5FBF7F), coral = Color(0xFFFF8A7A), teal = Color(0xFF7FD8C4), amber = Color(0xFFF2C66D), card = Color(0xFF1A2E23))),
    SUNSET("Sunset", ThemeColors(
        night = Color(0xFF160B14), dusk = Color(0xFF2A1220), ink = Color(0xFFFBEFF2), muted = Color(0xFFB293A2),
        violet = Color(0xFFFF7AA8), coral = Color(0xFFFF6F5E), teal = Color(0xFF7FC8D8), amber = Color(0xFFFFC266), card = Color(0xFF2B1626))),
    OCEAN("Ocean", ThemeColors(
        night = Color(0xFF07121E), dusk = Color(0xFF0D2033), ink = Color(0xFFEAF4FA), muted = Color(0xFF8AA3B5),
        violet = Color(0xFF4FA3FF), coral = Color(0xFFFF7A8A), teal = Color(0xFF45D3C7), amber = Color(0xFFFFD58A), card = Color(0xFF13283D))),
    MONO("Mono", ThemeColors(
        night = Color(0xFF000000), dusk = Color(0xFF111111), ink = Color(0xFFFFFFFF), muted = Color(0xFFB0B0B0),
        violet = Color(0xFFE6E6E6), coral = Color(0xFFFF6B6B), teal = Color(0xFFCFCFCF), amber = Color(0xFFFFD166), card = Color(0xFF1C1C1C))),
    /** Follows the laptop's ii dots colours (tools/desktop-sync); Midnight until they arrive. */
    LAPTOP_COLOURS("Laptop colours", MIDNIGHT.colors),
    ;

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: MIDNIGHT
    }
}

/**
 * The app's colours from the laptop's matugen scheme. Accents map to the Material roles that
 * play the same part: primary for the main accent, tertiary for the second, error for warnings,
 * and the light primary-container text for headings. Null if too few colours came over.
 */
internal fun desktopTheme(c: Map<String, String>): ThemeColors? {
    fun col(vararg keys: String): Color? = keys.firstNotNullOfOrNull { k ->
        c[k]?.removePrefix("#")?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
    }
    return ThemeColors(
        night = col("surface_container_lowest", "background") ?: return null,
        dusk = col("surface_container_low", "background") ?: return null,
        ink = col("on_surface") ?: return null,
        muted = col("on_surface_variant") ?: return null,
        violet = col("primary") ?: return null,
        coral = col("error") ?: Color(0xFFFF7A8A),
        teal = col("tertiary", "primary") ?: return null,
        amber = col("on_primary_container", "primary") ?: return null,
        card = col("surface_container_high", "surface_container_low") ?: return null,
    )
}

/**
 * The current theme's colours. Backed by Compose state, so changing the theme
 * redraws everything that uses them.
 */
internal object Palette {
    var theme by mutableStateOf(AppTheme.MIDNIGHT.colors)
    val night get() = theme.night
    val dusk get() = theme.dusk
    val ink get() = theme.ink
    val muted get() = theme.muted
    val violet get() = theme.violet
    val coral get() = theme.coral
    val teal get() = theme.teal
    val amber get() = theme.amber
    val card get() = theme.card
}

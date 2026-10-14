package com.rusty.museecho.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.rusty.museecho.gadget.GadgetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What sits behind the home screen (Setup > Background). */
enum class AppBackground(val label: String, val blurb: String) {
    PLAIN("Plain", "The theme's solid dark colour"),
    AURORA("Aurora", "Soft coloured light drifting very slowly"),
    CLOUDS("Clouds", "Faint clouds sliding across"),
    PHOTO("Photo", "One of your frame photos, blurred and dimmed, changing hourly"),
    LAPTOP_WALLPAPER("Laptop wallpaper", "The laptop's current wallpaper, blurred and dimmed"),
    LAVA("Lava lamp", "Big soft blobs of colour slowly rising, merging and sinking"),
    ;

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: PLAIN
    }
}

private const val TAU = (2 * PI).toFloat()

/**
 * The animated background. Motion this slow only needs a few frames a second, so it
 * redraws at 8 fps; it sits under everything and is drawn in the theme's colours.
 */
@Composable
internal fun AppBackgroundView(style: AppBackground, actions: UiActions) {
    when (style) {
        AppBackground.PLAIN -> {}
        AppBackground.AURORA, AppBackground.CLOUDS, AppBackground.LAVA -> {
            var t by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(style) {
                val start = System.currentTimeMillis()
                while (true) {
                    t = (System.currentTimeMillis() - start) / 1000f
                    awaitBeat(120)
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                if (style == AppBackground.LAVA) {
                    // Blobs rising and sinking on slow cycles; where they overlap they glow together.
                    val cols = listOf(Palette.violet, Palette.coral, Palette.amber, Palette.teal, Palette.violet)
                    cols.forEachIndexed { i, col ->
                        val cycle = 0.006f + i * 0.0013f
                        val ph = (t * cycle + i * 0.23f) % 1f
                        // Up and back down, lingering at the ends like warm wax.
                        val h = (1f - cos(ph * TAU)) / 2f
                        val x = size.width * (0.15f + 0.7f * ((i * 0.618f) % 1f)) + sin(t * 0.03f + i) * 40f
                        val y = size.height * (1.05f - 1.1f * h)
                        val r = size.minDimension * (0.28f + 0.08f * sin(t * 0.05f + i * 2f))
                        drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.26f), col.copy(alpha = 0.10f), Color.Transparent), Offset(x, y), r), r, Offset(x, y))
                    }
                } else if (style == AppBackground.AURORA) {
                    // Three big, soft glows on slow, different orbits.
                    val glows = listOf(
                        Triple(Palette.violet, 0.021f, 0f),
                        Triple(Palette.teal, 0.017f, 2.1f),
                        Triple(if (Palette.theme == AppTheme.MONO.colors) Palette.muted else Palette.coral, 0.013f, 4.2f),
                    )
                    glows.forEach { (color, speed, phase) ->
                        val a = t * speed * TAU + phase
                        val c = Offset(size.width * (0.5f + 0.38f * cos(a)), size.height * (0.5f + 0.42f * sin(a * 1.3f)))
                        val r = size.minDimension * (0.75f + 0.1f * sin(a * 0.7f))
                        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.16f), Color.Transparent), c, r), r, c)
                    }
                } else {
                    // Faint clouds sliding left to right at different speeds and heights.
                    for (i in 0 until 6) {
                        val speed = 6f + i * 2.3f
                        val span = size.width + size.minDimension
                        val x = ((t * speed + i * 377f) % span) - size.minDimension / 2
                        val y = size.height * (0.15f + (i * 0.618f % 1f) * 0.7f) + sin(t * 0.05f + i) * 20f
                        val r = size.minDimension * (0.35f + (i % 3) * 0.12f)
                        drawCircle(Brush.radialGradient(listOf(Palette.ink.copy(alpha = 0.045f), Color.Transparent), Offset(x, y), r), r, Offset(x, y))
                    }
                }
            }
        }
        AppBackground.PHOTO -> {
            var photo by remember { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(Unit) {
                while (true) {
                    photo = withContext(Dispatchers.IO) { actions.framePhotos().randomOrNull()?.let(::blurred) } ?: photo
                    delay(60 * 60_000L)
                }
            }
            BlurredPhoto(photo)
        }
        AppBackground.LAPTOP_WALLPAPER -> {
            // Redecoded whenever the laptop sends a new one.
            val wp by GadgetState.desktopWallpaper.collectAsState()
            var photo by remember { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(wp) { photo = withContext(Dispatchers.IO) { wp?.first?.let(::blurred) } ?: photo }
            BlurredPhoto(photo)
        }
    }
}

/** Shrunk hard and stretched back up: a soft blur without RenderEffect (Android 11). */
private fun blurred(f: java.io.File): ImageBitmap? = runCatching {
    val bmp = android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })
        ?: return@runCatching null
    android.graphics.Bitmap.createScaledBitmap(bmp, 40, (40f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true).asImageBitmap()
}.getOrNull()

@Composable
private fun BlurredPhoto(photo: ImageBitmap?) {
    Crossfade(targetState = photo, animationSpec = tween(3000), label = "bgPhoto") { p ->
        if (p != null) Box(Modifier.fillMaxSize()) {
            Image(p, null, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.15f; scaleY = 1.15f },
                contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
            Box(Modifier.fillMaxSize().background(Palette.night.copy(alpha = 0.62f)))
        }
    }
}

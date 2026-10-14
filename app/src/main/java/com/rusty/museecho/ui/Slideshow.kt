package com.rusty.museecho.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rusty.museecho.gadget.GadgetState
import com.rusty.museecho.gadget.Prefs
import com.rusty.museecho.gadget.Talk
import java.io.File
import java.util.Calendar
import java.util.Date
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One photo on screen: the picture, a blurred copy for filling around photos that
 * don't match the screen's shape, and its slow pan-and-zoom path.
 */
private class Slide(
    val file: File,
    val photo: ImageBitmap,
    val backdrop: ImageBitmap?,
    val zoomFrom: Float, val zoomTo: Float,
    val panX: Float, val panY: Float,
)

/** Photos in a shuffled order: hidden ones never, favourites twice as often, no repeat back to back. */
internal fun frameOrder(all: List<File>, favs: Set<String>, hidden: Set<String>, last: String?): List<File> {
    val shown = all.filter { it.name !in hidden }
    val weighted = (shown + shown.filter { it.name in favs }).shuffled().toMutableList()
    // Spread the favourites' second copies apart, and don't start with the photo just shown.
    val out = ArrayList<File>(weighted.size)
    while (weighted.isNotEmpty()) {
        val pick = weighted.indexOfFirst { it.name != (out.lastOrNull()?.name ?: last) }.let { if (it < 0) 0 else it }
        out += weighted.removeAt(pick)
    }
    return out
}

private const val SCREEN_RATIO = 2f // the Echo Show 5 is 960 x 480
private const val SLIDE_MS = 30_000L

private suspend fun loadSlide(file: File): Slide? = withContext(Dispatchers.IO) {
    val bmp = runCatching { com.rusty.museecho.gadget.EchoCommands.decodeScaled(file.readBytes()) }.getOrNull() ?: return@withContext null
    val ratio = bmp.width.toFloat() / bmp.height
    // Far from the screen's shape (portrait, square): show it whole over a blurred copy of itself.
    val backdrop = if (abs(ratio - SCREEN_RATIO) / SCREEN_RATIO > 0.25f) {
        // Shrinking hard and stretching back up with smooth filtering makes a soft blur (no RenderEffect on Android 11).
        val w = 24
        Bitmap.createScaledBitmap(bmp, w, (w / ratio).toInt().coerceAtLeast(1), true).asImageBitmap()
    } else null
    val zoomIn = Math.random() < 0.5
    Slide(
        file, bmp.asImageBitmap(), backdrop,
        zoomFrom = if (zoomIn) 1.0f else 1.1f, zoomTo = if (zoomIn) 1.1f else 1.0f,
        panX = (Math.random().toFloat() - 0.5f) * 0.06f, panY = (Math.random().toFloat() - 0.5f) * 0.04f,
    )
}

/** Evenings and dark rooms: dimmer and warmer, so the frame doesn't light up the room. */
private fun nightNow(): Boolean {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return h >= 20 || h < 6 || GadgetState.roomDark.value == true
}

private val nightFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    0.78f, 0f, 0f, 0f, 0f,
    0f, 0.62f, 0f, 0f, 0f,
    0f, 0f, 0.44f, 0f, 0f,
    0f, 0f, 0f, 1f, 0f,
)))

@Composable
internal fun SlideshowContent(actions: UiActions, now: Date) {
    val ctx = LocalContext.current
    val favs by GadgetState.frameFavs.collectAsState()
    val hidden by GadgetState.frameHidden.collectAsState()
    var slide by remember { mutableStateOf<Slide?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var skip by remember { mutableStateOf(0) }
    val marks = rememberUpdatedState(favs to hidden)
    val menu = rememberUpdatedState(menuOpen)
    val skipNow = rememberUpdatedState(skip)

    LaunchedEffect(Unit) {
        val queue = ArrayDeque<File>()
        var lastSkip = skip
        while (true) {
            val (f, h) = marks.value
            if (queue.isEmpty()) queue.addAll(frameOrder(actions.framePhotos(), f, h, slide?.file?.name))
            val next = generateSequence { queue.removeFirstOrNull() }.firstOrNull { it.name !in h && it.isFile }
            if (next == null) {
                if (slide == null) {
                    GadgetState.showFrame(false)
                    return@LaunchedEffect
                }
            } else {
                loadSlide(next)?.let { slide = it }
            }
            // Wait out the slide; a hide skips ahead, and the menu holds the photo still.
            var waited = 0L
            while (waited < SLIDE_MS || menu.value) {
                delay(250)
                waited += 250
                if (skipNow.value != lastSkip) { lastSkip = skipNow.value; break }
            }
            // Stop when something else needs the screen.
            if (GadgetState.talk.value != Talk.IDLE || GadgetState.ringing.value || GadgetState.quiet.value) {
                GadgetState.showFrame(false)
                return@LaunchedEffect
            }
        }
    }

    val night = remember(slide) { nightNow() }
    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTapGestures(
                // A tap ends the slideshow; press and hold for favourite / hide.
                onTap = { if (!menu.value) GadgetState.showFrame(false) },
                onLongPress = { if (slide != null) menuOpen = true },
            )
        },
    ) {
        Crossfade(targetState = slide, animationSpec = tween(1500), label = "frame") { s ->
            if (s != null) SlideView(s, night)
        }
        val style by GadgetState.frameClock.collectAsState()
        val weather by GadgetState.weather.collectAsState()
        Crossfade(targetState = FrameClock.of(style), animationSpec = tween(600), label = "frameClock") { fc ->
            Box(Modifier.graphicsLayer { alpha = if (night) 0.75f else 1f }) { FrameClockOverlay(fc, now, weather) }
        }
        val s = slide
        AnimatedVisibility(menuOpen && s != null, Modifier.align(Alignment.Center),
            enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.92f), exit = fadeOut(tween(150))) {
            val name = s?.file?.name.orEmpty()
            val isFav = name in favs
            Column(
                Modifier.width(300.dp).glass(RoundedCornerShape(20.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("This photo", color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                MenuRow(if (isFav) "★  Unfavourite" else "⭐  Favourite (shows more often)") {
                    val p = Prefs(ctx)
                    p.frameFavs = if (isFav) p.frameFavs - name else p.frameFavs + name
                    GadgetState.setFrameMarks(p.frameFavs, p.frameHidden)
                    menuOpen = false
                }
                MenuRow("🙈  Hide this photo") {
                    val p = Prefs(ctx)
                    p.frameHidden = p.frameHidden + name
                    p.frameFavs = p.frameFavs - name
                    GadgetState.setFrameMarks(p.frameFavs, p.frameHidden)
                    menuOpen = false
                    skip++
                }
                if (hidden.isNotEmpty()) {
                    MenuRow("↺  Show the ${hidden.size} hidden photo${if (hidden.size == 1) "" else "s"} again") {
                        val p = Prefs(ctx)
                        p.frameHidden = emptySet()
                        GadgetState.setFrameMarks(p.frameFavs, emptySet())
                        menuOpen = false
                    }
                }
                MenuRow("Cancel", Palette.muted) { menuOpen = false }
            }
        }
    }
}

@Composable
private fun MenuRow(text: String, color: Color = Palette.ink, onClick: () -> Unit) {
    Text(text, color = color, fontSize = 17.sp,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp))
}

/** The photo drifting and zooming very slowly over its 30 seconds (layer transforms only: cheap). */
@Composable
private fun SlideView(s: Slide, night: Boolean) {
    var p by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(s) {
        val start = System.nanoTime()
        while (p < 1f) {
            // 30 fps is plenty for motion this slow, and half the work of 60.
            delay(17)
            withFrameNanos { }
            p = ((System.nanoTime() - start) / 1e6f / (SLIDE_MS + 2_000)).coerceAtMost(1f)
        }
    }
    val filter = if (night) nightFilter else null
    fun Modifier.drift(strength: Float) = graphicsLayer {
        // Ease in and out so the motion never starts or stops with a jolt.
        val e = p * p * (3 - 2 * p)
        val z = 1f + (s.zoomFrom - 1f + (s.zoomTo - s.zoomFrom) * e) * strength
        scaleX = z; scaleY = z
        translationX = s.panX * size.width * (e - 0.5f) * strength
        translationY = s.panY * size.height * (e - 0.5f) * strength
    }
    Box(Modifier.fillMaxSize()) {
        if (s.backdrop != null) {
            Image(s.backdrop, null, Modifier.fillMaxSize().drift(1.3f), contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High, colorFilter = filter)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            Image(s.photo, "Photo", Modifier.fillMaxSize().drift(0.4f), contentScale = ContentScale.Fit, colorFilter = filter)
        } else {
            Image(s.photo, "Photo", Modifier.fillMaxSize().drift(1f), contentScale = ContentScale.Crop, colorFilter = filter)
        }
        if (night) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
    }
}

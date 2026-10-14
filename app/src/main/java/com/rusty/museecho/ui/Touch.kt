package com.rusty.museecho.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rusty.museecho.gadget.GadgetState
import com.rusty.museecho.gadget.Talk
import kotlinx.coroutines.delay

/** A button that squishes a little when pressed and springs back as you let go. */
internal fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMedium), label = "squish")
    this.graphicsLayer { scaleX = scale; scaleY = scale }.clickable(src, null, enabled = enabled, onClick = onClick)
}

/** The popup when the volume or brightness changes: a big icon and a level bar, gone after a moment. */
@Composable
internal fun LevelOverlay(modifier: Modifier = Modifier) {
    val popup by GadgetState.levelPopup.collectAsState()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(popup?.atMs) {
        if (popup == null) return@LaunchedEffect
        visible = true
        delay(1_600)
        visible = false
    }
    val p = popup ?: return
    val level by animateFloatAsState(p.level, tween(180), label = "level")
    AnimatedVisibility(visible, modifier, enter = fadeIn(tween(150)) + scaleIn(tween(200), initialScale = 0.9f), exit = fadeOut(tween(350))) {
        Row(Modifier.width(320.dp).glass(RoundedCornerShape(28.dp), strength = 1.6f).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val icon = when {
                p.kind == "brightness" -> if (p.level < 0.35f) "🔅" else "🔆"
                p.level == 0f -> "🔇"
                p.level < 0.4f -> "🔈"
                p.level < 0.75f -> "🔉"
                else -> "🔊"
            }
            Text(icon, fontSize = 30.sp)
            Box(Modifier.weight(1f).padding(horizontal = 14.dp).height(12.dp).background(Palette.night.copy(alpha = 0.5f), RoundedCornerShape(50))) {
                Box(Modifier.fillMaxWidth(level).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Palette.teal, if (p.kind == "brightness") Palette.amber else Palette.violet)), RoundedCornerShape(50)))
            }
            Text("${(level * 100).toInt()}", color = Palette.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A chunky slider: a tall rounded track filling with colour, dragged or tapped anywhere along it. */
@Composable
internal fun ChunkySlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, colors: List<Color> = listOf(Palette.teal, Palette.violet)) {
    var width by remember { mutableFloatStateOf(1f) }
    fun at(x: Float) = (x / width).coerceIn(0f, 1f)
    Box(modifier.height(34.dp).onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
        .background(Palette.night.copy(alpha = 0.5f), RoundedCornerShape(50))
        .pointerInput(Unit) { detectTapGestures { onChange(at(it.x)) } }
        .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> onChange(at(change.position.x)) } }) {
        Box(Modifier.fillMaxWidth(value.coerceIn(0.06f, 1f)).fillMaxHeight().background(Brush.horizontalGradient(colors), RoundedCornerShape(50)))
        // The knob's highlight at the end of the fill.
        Canvas(Modifier.fillMaxSize()) {
            val x = (size.width * value.coerceIn(0.06f, 1f) - size.height / 2).coerceAtLeast(size.height / 2)
            drawCircle(Color.White.copy(alpha = 0.9f), size.height * 0.36f, Offset(x, size.height / 2))
        }
    }
}

/**
 * While you're talking to Pixel, the rest of the screen dims back, with a soft pool of light left
 * around Puff on the home screen; it comes back up when the conversation's done.
 */
@Composable
internal fun Spotlight() {
    val talk by GadgetState.talk.collectAsState()
    val speaking by GadgetState.speaking.collectAsState()
    val page by GadgetState.idlePage.collectAsState()
    val on = talk == Talk.LISTENING || talk == Talk.SENDING || talk == Talk.WAITING || speaking
    val a by animateFloatAsState(if (on) 1f else 0f, tween(if (on) 500 else 900), label = "spotlight")
    if (a <= 0.01f) return
    val puff = if (page == 0) GadgetState.blobBounds[0] else null
    Canvas(Modifier.fillMaxSize()) {
        val dark = Color.Black.copy(alpha = 0.5f * a)
        if (puff == null) drawRect(dark)
        else {
            val c = Offset(puff.exactCenterX(), puff.exactCenterY())
            val r = puff.width() * 0.85f
            // Clear around Puff, fading to dim; past the radius the gradient stays dim to the screen's edges.
            drawRect(Brush.radialGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 1f to dark, center = c, radius = r))
        }
    }
}

// -- Friendly trouble screens --------------------------------------------------------------

internal enum class Trouble { NOT_PAIRED, NO_PIXEL, NO_INTERNET }

/** A calm card for when something's wrong: a small drawing, what's happening, and what still works. */
@Composable
internal fun TroubleCard(kind: Trouble, name: String) {
    val clock = rememberIconClock()
    val (title, body) = when (kind) {
        Trouble.NOT_PAIRED -> "Not paired with $name yet" to "Open Setup (the gear, top right) to pair. The clock, timers and pages all work meanwhile."
        Trouble.NO_PIXEL -> "Can't reach $name right now" to "It keeps trying by itself. The clock, timers and offline answers still work."
        Trouble.NO_INTERNET -> "No internet" to "The Wi-Fi is up but the internet isn't. Offline answers still work."
    }
    Row(Modifier.padding(top = 4.dp).glass(RoundedCornerShape(16.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(64.dp).height(48.dp)) {
            val t = clock.value
            if (kind == Trouble.NO_INTERNET) {
                // A cloud with a dotted, broken line under it.
                val c = Offset(size.width / 2, size.height * 0.38f)
                for ((dx, r) in listOf(-10f to 9f, 2f to 12f, 13f to 8f)) drawCircle(Palette.muted.copy(alpha = 0.7f), r.dp.toPx() * 0.9f, c + Offset(dx.dp.toPx(), 0f))
                for (k in 0 until 4) if ((t * 2).toInt() % 4 != k) drawCircle(Palette.amber, 2.dp.toPx(), Offset(size.width * (0.2f + k * 0.2f), size.height * 0.85f))
            } else {
                // Two plugs reaching for each other, a little spark trying to jump the gap.
                val gap = 6.dp.toPx() + 3.dp.toPx() * kotlin.math.sin(t * 2f)
                val mid = size.height / 2
                drawRoundRect(Palette.muted, Offset(0f, mid - 7.dp.toPx()), androidx.compose.ui.geometry.Size(size.width / 2 - gap, 14.dp.toPx()),
                    androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                drawRoundRect(Palette.muted, Offset(size.width / 2 + gap, mid - 7.dp.toPx()), androidx.compose.ui.geometry.Size(size.width / 2 - gap, 14.dp.toPx()),
                    androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                for (k in listOf(-1f, 1f)) drawLine(Palette.muted, Offset(size.width / 2 - gap, mid + k * 3.dp.toPx()), Offset(size.width / 2 - gap + 4.dp.toPx(), mid + k * 3.dp.toPx()), 2.dp.toPx())
                if ((t * 3).toInt() % 3 == 0) drawCircle(Palette.amber, 2.5f.dp.toPx(), Offset(size.width / 2, mid))
            }
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, color = Palette.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = Palette.muted, fontSize = 13.sp, lineHeight = 17.sp)
        }
    }
}

// -- Breathing vignette --------------------------------------------------------------------

/** When nothing's happening for a minute, the corners darken very slightly and breathe. */
@Composable
internal fun Vignette() {
    val asleep by GadgetState.asleep.collectAsState()
    val talk by GadgetState.talk.collectAsState()
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            idle = System.currentTimeMillis() - GadgetState.lastTouch > 60_000 && talk == Talk.IDLE
            delay(5_000)
        }
    }
    val on by animateFloatAsState(if (idle && !asleep) 1f else 0f, tween(3_000), label = "vignette")
    if (on <= 0.01f) return
    // Slow breathing (8 s), stepped on the shared 200 ms beat: smooth enough at this speed, and cheap.
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { while (true) { awaitBeat(200); t = System.currentTimeMillis() / 1000f } }
    Canvas(Modifier.fillMaxSize()) {
        val breath = 0.5f + 0.5f * kotlin.math.sin(t * (2 * Math.PI.toFloat() / 8f))
        val a = on * (0.16f + 0.10f * breath)
        drawRect(Brush.radialGradient(0f to Color.Transparent, 0.62f to Color.Transparent, 1f to Color.Black.copy(alpha = a),
            center = Offset(size.width / 2, size.height / 2), radius = size.maxDimension * 0.62f))
    }
}

// -- Hold-to-talk ring -----------------------------------------------------------------------

/** When a press-and-hold on Puff started (0 = none), for the ring that fills before it listens. */
internal object HoldRing {
    var startMs by androidx.compose.runtime.mutableLongStateOf(0L)
    const val HOLD_MS = 380L
}

/** The ring filling around Puff while you hold it, until it starts listening. */
@Composable
internal fun HoldRingOverlay() {
    val start = HoldRing.startMs
    var p by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(start) {
        p = 0f
        if (start == 0L) return@LaunchedEffect
        while (p < 1f) {
            androidx.compose.runtime.withFrameMillis { }
            p = ((System.currentTimeMillis() - start) / HoldRing.HOLD_MS.toFloat()).coerceIn(0f, 1f)
        }
    }
    val puff = GadgetState.blobBounds[0] ?: return
    if (start == 0L) return
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(puff.exactCenterX(), puff.exactCenterY())
        val r = puff.width() * 0.36f
        drawCircle(Color.White.copy(alpha = 0.12f), r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(5.dp.toPx()))
        drawArc(Palette.coral, -90f, 360f * p, false, c - Offset(r, r), androidx.compose.ui.geometry.Size(r * 2, r * 2),
            style = androidx.compose.ui.graphics.drawscope.Stroke(5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

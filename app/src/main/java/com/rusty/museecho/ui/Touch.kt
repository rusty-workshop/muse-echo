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

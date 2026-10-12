package com.rusty.museecho.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import com.rusty.museecho.gadget.GadgetState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.rusty.museecho.gadget.Mood
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay

/** What the screen shows when it goes to sleep overnight (Setup > Night screen). */
enum class NightScene(val label: String, val blurb: String) {
    BLACK("Black", "Screen fully off"),
    STARS("Starry night", "{name} asleep among slowly drifting stars"),
    AQUARIUM("Aquarium", "{name} dozing in a dark, bubbly tank"),
    ;

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: BLACK
    }
}

private const val TAU = (2 * PI).toFloat()

/**
 * The overnight screen. Everything moves very slowly and the blob wanders across the screen
 * over a few minutes so nothing stays put long enough to mark the panel; it redraws only a
 * few times a second.
 */
@Composable
internal fun NightScreen(scene: NightScene, actions: UiActions) {
    if (scene == NightScene.BLACK) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            t = (System.currentTimeMillis() - start) / 1000f
            delay(125)
        }
    }
    Box(Modifier.fillMaxSize().background(if (scene == NightScene.STARS) Color(0xFF05040A) else Color(0xFF02101A))) {
        Canvas(Modifier.fillMaxSize()) {
            if (scene == NightScene.STARS) drawStars(t) else drawTank(t)
        }
        // The blob, asleep, drifting slowly around the screen (about 7 minutes a lap).
        val lap = (t / 420f) * TAU
        val dx = (sin(lap) * 300).dp
        val dy = (sin(lap * 2f) * 60 + if (scene == NightScene.AQUARIUM) sin(t * 0.6f) * 8 else 0f).dp
        Blob(Modifier.align(Alignment.Center).offset(dx, dy).size(150.dp), actions, mini = true,
            moodOverride = Mood.SLEEPY)
        // A sleepover: the friend snoozes beside it, and they share one dream.
        val visit by GadgetState.friend.collectAsState()
        val friends by GadgetState.friends.collectAsState()
        visit?.takeIf { it.activity == "sleepover" }?.let { v ->
            val f = friends.friends.firstOrNull { it.id == v.friendIds.firstOrNull() }
            val friendSize = (110 * (f?.p?.scale ?: 1f)).dp
            Blob(Modifier.align(Alignment.Center).offset(dx + 115.dp, dy + 20.dp).size(friendSize), actions, mini = true,
                moodOverride = Mood.SLEEPY, tintOverride = Color(f?.color ?: v.color), ownStyle = false)
            Canvas(Modifier.align(Alignment.Center).offset(dx + 55.dp, dy - 95.dp).size(90.dp, 60.dp)) {
                val puff = Color.White.copy(alpha = 0.12f)
                drawCircle(puff, size.height * 0.4f, Offset(size.width * 0.5f, size.height * 0.45f))
                drawCircle(puff, size.height * 0.3f, Offset(size.width * 0.25f, size.height * 0.55f))
                drawCircle(puff, size.height * 0.3f, Offset(size.width * 0.75f, size.height * 0.55f))
                // Both of them, holding hands under the stars.
                drawCircle(Color(0xFF8B6CFF).copy(alpha = 0.55f), size.height * 0.12f, Offset(size.width * 0.4f, size.height * 0.5f))
                drawCircle(Color(f?.color ?: v.color).copy(alpha = 0.55f), size.height * 0.1f, Offset(size.width * 0.6f, size.height * 0.52f))
                val twinkle = 0.3f + 0.3f * sin(t * 1.5f)
                drawCircle(Color.White.copy(alpha = twinkle), 1.5f, Offset(size.width * 0.3f, size.height * 0.2f))
                drawCircle(Color.White.copy(alpha = 0.6f - twinkle), 1.5f, Offset(size.width * 0.7f, size.height * 0.25f))
            }
        }
    }
}

private fun DrawScope.drawStars(t: Float) {
    // Fixed pseudo-random stars twinkling gently, the whole sky creeping sideways.
    val drift = (t * 2f) % size.width
    for (i in 0 until 70) {
        val x = ((i * 0.618034f * 997f) % size.width + drift) % size.width
        val y = (i * 0.414214f * 613f) % size.height
        val twinkle = 0.35f + 0.3f * sin(t * (0.4f + (i % 7) * 0.13f) + i)
        drawCircle(Color.White.copy(alpha = twinkle * 0.6f), if (i % 9 == 0) 2.2f else 1.3f, Offset(x, y))
    }
    // A thin moon in a corner that also drifts.
    val m = Offset(size.width * (0.85f - 0.05f * sin(t / 300f)), size.height * 0.18f)
    drawCircle(Color(0xFFE8E2C8).copy(alpha = 0.35f), 22f, m)
    drawCircle(Color(0xFF05040A), 20f, m + Offset(9f, -4f))
}

private fun DrawScope.drawTank(t: Float) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF06283A), Color(0xFF021019))))
    // Light rippling down from the surface.
    for (i in 0 until 4) {
        val x = size.width * (0.15f + i * 0.25f) + sin(t * 0.3f + i) * 30f
        drawRect(Brush.verticalGradient(listOf(Color(0xFF6EC6D8).copy(alpha = 0.05f), Color.Transparent)),
            Offset(x - 30f, 0f), androidx.compose.ui.geometry.Size(60f, size.height * 0.7f))
    }
    // Bubbles rising and wobbling.
    for (i in 0 until 18) {
        val speed = 18f + (i % 5) * 6f
        val y = size.height - ((t * speed + i * 97f) % (size.height + 40f))
        val x = (i * 0.618034f * 911f) % size.width + sin(t * 0.8f + i) * 8f
        drawCircle(Color(0xFF9FE3F0).copy(alpha = 0.22f), 2.5f + (i % 3), Offset(x, y),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
    }
    // A few slow fish silhouettes.
    for (i in 0 until 3) {
        val dir = if (i % 2 == 0) 1f else -1f
        val span = size.width + 120f
        val x = ((t * (12f + i * 5f) + i * 400f) % span).let { if (dir > 0) it - 60f else size.width + 60f - it }
        val y = size.height * (0.3f + 0.22f * i) + sin(t * 0.5f + i) * 10f
        val fish = Path().apply {
            moveTo(x + 18f * dir, y)
            quadraticTo(x, y - 9f, x - 14f * dir, y)
            quadraticTo(x, y + 9f, x + 18f * dir, y)
            moveTo(x - 12f * dir, y)
            lineTo(x - 24f * dir, y - 7f)
            lineTo(x - 24f * dir, y + 7f)
            close()
        }
        drawPath(fish, Color(0xFF2E6C7E).copy(alpha = 0.55f))
    }
    // Sand at the bottom.
    drawRect(Color(0xFF1A2420), Offset(0f, size.height - 18f), androidx.compose.ui.geometry.Size(size.width, 18f))
}

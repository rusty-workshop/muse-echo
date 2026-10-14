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
    STREET("Puff's street", "The street at night, windows going dark one by one"),
    AURORA("Aurora", "Slow green and violet curtains of light over a dark hill"),
    CAMPFIRE("Campfire", "{name} dozing by a softly crackling fire"),
    RAIN_WINDOW("Rain on the window", "Raindrops trickling down a dark window"),
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
    // Sleeping over at a friend's: both asleep in the friend's room (whatever the night scene).
    val trip by GadgetState.trip.collectAsState()
    val friendsData by GadgetState.friends.collectAsState()
    trip?.takeIf { it.overnight }?.let { t -> friendsData.friends.firstOrNull { it.id == t.friendId } }?.let { host ->
        AwaySleepover(host, actions)
        return
    }
    if (scene == NightScene.BLACK) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    if (scene == NightScene.STREET) {
        NightStreet()
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
    val bg = when (scene) {
        NightScene.STARS, NightScene.AURORA -> Color(0xFF05040A)
        NightScene.CAMPFIRE -> Color(0xFF080504)
        NightScene.RAIN_WINDOW -> Color(0xFF05070C)
        else -> Color(0xFF02101A)
    }
    Box(Modifier.fillMaxSize().background(bg)) {
        Canvas(Modifier.fillMaxSize()) {
            when (scene) {
                NightScene.STARS -> drawStars(t)
                NightScene.AURORA -> { drawStars(t); drawAurora(t) }
                NightScene.CAMPFIRE -> drawCampfire(t)
                NightScene.RAIN_WINDOW -> drawRainWindow(t)
                else -> drawTank(t)
            }
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

/**
 * The street after bedtime: everyone's houses under the stars, each window going dark at its own
 * time (sleepy friends first), Puff's with a soft nightlight. The whole street drifts a little so
 * nothing stays put on the panel.
 */
@Composable
private fun NightStreet() {
    val data by GadgetState.friends.collectAsState()
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            t = (System.currentTimeMillis() - start) / 1000f
            delay(500)
        }
    }
    val cal = java.util.Calendar.getInstance()
    // Minutes since 9pm (negative before; after midnight keeps counting).
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val sinceNine = ((if (h < 12) h + 24 else h) - 21) * 60 + cal.get(java.util.Calendar.MINUTE)
    val houses = listOf(Triple(Color(0xFF8B6CFF), 1.15f, true)) + data.friends.map { Triple(Color(it.color), it.scale, sinceNine < com.rusty.museecho.gadget.FriendsBrain.lightsOut(it)) }
    Box(Modifier.fillMaxSize().background(Color(0xFF04030A))) {
        Canvas(Modifier.fillMaxSize()) {
            drawStars(t)
            val drift = sin(t / 240f) * 24f
            val ground = size.height * 0.8f
            drawRect(Color(0xFF0E0C16), Offset(0f, ground), androidx.compose.ui.geometry.Size(size.width, size.height - ground))
            val n = houses.size
            val slot = (size.width - 80f) / n
            houses.forEachIndexed { i, (c, scale, lit) ->
                val w = (slot * 0.7f).coerceAtMost(110f) * (0.85f + 0.15f * scale)
                val wallH = w * 0.7f
                val x = 40f + slot * i + (slot - w) / 2 + drift
                val top = ground - wallH
                val wall = androidx.compose.ui.graphics.lerp(c, Color(0xFF07060C), 0.8f)
                drawRect(wall, Offset(x, top), androidx.compose.ui.geometry.Size(w, wallH))
                drawPath(Path().apply { moveTo(x - w * 0.08f, top); lineTo(x + w / 2, top - wallH * 0.6f); lineTo(x + w * 1.08f, top); close() },
                    androidx.compose.ui.graphics.lerp(c, Color(0xFF07060C), 0.88f))
                val win = Offset(x + w * 0.18f, top + wallH * 0.22f)
                val ws = androidx.compose.ui.geometry.Size(w * 0.32f, wallH * 0.32f)
                // Puff's window: a faint nightlight; friends': warm until their light goes out.
                val glow = when {
                    i == 0 -> Color(0xFF9AB8FF).copy(alpha = 0.18f + 0.04f * sin(t / 3f))
                    lit -> Color(0xFFFFD27A).copy(alpha = 0.55f)
                    else -> Color(0xFF15131F)
                }
                drawRect(glow, win, ws)
                if (i == 0 || lit) drawCircle(glow.copy(alpha = glow.alpha * 0.25f), ws.width * 1.2f, win + Offset(ws.width / 2, ws.height / 2))
            }
        }
    }
}

/** Puff asleep at a friend's house: the friend's own room, dimmed, both of them snoozing; drifting slowly. */
@Composable
private fun AwaySleepover(host: com.rusty.museecho.gadget.Friend, actions: UiActions) {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            t = (System.currentTimeMillis() - start) / 1000f
            delay(500)
        }
    }
    val c = Color(host.color)
    val dx = (sin(t / 300f) * 30).dp
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.align(Alignment.Center).offset(dx, 0.dp).size(620.dp, 300.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                drawInterior(host, c, 0)
                drawRect(Color.Black.copy(alpha = 0.6f))
            }
            Blob(Modifier.align(Alignment.BottomCenter).offset((-60).dp, (-10).dp).size(120.dp), actions, mini = true, moodOverride = Mood.SLEEPY)
            Blob(Modifier.align(Alignment.BottomCenter).offset(70.dp, (-10).dp).size((110 * host.scale).dp), actions, mini = true,
                moodOverride = Mood.SLEEPY, tintOverride = c, ownStyle = false)
        }
    }
}

/** Slow curtains of green and violet light rippling over a dark hill. Everything drifts so nothing stays put. */
private fun DrawScope.drawAurora(t: Float) {
    val drift = sin(t / 200f) * 30f
    for (band in 0 until 3) {
        val col = if (band == 1) Color(0xFF8B6CFF) else Color(0xFF3DE0A0)
        val baseY = size.height * (0.32f + band * 0.08f)
        val path = Path()
        val steps = 40
        for (k in 0..steps) {
            val x = size.width * k / steps + drift
            val y = baseY + sin(k * 0.35f + t * 0.12f + band) * 26f + sin(k * 0.13f - t * 0.07f) * 18f
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        for (k in steps downTo 0) {
            val x = size.width * k / steps + drift
            path.lineTo(x, baseY + 120f + sin(k * 0.2f + t * 0.1f) * 20f)
        }
        path.close()
        drawPath(path, Brush.verticalGradient(listOf(col.copy(alpha = 0.18f), Color.Transparent), baseY - 30f, baseY + 140f))
    }
    // The hill.
    val hill = Path().apply {
        moveTo(0f, size.height)
        lineTo(0f, size.height * 0.82f)
        quadraticTo(size.width * 0.35f + drift, size.height * 0.68f, size.width * 0.7f, size.height * 0.8f)
        quadraticTo(size.width * 0.85f, size.height * 0.84f, size.width, size.height * 0.78f)
        lineTo(size.width, size.height); close()
    }
    drawPath(hill, Color(0xFF020203))
}

/** A small fire with flickering flames and rising sparks, glowing warm on the ground. */
private fun DrawScope.drawCampfire(t: Float) {
    val c = Offset(size.width * (0.5f + 0.03f * sin(t / 240f)), size.height * 0.78f)
    val flicker = 0.85f + 0.15f * sin(t * 7f) * sin(t * 3.3f)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFF8A3D).copy(alpha = 0.22f * flicker), Color.Transparent), c, size.height * 0.5f), size.height * 0.5f, c)
    // Logs.
    drawLine(Color(0xFF3A2416), c + Offset(-40f, 12f), c + Offset(40f, 2f), 12f, androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(Color(0xFF2E1C10), c + Offset(-40f, 2f), c + Offset(40f, 12f), 12f, androidx.compose.ui.graphics.StrokeCap.Round)
    // Flames: a few teardrops swaying.
    for (k in 0 until 3) {
        val sway = sin(t * (2.2f + k) + k) * 6f
        val h = (46f - k * 10f) * flicker
        val base = c + Offset((k - 1) * 12f, 0f)
        val flame = Path().apply {
            moveTo(base.x - 12f + k * 2, base.y)
            quadraticTo(base.x - 14f + sway, base.y - h * 0.6f, base.x + sway, base.y - h)
            quadraticTo(base.x + 14f + sway, base.y - h * 0.6f, base.x + 12f - k * 2, base.y)
            close()
        }
        drawPath(flame, if (k == 1) Color(0xFFFFC266).copy(alpha = 0.9f) else Color(0xFFFF6A2A).copy(alpha = 0.8f))
    }
    // Sparks drifting up.
    for (k in 0 until 8) {
        val ph = ((t * 0.25f + k * 0.137f) % 1f)
        val p = c + Offset(sin(t + k * 2f) * 20f, -20f - ph * size.height * 0.45f)
        drawCircle(Color(0xFFFFC266).copy(alpha = (1f - ph) * 0.7f), 1.6f, p)
    }
}

/** Raindrops trickling down a dark window, a few city lights blurred far behind it. */
private fun DrawScope.drawRainWindow(t: Float) {
    // Faraway lights.
    for (k in 0 until 9) {
        val p = Offset((k * 0.618f * 997f + sin(t / 300f) * 20f) % size.width, size.height * (0.55f + 0.25f * ((k * 0.414f) % 1f)))
        val col = listOf(Color(0xFFFFC266), Color(0xFF8B6CFF), Color(0xFF45D3C7))[k % 3]
        drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.18f), Color.Transparent), p, 40f), 40f, p)
    }
    // Drops: each slides down slowly, then quickly, leaving a short trail.
    for (k in 0 until 26) {
        val speed = 0.03f + (k % 5) * 0.012f
        val ph = ((t * speed + k * 0.29f) % 1f)
        val eased = ph * ph
        val x = (k * 0.618f * 911f) % size.width
        val y = eased * (size.height + 40f) - 20f
        drawLine(Color(0xFFAFC8E8).copy(alpha = 0.12f), Offset(x, y - 40f * ph), Offset(x, y), 2f)
        drawCircle(Color(0xFFCFE0F5).copy(alpha = 0.35f), 2.6f + (k % 3), Offset(x, y))
    }
    // The window frame's cross.
    drawLine(Color(0xFF15131A), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 14f)
    drawLine(Color(0xFF15131A), Offset(0f, size.height * 0.45f), Offset(size.width, size.height * 0.45f), 14f)
}

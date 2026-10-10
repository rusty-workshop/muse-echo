package com.rusty.museecho.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import com.rusty.museecho.gadget.GadgetState
import com.rusty.museecho.gadget.Link
import com.rusty.museecho.gadget.Mood
import com.rusty.museecho.gadget.Reaction
import com.rusty.museecho.gadget.Talk
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay

private const val TAU = (2 * PI).toFloat()

/**
 * Pixel's on-screen body: a breathing orb with a face. It blinks, looks where
 * you tap, follows the real loudness of your voice and its own, thinks while
 * waiting, sleeps through quiet hours, frets when the server is down, shows
 * the weather, and now and then does something silly on its own.
 *
 * [lookAt] is a tap position in this composable's own pixels, made at [lookAtMs].
 */
@Composable
internal fun Blob(modifier: Modifier, actions: UiActions, lookAt: Offset?, lookAtMs: Long) {
    val quiet by GadgetState.quiet.collectAsState()
    val speaking by GadgetState.speaking.collectAsState()
    val talk by GadgetState.talk.collectAsState()
    val link by GadgetState.link.collectAsState()
    val server by GadgetState.server.collectAsState()
    val weather by GadgetState.weather.collectAsState()
    val moodSet by GadgetState.mood.collectAsState()
    val reaction by GadgetState.reaction.collectAsState()
    val alertGlow by GadgetState.alertGlow.collectAsState()

    var t by remember { mutableFloatStateOf(0f) }
    var mic by remember { mutableFloatStateOf(0f) }
    var voice by remember { mutableFloatStateOf(0f) }
    var nextBlink by remember { mutableFloatStateOf(3f) }
    var blinkAt by remember { mutableFloatStateOf(-1f) }

    val state = rememberUpdatedState(Triple(quiet, speaking, talk))
    val reactionNow = rememberUpdatedState(reaction)
    val glowNow = rememberUpdatedState(alertGlow)

    // One clock for everything. The frame rate follows what's on screen: smooth
    // while something moves fast, 12 fps for the slow breath, and a few frames
    // a second overnight just to float the z's.
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            val (q, s, tk) = state.value
            val now = System.currentTimeMillis()
            t = (now - start) / 1000f
            mic = if (tk == Talk.LISTENING) mic * 0.5f + (actions.micLevel() * 2.5f).coerceAtMost(1f) * 0.5f else mic * 0.6f
            voice = if (s) {
                val out = GadgetState.outputLevel()
                // System TTS gives no audio to measure: fall back to a syllable-rate flutter.
                val target = if (out >= 0f) out else (0.5f + 0.5f * sin(t * 17f)) * (0.6f + 0.4f * sin(t * 5.3f))
                voice * 0.45f + target * 0.55f
            } else voice * 0.5f
            if (t > nextBlink + 0.18f) {
                blinkAt = nextBlink
                // Mostly single blinks, sometimes a quick double.
                nextBlink = t + if ((t * 7.3f).toInt() % 5 == 0) 0.35f else 2.5f + ((t * 13.7f) % 3.5f)
            }
            val r = reactionNow.value
            val reacting = r != null && now - r.atMs < r.reaction.ms
            val fast = s || tk == Talk.LISTENING || tk == Talk.SENDING || tk == Talk.WAITING || reacting
            delay(
                when {
                    fast -> 40L
                    q -> 250L
                    glowNow.value -> 60L
                    else -> 83L
                }
            )
        }
    }

    // Quiet hours start with a yawn and end with a stretch.
    var wasQuiet by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(quiet) {
        val before = wasQuiet
        wasQuiet = quiet
        if (before == false && quiet) GadgetState.react(Reaction.YAWN)
        if (before == true && !quiet) GadgetState.react(Reaction.STRETCH)
    }

    // Idle antics: every 10-20 minutes, if nobody's around and nothing's going on.
    LaunchedEffect(Unit) {
        val antics = listOf(Reaction.HICCUP, Reaction.STRETCH, Reaction.PEEK, Reaction.WANDER, Reaction.HOP, Reaction.YAWN)
        while (true) {
            delay(600_000L + (Math.random() * 600_000).toLong())
            val (q, s, tk) = state.value
            val idleFor = System.currentTimeMillis() - GadgetState.lastTouch
            if (!q && !s && tk == Talk.IDLE && !GadgetState.ringing.value && idleFor > 60_000) {
                GadgetState.react(antics.random())
            }
        }
    }

    Canvas(modifier) {
        val nowMs = System.currentTimeMillis()
        val r = size.minDimension / 2
        val c = center

        // -- What it's feeling ----------------------------------------------------
        val active = reaction?.takeIf { nowMs - it.atMs < it.reaction.ms }
        val react = active?.reaction
        val p = active?.let { ((nowMs - it.atMs).toFloat() / it.reaction.ms).coerceIn(0f, 1f) } ?: 0f
        val serverDown = server?.overall == "down"
        val pixelMood = moodSet?.takeIf { it.untilMs > nowMs }?.mood
        val sleeping = quiet && !speaking && talk == Talk.IDLE
        var mood = when {
            pixelMood != null -> pixelMood
            sleeping -> Mood.SLEEPY
            talk == Talk.LISTENING -> Mood.CURIOUS
            talk == Talk.ERROR -> Mood.SAD
            serverDown && talk == Talk.IDLE && !speaking -> Mood.WORRIED
            else -> Mood.NEUTRAL
        }
        when (react) {
            Reaction.CELEBRATE, Reaction.STRETCH -> mood = Mood.HAPPY
            Reaction.WINK -> mood = Mood.WINK
            Reaction.YAWN -> mood = Mood.SLEEPY
            Reaction.HICCUP -> if (hiccupSpike(p) > 0.3f) mood = Mood.SURPRISED
            else -> {}
        }

        var tint = when {
            speaking -> Palette.teal
            talk == Talk.LISTENING -> Palette.coral
            talk == Talk.SENDING || talk == Talk.WAITING -> Palette.amber
            link == Link.CONNECTED -> Palette.violet
            else -> Palette.muted
        }
        if (serverDown && talk == Talk.IDLE && !speaking) tint = lerp(tint, Palette.coral, 0.55f)

        // -- How it moves ------------------------------------------------------------
        val breathe = 0.99f + 0.07f * sin(t * TAU / 7.2f) + 0.08f * voice + 0.12f * mic
        var dx = 0f
        var dy = 0f
        var sx = 1f
        var sy = 1f
        var lookX = 0f
        var lookY = 0f
        when (react) {
            Reaction.HOP, Reaction.CELEBRATE -> {
                val hops = if (react == Reaction.HOP) 2 else 3
                val phase = (p * hops) % 1f
                dy = -abs(sin(phase * PI.toFloat())) * r * 0.2f
                // Squash a little on each landing.
                val land = 1f - abs(sin(phase * PI.toFloat()))
                sy = 1f - 0.08f * land * land
                sx = 1f + 0.06f * land * land
                lookY = -0.5f
            }
            Reaction.NOD -> { dy = sin(p * TAU * 2) * r * 0.05f; lookY = 0.3f + 0.5f * sin(p * TAU * 2) }
            Reaction.SHAKE -> { dx = sin(p * TAU * 3) * r * 0.08f * (1 - p); lookX = sin(p * TAU * 3) }
            Reaction.YAWN -> { val o = sin(p * PI.toFloat()); sy = 1f + 0.08f * o; sx = 1f - 0.03f * o }
            Reaction.STRETCH -> { val o = sin(p * PI.toFloat()); sy = 1f + 0.15f * o; sx = 1f - 0.08f * o; dy = -o * r * 0.05f }
            Reaction.HICCUP -> { val k = hiccupSpike(p); sx = 1f + 0.09f * k; sy = 1f + 0.09f * k; dy = -k * r * 0.05f }
            Reaction.PEEK -> { lookX = sin(p * TAU) ; lookY = -0.2f }
            Reaction.WANDER -> {
                dx = sin(p * TAU) * r * 0.35f
                dy = sin(p * TAU * 3) * r * 0.04f
                lookX = cos(p * TAU).coerceIn(-1f, 1f)
            }
            else -> {}
        }
        // Thinking: eyes drift up and to the side.
        if (react == null && (talk == Talk.SENDING || talk == Talk.WAITING)) { lookX = 0.6f; lookY = -0.7f }
        // A recent tap pulls the eyes towards it.
        if (lookAt != null && nowMs - lookAtMs < 2_500 && react == null) {
            val v = lookAt - c
            val len = sqrt(v.x * v.x + v.y * v.y).coerceAtLeast(1f)
            val reach = (len / r).coerceAtMost(1f)
            lookX = v.x / len * reach
            lookY = v.y / len * reach
        }

        val drift = (t / 14f) % 1f
        val angle = drift * TAU
        val shift = Offset(cos(angle) * r * 0.18f, sin(angle) * r * 0.18f)
        val body = c + Offset(dx, dy)

        // -- Behind the body: weather and the alert glow ---------------------------------
        drawWeather(weather?.summary, body, r, t)
        if (alertGlow) {
            val a = 0.22f + 0.18f * sin(t * 3f)
            drawCircle(Brush.radialGradient(listOf(Color.Transparent, Palette.amber.copy(alpha = a), Color.Transparent), body, r * 1.05f), r * 1.05f, body)
        }

        // -- The body ----------------------------------------------------------------
        withTransform({
            translate(dx, dy)
            scale(breathe * sx, breathe * sy, Offset(c.x, c.y + r * 0.5f))
        }) {
            drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.35f), Color.Transparent), c, r), r, c)
            drawCircle(Brush.radialGradient(listOf(Palette.teal.copy(alpha = 0.55f), Color.Transparent), c + shift, r * 0.7f), r * 0.62f, c + shift)
            val core = c - shift * 0.6f
            drawCircle(Brush.radialGradient(listOf(tint, tint.copy(alpha = 0.15f)), core, r * 0.55f), r * 0.48f, core)

            // Thinking: three dots circling the core.
            if (talk == Talk.SENDING || talk == Talk.WAITING) {
                for (i in 0 until 3) {
                    val a = t * 2.4f + i * TAU / 3
                    val pos = core + Offset(cos(a) * r * 0.66f, sin(a) * r * 0.66f * 0.55f)
                    drawCircle(Palette.amber.copy(alpha = 0.55f + 0.35f * sin(t * 3f + i)), r * 0.04f, pos)
                }
            }

            val blinking = t >= blinkAt && t < blinkAt + 0.16f
            drawFace(core + Offset(0f, -r * 0.03f), r, mood, Offset(lookX, lookY), blinking, voice, react, p, t)
        }

        // -- In front: confetti --------------------------------------------------------
        if (react == Reaction.CELEBRATE) drawConfetti(body, r, (nowMs - active!!.atMs) / 1000f, p)
    }
}

/** Two sharp bumps over the reaction: hic... hic. */
private fun hiccupSpike(p: Float): Float {
    fun bump(at: Float) = (1f - abs(p - at) / 0.08f).coerceAtLeast(0f)
    return maxOf(bump(0.2f), bump(0.65f))
}

private val eyeInk = Palette.night.copy(alpha = 0.88f)

private fun DrawScope.drawFace(
    at: Offset, r: Float, mood: Mood, look: Offset, blinking: Boolean,
    voice: Float, react: Reaction?, p: Float, t: Float,
) {
    val gap = r * 0.17f
    val w = r * 0.075f
    val h = r * 0.14f
    val stroke = Stroke(width = r * 0.04f, cap = StrokeCap.Round)
    val lk = Offset(look.x * r * 0.06f, look.y * r * 0.05f)
    val left = at + Offset(-gap, 0f) + lk
    val right = at + Offset(gap, 0f) + lk

    fun oval(center: Offset, scale: Float = 1f, squash: Float = 1f) {
        val ew = w * scale
        val eh = (if (blinking) h * 0.12f else h * squash) * scale
        drawOval(eyeInk, Offset(center.x - ew, center.y - eh), Size(ew * 2, eh * 2))
    }
    fun happyArc(center: Offset) {
        val path = Path().apply {
            moveTo(center.x - w * 1.2f, center.y + h * 0.25f)
            quadraticTo(center.x, center.y - h * 0.9f, center.x + w * 1.2f, center.y + h * 0.25f)
        }
        drawPath(path, eyeInk, style = stroke)
    }
    fun closedArc(center: Offset) {
        val path = Path().apply {
            moveTo(center.x - w * 1.2f, center.y)
            quadraticTo(center.x, center.y + h * 0.6f, center.x + w * 1.2f, center.y)
        }
        drawPath(path, eyeInk, style = stroke)
    }
    // Brows with the inner ends raised: worried or sad.
    fun brows(lift: Float) {
        drawLine(eyeInk, left + Offset(-w * 1.3f, -h * 1.15f), left + Offset(w * 1.1f, -h * (1.15f + lift)), r * 0.03f, StrokeCap.Round)
        drawLine(eyeInk, right + Offset(w * 1.3f, -h * 1.15f), right + Offset(-w * 1.1f, -h * (1.15f + lift)), r * 0.03f, StrokeCap.Round)
    }
    val mouth = at + Offset(lk.x * 0.6f, h * 1.55f)
    fun smile(width: Float, depth: Float) {
        val path = Path().apply {
            moveTo(mouth.x - width, mouth.y)
            quadraticTo(mouth.x, mouth.y + depth, mouth.x + width, mouth.y)
        }
        drawPath(path, eyeInk, style = Stroke(width = r * 0.03f, cap = StrokeCap.Round))
    }

    when (mood) {
        Mood.NEUTRAL -> { oval(left); oval(right) }
        Mood.HAPPY -> { happyArc(left); happyArc(right); smile(w * 1.4f, h * 0.7f) }
        Mood.CURIOUS -> { oval(left, 1.22f); oval(right, 0.92f) }
        Mood.WORRIED -> { oval(left); oval(right); brows(0.45f); smile(w * 0.9f, -h * 0.2f) }
        Mood.SAD -> { oval(left, squash = 0.8f); oval(right, squash = 0.8f); brows(0.55f); smile(w * 1.1f, -h * 0.55f) }
        Mood.SURPRISED -> {
            drawCircle(eyeInk, w * 1.35f, left)
            drawCircle(eyeInk, w * 1.35f, right)
            drawOval(eyeInk, Offset(mouth.x - w * 0.6f, mouth.y - h * 0.15f), Size(w * 1.2f, h * 0.9f))
        }
        Mood.WINK -> { happyArc(left); oval(right); smile(w * 1.2f, h * 0.55f) }
        Mood.SLEEPY -> {
            closedArc(left); closedArc(right)
            if (react == Reaction.YAWN) {
                val o = sin(p * PI.toFloat())
                drawOval(eyeInk, Offset(mouth.x - w * 0.9f * o, mouth.y - h * 0.2f), Size(w * 1.8f * o, h * 1.4f * o))
            } else {
                drawZs(at + Offset(r * 0.32f, -r * 0.3f), r, t)
            }
        }
    }
    // Talking: the mouth opens with the actual loudness of the voice.
    if (voice > 0.04f && mood != Mood.SURPRISED && react != Reaction.YAWN) {
        val open = h * (0.15f + 0.85f * voice)
        drawOval(eyeInk, Offset(mouth.x - w * 0.8f, mouth.y - open * 0.3f), Size(w * 1.6f, open))
    }
}

/** Three z's drifting up and fading, on a loop. */
private fun DrawScope.drawZs(from: Offset, r: Float, t: Float) {
    for (i in 0 until 3) {
        val phase = ((t / 4.5f) + i / 3f) % 1f
        val size = r * (0.06f + 0.05f * phase)
        val pos = from + Offset(phase * r * 0.25f + sin(phase * TAU) * r * 0.03f, -phase * r * 0.45f)
        val alpha = (if (phase < 0.2f) phase / 0.2f else 1f - (phase - 0.2f) / 0.8f) * 0.8f
        val ink = Palette.ink.copy(alpha = alpha)
        val path = Path().apply {
            moveTo(pos.x - size, pos.y - size)
            lineTo(pos.x + size, pos.y - size)
            lineTo(pos.x - size, pos.y + size)
            lineTo(pos.x + size, pos.y + size)
        }
        drawPath(path, ink, style = Stroke(width = r * 0.022f, cap = StrokeCap.Round))
    }
}

/** Rain, snow, sun or storm around the blob, from the glance weather. */
private fun DrawScope.drawWeather(summary: String?, c: Offset, r: Float, t: Float) {
    val s = summary?.lowercase() ?: return
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val daytime = hour in 7..18
    when {
        "rain" in s || "drizzle" in s || "shower" in s || "thunder" in s -> {
            val heavy = "heavy" in s || "thunder" in s
            val drops = if (heavy) 11 else 7
            val speed = if ("drizzle" in s) 0.35f else 0.6f
            for (i in 0 until drops) {
                val x = ((i * 0.618f) % 1f) * 1.7f - 0.85f
                val phase = (t * speed + i * 0.37f) % 1f
                val y = -0.95f + phase * 1.9f
                val top = c + Offset(x * r - y * r * 0.05f, y * r)
                drawLine(Palette.teal.copy(alpha = 0.4f * sin(phase * PI.toFloat())), top, top + Offset(-r * 0.02f, r * 0.1f),
                    r * 0.018f, StrokeCap.Round)
            }
            // The odd flash of lightning.
            if ("thunder" in s && (t % 9f) < 0.12f) {
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.3f), Color.Transparent), c, r * 1.1f), r * 1.1f, c)
            }
        }
        "snow" in s -> {
            for (i in 0 until 9) {
                val x = ((i * 0.618f) % 1f) * 1.7f - 0.85f
                val phase = (t * 0.15f + i * 0.29f) % 1f
                val pos = c + Offset((x + 0.05f * sin(t * 1.3f + i)) * r, (-0.95f + phase * 1.9f) * r)
                drawCircle(Palette.ink.copy(alpha = 0.6f * sin(phase * PI.toFloat())), r * 0.025f, pos)
            }
        }
        ("clear" in s) && daytime -> {
            val a = 0.16f + 0.05f * sin(t * TAU / 9f)
            drawCircle(Brush.radialGradient(listOf(Palette.amber.copy(alpha = a), Color.Transparent), c, r * 1.1f), r * 1.1f, c)
        }
        "fog" in s -> {
            drawCircle(Brush.radialGradient(listOf(Color.Transparent, Palette.muted.copy(alpha = 0.18f), Color.Transparent), c, r * 1.05f), r * 1.05f, c)
        }
    }
}

private val confettiColors = listOf(Palette.coral, Palette.amber, Palette.teal, Palette.violet, Palette.ink)

/** A burst of confetti from the blob that falls and fades. */
private fun DrawScope.drawConfetti(from: Offset, r: Float, seconds: Float, p: Float) {
    val alpha = if (p < 0.7f) 1f else (1f - p) / 0.3f
    for (i in 0 until 28) {
        // Fixed per-piece randomness, so each frame continues the same burst.
        val a = -PI.toFloat() / 2 + ((i * 0.618f) % 1f - 0.5f) * 2.4f
        val speed = r * (1.6f + ((i * 0.377f) % 1f) * 1.4f)
        val x = from.x + cos(a) * speed * seconds
        val y = from.y + sin(a) * speed * seconds + 0.5f * r * 3.2f * seconds * seconds
        val spin = seconds * (180f + i * 37f)
        val piece = Size(r * 0.06f, r * 0.03f)
        rotate(spin, Offset(x, y)) {
            drawRect(confettiColors[i % confettiColors.size].copy(alpha = alpha), Offset(x - piece.width / 2, y - piece.height / 2), piece)
        }
    }
}

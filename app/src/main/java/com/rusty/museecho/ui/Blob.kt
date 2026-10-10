package com.rusty.museecho.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rusty.museecho.gadget.Accessory
import com.rusty.museecho.gadget.BlobBrain
import com.rusty.museecho.gadget.HeldItem
import com.rusty.museecho.gadget.GadgetState
import com.rusty.museecho.gadget.Link
import com.rusty.museecho.gadget.Mood
import com.rusty.museecho.gadget.Reaction
import com.rusty.museecho.gadget.Talk
import com.rusty.museecho.integrations.AgendaDay
import com.rusty.museecho.integrations.ServerStatus
import com.rusty.museecho.integrations.WeatherNow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay

private const val TAU = (2 * PI).toFloat()
private const val PIF = PI.toFloat()

/**
 * Pixel's on-screen body: a breathing orb with a face. It blinks, looks where
 * you tap, follows the real loudness of your voice, its own and the music,
 * thinks while waiting, sleeps through quiet hours (drowsy if you're still up),
 * frets when the server is down, shows the weather, dresses for the season,
 * watches the TV, keeps an eye on timers, and now and then does something silly.
 * Tapping it gives a quick check-in on the day.
 *
 * [lookAt] is where a finger last touched, in this composable's own pixels, at [lookAtMs];
 * [tapAt] / [tapAtMs] the last real tap (touch and release without moving).
 */
@Composable
internal fun Blob(modifier: Modifier, actions: UiActions, lookAt: Offset?, lookAtMs: Long, tapAt: Offset?, tapAtMs: Long) {
    val quiet by GadgetState.quiet.collectAsState()
    val speaking by GadgetState.speaking.collectAsState()
    val talk by GadgetState.talk.collectAsState()
    val link by GadgetState.link.collectAsState()
    val server by GadgetState.server.collectAsState()
    val showServer by GadgetState.serverPill.collectAsState()
    val weather by GadgetState.weather.collectAsState()
    val moodSet by GadgetState.mood.collectAsState()
    val reaction by GadgetState.reaction.collectAsState()
    val alertGlow by GadgetState.alertGlow.collectAsState()
    val style by GadgetState.style.collectAsState()
    val nowPlaying by GadgetState.nowPlaying.collectAsState()
    val tvPlaying by GadgetState.tvPlaying.collectAsState()
    val workingOn by GadgetState.workingOn.collectAsState()
    val bubble by GadgetState.bubble.collectAsState()
    val timers by actions.timers().collectAsState()
    val agenda by actions.agenda().collectAsState()
    val held by GadgetState.held.collectAsState()
    val museBusy by GadgetState.museBusy.collectAsState()
    val roomDark by GadgetState.roomDark.collectAsState()
    var dreamPhoto by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var litAtMs by remember { mutableStateOf(0L) }

    var t by remember { mutableFloatStateOf(0f) }
    var mic by remember { mutableFloatStateOf(0f) }
    var voice by remember { mutableFloatStateOf(0f) }
    var music by remember { mutableFloatStateOf(0f) }
    var nextBlink by remember { mutableFloatStateOf(3f) }
    var blinkAt by remember { mutableFloatStateOf(-1f) }
    var sizePx by remember { mutableFloatStateOf(0f) }

    val state = rememberUpdatedState(Triple(quiet, speaking, talk))
    val reactionNow = rememberUpdatedState(reaction)
    val glowNow = rememberUpdatedState(alertGlow)
    val musicOn = rememberUpdatedState(nowPlaying != null)
    val timerSoon = rememberUpdatedState(timers.filter { it.kind == "timer" }.minOfOrNull { it.dueAtMs })

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
            music = if (musicOn.value) {
                val m = GadgetState.musicLevel()
                music * 0.6f + (if (m >= 0f) m else 0.6f) * 0.4f
            } else 0f
            if (t > nextBlink + 0.18f) {
                blinkAt = nextBlink
                // Mostly single blinks, sometimes a quick double.
                nextBlink = t + if ((t * 7.3f).toInt() % 5 == 0) 0.35f else 2.5f + ((t * 13.7f) % 3.5f)
            }
            val r = reactionNow.value
            val reacting = r != null && now - r.atMs < r.reaction.ms
            val antsy = timerSoon.value?.let { it - now in 1..60_000 } == true
            val fast = s || tk == Talk.LISTENING || tk == Talk.SENDING || tk == Talk.WAITING || reacting || musicOn.value || antsy
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

    // Quiet hours start with a yawn and end with a stretch and a look at the day ahead.
    var wasQuiet by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(quiet) {
        val before = wasQuiet
        wasQuiet = quiet
        if (before == false && quiet) GadgetState.react(Reaction.YAWN)
        if (before == true && !quiet) {
            GadgetState.react(Reaction.STRETCH)
            delay(Reaction.STRETCH.ms + 200)
            checkIn(weather, agenda, server, morning = true, seconds = 600)
        }
        // Something to dream about: one of the photo-frame pictures.
        if (quiet) dreamPhoto = actions.framePhotos().randomOrNull()?.let { loadDreamPhoto(it) }
        // Still up after quiet hours start? It keeps yawning at you. Asleep, it mumbles now and then.
        while (quiet) {
            delay(120_000L + (Math.random() * 120_000).toLong())
            if (System.currentTimeMillis() - GadgetState.lastTouch < 10 * 60_000L) GadgetState.react(Reaction.YAWN)
            else if (Math.random() < 0.15) GadgetState.say(BlobBrain.SLEEP_TALK.random(), 8)
        }
    }

    // A tap on the blob itself: a quick check-in on the day.
    LaunchedEffect(tapAtMs) {
        val at = tapAt ?: return@LaunchedEffect
        if (sizePx <= 0f || System.currentTimeMillis() - tapAtMs > 1_000) return@LaunchedEffect
        val c = Offset(sizePx / 2, sizePx / 2)
        val d = at - c
        if (sqrt(d.x * d.x + d.y * d.y) < sizePx * 0.32f) checkIn(weather, agenda, server, morning = false, seconds = 12)
    }

    // The server coming back is worth a little cheer.
    var lastOverall by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(server?.overall) {
        val before = lastOverall
        lastOverall = server?.overall
        if (before == "down" && server?.overall == "up") {
            GadgetState.react(Reaction.HOP)
            GadgetState.setMood(Mood.HAPPY, 60)
            GadgetState.say("All fixed! 🎉", 60)
        }
    }

    // Lights coming on in the morning: it stretches awake.
    var wasDark by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(roomDark) {
        val before = wasDark
        wasDark = roomDark
        if (before == true && roomDark == false && Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in 5..11) {
            litAtMs = System.currentTimeMillis()
            GadgetState.react(Reaction.STRETCH)
        }
    }

    // Nobody's talked to it in hours: it tries to get some attention.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_200_000L + (Math.random() * 1_200_000).toLong())
            val (q, s, tk) = state.value
            val now = System.currentTimeMillis()
            if (!q && !s && tk == Talk.IDLE && !musicOn.value && now - GadgetState.lastChat > 6 * 3_600_000L) {
                GadgetState.react(Reaction.NUDGE)
                GadgetState.say(BlobBrain.BORED.random(), 20)
            }
        }
    }

    // Agenda countdown: a card for anything starting within 10 minutes, with a nudge at 10 and at the start.
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            tick = System.currentTimeMillis()
        }
    }
    val soonEvent = remember(agenda, tick) { upcomingEvent(agenda, tick) }
    val nudged = remember { mutableSetOf<String>() }
    LaunchedEffect(soonEvent) {
        val (title, mins) = soonEvent ?: return@LaunchedEffect
        if (mins in 9..10 && nudged.add("$title@10")) GadgetState.react(Reaction.NUDGE)
        if (mins == 0 && nudged.add("$title@0")) {
            GadgetState.react(Reaction.HOP)
            GadgetState.say("Time for $title!", 60)
        }
    }

    // Idle antics: every 10-20 minutes, if nobody's around and nothing's going on.
    LaunchedEffect(Unit) {
        val antics = listOf(Reaction.HICCUP, Reaction.STRETCH, Reaction.PEEK, Reaction.WANDER, Reaction.HOP, Reaction.YAWN)
        while (true) {
            delay(600_000L + (Math.random() * 600_000).toLong())
            val (q, s, tk) = state.value
            val idleFor = System.currentTimeMillis() - GadgetState.lastTouch
            if (!q && !s && tk == Talk.IDLE && !GadgetState.ringing.value && !musicOn.value && idleFor > 60_000) {
                GadgetState.react(antics.random())
            }
        }
    }

    // The speech bubble disappears on time even if nothing else changes.
    var bubbleShown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(bubble) {
        val b = bubble
        if (b == null || b.untilMs <= System.currentTimeMillis()) {
            bubbleShown = null
            return@LaunchedEffect
        }
        bubbleShown = b.text
        delay(b.untilMs - System.currentTimeMillis())
        bubbleShown = null
    }

    Box(modifier.onSizeChanged { sizePx = it.width.toFloat() }) {
        Canvas(Modifier.fillMaxSize()) {
            val nowMs = System.currentTimeMillis()
            val r = size.minDimension / 2
            val c = center
            val cal = Calendar.getInstance()
            val hour = cal.get(Calendar.HOUR_OF_DAY)

            // -- What it's feeling ----------------------------------------------------
            val active = reaction?.takeIf { nowMs - it.atMs < it.reaction.ms }
            val react = active?.reaction
            val p = active?.let { ((nowMs - it.atMs).toFloat() / it.reaction.ms).coerceIn(0f, 1f) } ?: 0f
            val serverDown = server?.overall == "down"
            val pixelMood = moodSet?.takeIf { it.untilMs > nowMs }?.mood
            val upLate = nowMs - GadgetState.lastTouch < 10 * 60_000L || talk != Talk.IDLE || speaking || nowMs - litAtMs < 10 * 60_000L
            val lightsOut = roomDark == true && (hour >= 20 || hour < 5)
            val playingMusic = nowPlaying != null
            var mood = when {
                pixelMood != null -> pixelMood
                quiet && !upLate -> Mood.SLEEPY
                quiet -> Mood.DROWSY
                lightsOut && talk == Talk.IDLE && !speaking -> Mood.DROWSY
                talk == Talk.LISTENING -> Mood.CURIOUS
                talk == Talk.ERROR -> Mood.SAD
                serverDown && talk == Talk.IDLE && !speaking -> Mood.WORRIED
                playingMusic && !speaking && talk == Talk.IDLE -> Mood.HAPPY
                else -> Mood.NEUTRAL
            }
            when (react) {
                Reaction.CELEBRATE, Reaction.STRETCH, Reaction.WAVE -> mood = Mood.HAPPY
                Reaction.WINK -> mood = Mood.WINK
                Reaction.YAWN -> mood = Mood.SLEEPY
                Reaction.STARTLE -> mood = Mood.SURPRISED
                Reaction.HICCUP -> if (hiccupSpike(p) > 0.3f) mood = Mood.SURPRISED
                else -> {}
            }

            val ownColor = style.takeIf { it.active(nowMs) }?.color?.let { Color(it) }
            var tint = when {
                speaking -> Palette.teal
                talk == Talk.LISTENING -> Palette.coral
                talk == Talk.SENDING || talk == Talk.WAITING -> Palette.amber
                link == Link.CONNECTED -> ownColor ?: Palette.violet
                else -> Palette.muted
            }
            if (serverDown && talk == Talk.IDLE && !speaking) tint = lerp(tint, Palette.coral, 0.55f)

            val evening = hour >= 17
            val accessory = style.takeIf { it.active(nowMs) }?.accessory
                ?: autoAccessory(cal, weather, playingMusic, tvPlaying && evening)

            // -- How it moves ------------------------------------------------------------
            val breathe = 0.99f + 0.07f * sin(t * TAU / 7.2f) + 0.08f * voice + 0.12f * mic
            var dx = 0f
            var dy = 0f
            var sx = 1f
            var sy = 1f
            var lookX = 0f
            var lookY = 0f
            var looking = false
            when (react) {
                Reaction.HOP, Reaction.CELEBRATE -> {
                    val hops = if (react == Reaction.HOP) 2 else 3
                    val phase = (p * hops) % 1f
                    dy = -abs(sin(phase * PIF)) * r * 0.2f
                    // Squash a little on each landing.
                    val land = 1f - abs(sin(phase * PIF))
                    sy = 1f - 0.08f * land * land
                    sx = 1f + 0.06f * land * land
                    lookY = -0.5f
                }
                Reaction.NOD -> { dy = sin(p * TAU * 2) * r * 0.05f; lookY = 0.3f + 0.5f * sin(p * TAU * 2) }
                Reaction.SHAKE -> { dx = sin(p * TAU * 3) * r * 0.08f * (1 - p); lookX = sin(p * TAU * 3) }
                Reaction.YAWN -> { val o = sin(p * PIF); sy = 1f + 0.08f * o; sx = 1f - 0.03f * o }
                Reaction.STRETCH -> { val o = sin(p * PIF); sy = 1f + 0.15f * o; sx = 1f - 0.08f * o; dy = -o * r * 0.05f }
                Reaction.HICCUP -> { val k = hiccupSpike(p); sx = 1f + 0.09f * k; sy = 1f + 0.09f * k; dy = -k * r * 0.05f }
                Reaction.PEEK -> { lookX = sin(p * TAU); lookY = -0.2f }
                Reaction.WANDER -> {
                    dx = sin(p * TAU) * r * 0.35f
                    dy = sin(p * TAU * 3) * r * 0.04f
                    lookX = cos(p * TAU).coerceIn(-1f, 1f)
                }
                Reaction.WAVE -> { dy = -sin(p * PIF) * r * 0.03f; lookX = 0.3f }
                Reaction.NUDGE -> { dy = -abs(sin(p * TAU * 2)) * r * 0.06f; lookX = 1f; lookY = 0.85f }
                Reaction.DIZZY -> { dx = sin(p * TAU * 2) * r * 0.04f * (1 - p) }
                Reaction.STARTLE -> {
                    val jump = if (p < 0.12f) p / 0.12f else (1f - (p - 0.12f) / 0.3f).coerceAtLeast(0f)
                    dy = -jump * r * 0.2f
                    dx = sin(p * TAU * 6) * r * 0.03f * (1 - p)
                    sx = 1f - 0.05f * jump
                    sy = 1f + 0.08f * jump
                }
                else -> {}
            }
            if (react != null) looking = true

            // Music: bob along (to its real loudness when the ROM lets us measure it).
            if (playingMusic && react == null) {
                val bob = abs(sin(t * PIF * 2f))
                dy -= bob * r * 0.05f * (0.4f + 0.6f * music)
                sy *= 1f - 0.03f * (1 - bob)
            }
            // A timer about to go off: glance up at it and fidget, faster near the end.
            val soon = timers.filter { it.kind == "timer" }.minOfOrNull { it.dueAtMs }?.minus(nowMs)
            if (soon != null && soon in 1..60_000 && react == null) {
                val period = if (soon < 10_000) 0.6f else 2.5f
                val ph = (t % period) / period
                if (ph < 0.25f) dy -= sin(ph / 0.25f * PIF) * r * 0.06f
                lookX = 0.5f; lookY = -0.9f; looking = true
            }
            // Looking over at what Pixel is working on: the laptop to one side, the TV to the other.
            workingOn?.let { cmd ->
                if (!looking) { lookX = if (cmd.startsWith("laptop.")) 0.9f else -0.9f; lookY = -0.1f; looking = true }
            }
            // Busy on the Latitude: every few seconds it glances over at the gear.
            if (!looking && museBusy && talk == Talk.IDLE && (t % 8f) < 2f) { lookX = 0.7f; lookY = -0.6f; looking = true }
            // Thinking: eyes drift up and to the side.
            if (!looking && (talk == Talk.SENDING || talk == Talk.WAITING)) { lookX = 0.6f; lookY = -0.7f; looking = true }
            // A recent tap pulls the eyes towards it.
            if (!looking && lookAt != null && nowMs - lookAtMs < 2_500) {
                val v = lookAt - c
                val len = sqrt(v.x * v.x + v.y * v.y).coerceAtLeast(1f)
                val reach = (len / r).coerceAtMost(1f)
                lookX = v.x / len * reach
                lookY = v.y / len * reach
                looking = true
            }
            // Watching the TV.
            if (!looking && tvPlaying && !quiet) { lookX = -0.85f; lookY = 0.1f }

            // What it's holding: Pixel's prop, else (evenings) a hint about tomorrow's weather.
            val prop = held?.takeIf { it.untilMs > nowMs }?.item
                ?: if (hour >= 18 && !quiet) forecastProp(weather) else null
            val wobble = if (react == Reaction.DIZZY) sin(p * TAU * 3) * 10f * (1 - p) else 0f

            // Munching popcorn now and then.
            val munch = if (accessory == Accessory.POPCORN && (t % 5f) < 1.5f) abs(sin(t * 9f)) else 0f

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
                rotate(wobble, c)
            }) {
                drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.35f), Color.Transparent), c, r), r, c)
                drawCircle(Brush.radialGradient(listOf(Palette.teal.copy(alpha = 0.55f), Color.Transparent), c + shift, r * 0.7f), r * 0.62f, c + shift)
                val core = c - shift * 0.6f
                if (react == Reaction.WAVE) drawWave(core, r, p, tint)
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
                drawFace(core + Offset(0f, -r * 0.03f), r, mood, Offset(lookX, lookY), blinking, voice, munch, react, p, t)
                if (accessory != null) drawAccessory(accessory, core, r, t)
                if (prop != null) drawHeld(prop, core, r, t)
                if (museBusy && talk == Talk.IDLE) drawGear(core + Offset(r * 0.52f, -r * 0.46f), r * 0.09f, t * 1.5f)
                if (mood == Mood.SLEEPY && react == null) drawDream(core, r, t, weather, dreamPhoto)
            }

            // -- In front: confetti --------------------------------------------------------
            if (react == Reaction.CELEBRATE) drawConfetti(body, r, (nowMs - active!!.atMs) / 1000f, p)
        }

        // Speech bubble, up and to the left, with a tail pointing down at the blob.
        bubbleShown?.let { text ->
            val bubbleInk = Palette.ink.copy(alpha = 0.94f)
            androidx.compose.foundation.layout.Column(
                Modifier.align(Alignment.TopStart).layout { m, cons ->
                    val placeable = m.measure(cons.copy(minWidth = 0, maxWidth = 270.dp.roundToPx()))
                    // Bottom of the column (the tail's tip) lands just above the blob's upper-left edge.
                    layout(placeable.width, placeable.height) {
                        placeable.place(4.dp.roundToPx(), 76.dp.roundToPx() - placeable.height)
                    }
                },
            ) {
                Text(
                    text, color = Palette.night, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.background(bubbleInk, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 7.dp),
                )
                Canvas(Modifier.padding(start = 30.dp).size(width = 30.dp, height = 20.dp)) {
                    drawPath(Path().apply {
                        moveTo(0f, 0f); lineTo(size.width * 0.6f, 0f); lineTo(size.width, size.height); close()
                    }, bubbleInk)
                }
            }
        }

        // Cards under the blob: an event starting soon, the label on what it's holding, and the
        // sign it holds up when something on the server is wrong.
        val cards = mutableListOf<Pair<String, Color>>()
        soonEvent?.let { (title, mins) ->
            cards += when {
                mins > 0 -> "⏰ $title in $mins min"
                mins == 0 -> "⏰ $title now"
                else -> "⏰ $title started"
            } to Palette.teal
        }
        held?.takeIf { it.untilMs > tick && it.text.isNotEmpty() }?.let { cards += it.text to Palette.ink }
        val s = server
        if (showServer && s != null && s.overall != "up") {
            val down = s.monitors.filter { it.status == "down" }.map { it.name }
            cards += when {
                s.overall == "down" && down.isNotEmpty() -> "⚠ " + down.take(2).joinToString(", ") +
                    (if (down.size > 2) " +${down.size - 2}" else "") + " down"
                s.overall == "down" -> "⚠ Server down"
                else -> "🔧 Checking servers…"
            } to (if (s.overall == "down") Palette.coral else Palette.amber)
        }
        if (cards.isNotEmpty()) {
            androidx.compose.foundation.layout.Column(
                Modifier.align(Alignment.BottomCenter).layout { m, cons ->
                    val placeable = m.measure(cons.copy(minWidth = 0, maxWidth = 240.dp.roundToPx()))
                    // Hangs just below the blob's box.
                    layout(placeable.width, placeable.height) { placeable.place(0, placeable.height - 4.dp.roundToPx()) }
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                cards.forEach { (text, color) ->
                    Text(
                        text, color = Palette.night, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 3.dp)
                            .background(color, RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** The next agenda item starting within 10 minutes (or started up to 5 minutes ago), as title to minutes away. */
private fun upcomingEvent(agenda: AgendaDay, nowMs: Long): Pair<String, Int>? {
    val today = SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date(nowMs))
    if (agenda.date.isNotEmpty() && agenda.date != today) return null
    val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
    val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    return agenda.items.mapNotNull { item ->
        BlobBrain.minutesOfDay(item.time)?.let { item.title.ifBlank { "Event" }.take(30) to it - nowMin }
    }.filter { it.second in -5..10 }.minByOrNull { abs(it.second) }
}

/** Evenings: an umbrella if tomorrow looks wet, sunglasses peeking out if it looks sunny. */
private fun forecastProp(weather: WeatherNow?): HeldItem? {
    val s = weather?.tomorrowSummary?.lowercase().orEmpty()
    return when {
        s.isEmpty() -> null
        "rain" in s || "shower" in s || "drizzle" in s || "thunder" in s -> HeldItem.UMBRELLA
        "clear" in s || "partly" in s -> HeldItem.SUNGLASSES
        else -> null
    }
}

/** A small, square-cropped copy of a photo-frame picture to dream about. */
private suspend fun loadDreamPhoto(file: java.io.File): androidx.compose.ui.graphics.ImageBitmap? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 128) sample *= 2
            val bmp = android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@runCatching null
            val side = minOf(bmp.width, bmp.height)
            android.graphics.Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side).asImageBitmap()
        }.getOrNull()
    }

/** What it wears when Pixel hasn't chosen: music, TV, the season, then the weather. */
private fun autoAccessory(cal: Calendar, weather: WeatherNow?, music: Boolean, tvEvening: Boolean): Accessory? {
    if (music) return Accessory.HEADPHONES
    if (tvEvening) return Accessory.POPCORN
    val month = cal.get(Calendar.MONTH)
    val day = cal.get(Calendar.DAY_OF_MONTH)
    when {
        month == Calendar.DECEMBER && day <= 26 -> return Accessory.SANTA_HAT
        month == Calendar.OCTOBER && day >= 24 -> return Accessory.WITCH_HAT
        month == Calendar.JANUARY && day == 1 -> return Accessory.PARTY_HAT
        month == Calendar.JULY && day == 4 -> return Accessory.PARTY_HAT
    }
    val w = weather ?: return null
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    val sunny = w.summary.lowercase().let { "clear" in it || "partly" in it }
    if (w.tempF >= 90 && sunny && hour in 8..18) return Accessory.SUNGLASSES
    if (w.tempF <= 50) return Accessory.SCARF
    return null
}

/** A quick read on the day, said in a bubble with a matching face. */
private fun checkIn(weather: WeatherNow?, agenda: AgendaDay, server: ServerStatus?, morning: Boolean, seconds: Int) {
    val parts = mutableListOf<String>()
    var worries = 0
    weather?.let { w ->
        parts += "${w.tempF}° and ${w.summary.lowercase()}"
        val s = w.summary.lowercase()
        if ("rain" in s || "shower" in s || "thunder" in s || "snow" in s) worries++
        if (w.tempF >= 95 || w.tempF <= 40) worries++
    }
    val today = SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date())
    if (agenda.date.isEmpty() || agenda.date == today) {
        val n = agenda.items.size
        parts += when (n) { 0 -> "nothing planned"; 1 -> "1 thing today"; else -> "$n things today" }
        if (n >= 5) worries++
    }
    server?.let { s ->
        parts += when (s.overall) {
            "up" -> "servers all good"
            "down" -> {
                worries++
                val down = s.monitors.filter { it.status == "down" }.map { it.name }
                if (down.size == 1) "${down[0]} is down" else "${down.size} services down"
            }
            else -> "servers being checked"
        }
    }
    weather?.takeIf { it.tomorrowSummary.isNotEmpty() && Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= 17 }?.let { w ->
        parts += "tomorrow ${w.tomorrowSummary.lowercase()}" + (w.tomorrowHighF?.let { ", $it°" } ?: "")
    }
    val opener = if (morning) "Good morning!" else "Today:"
    GadgetState.say(if (parts.isEmpty()) "$opener Nothing to report." else "$opener " + parts.joinToString(" · "), seconds)
    GadgetState.setMood(
        when {
            worries == 0 -> Mood.HAPPY
            worries == 1 -> Mood.CURIOUS
            else -> Mood.WORRIED
        },
        seconds,
    )
}

/** Two sharp bumps over the reaction: hic... hic. */
private fun hiccupSpike(p: Float): Float {
    fun bump(at: Float) = (1f - abs(p - at) / 0.08f).coerceAtLeast(0f)
    return maxOf(bump(0.2f), bump(0.65f))
}

private val eyeInk = Palette.night.copy(alpha = 0.88f)

private fun DrawScope.drawFace(
    at: Offset, r: Float, mood: Mood, look: Offset, blinking: Boolean,
    voice: Float, munch: Float, react: Reaction?, p: Float, t: Float,
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
    // Half-closed: just the lower half of the eye under a heavy lid.
    fun lidded(center: Offset) {
        val eh = if (blinking) h * 0.12f else h * 0.75f
        drawArc(eyeInk, 0f, 180f, true, Offset(center.x - w, center.y - eh * 0.5f), Size(w * 2, eh))
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

    if (react == Reaction.DIZZY) {
        for (eye in listOf(left, right)) {
            val path = Path()
            for (k in 0..26) {
                val a = k * 0.55f + t * 9f
                val rad = w * 1.5f * k / 26f
                val pt = eye + Offset(cos(a) * rad, sin(a) * rad)
                if (k == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
            }
            drawPath(path, eyeInk, style = Stroke(width = r * 0.022f, cap = StrokeCap.Round))
        }
        // A wobbly line of a mouth.
        val wavy = Path().apply {
            moveTo(mouth.x - w * 1.2f, mouth.y)
            for (k in 1..6) lineTo(mouth.x - w * 1.2f + k * w * 0.4f, mouth.y + (if (k % 2 == 0) 0f else h * 0.18f))
        }
        drawPath(wavy, eyeInk, style = Stroke(width = r * 0.025f, cap = StrokeCap.Round))
        return
    }
    when (mood) {
        Mood.NEUTRAL -> { oval(left); oval(right) }
        Mood.HAPPY -> { happyArc(left); happyArc(right); if (munch < 0.05f) smile(w * 1.4f, h * 0.7f) }
        Mood.CURIOUS -> { oval(left, 1.22f); oval(right, 0.92f) }
        Mood.WORRIED -> { oval(left); oval(right); brows(0.45f); smile(w * 0.9f, -h * 0.2f) }
        Mood.SAD -> { oval(left, squash = 0.8f); oval(right, squash = 0.8f); brows(0.55f); smile(w * 1.1f, -h * 0.55f) }
        Mood.DROWSY -> { lidded(left); lidded(right) }
        Mood.SURPRISED -> {
            drawCircle(eyeInk, w * 1.35f, left)
            drawCircle(eyeInk, w * 1.35f, right)
            drawOval(eyeInk, Offset(mouth.x - w * 0.6f, mouth.y - h * 0.15f), Size(w * 1.2f, h * 0.9f))
        }
        Mood.WINK -> { happyArc(left); oval(right); smile(w * 1.2f, h * 0.55f) }
        Mood.SLEEPY -> {
            closedArc(left); closedArc(right)
            if (react == Reaction.YAWN) {
                val o = sin(p * PIF)
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
    } else if (munch > 0.05f) {
        val open = h * 0.5f * munch
        drawOval(eyeInk, Offset(mouth.x - w * 0.6f, mouth.y - open * 0.3f), Size(w * 1.2f, open))
    }
}

/** A little hand popping out of the side, waving. */
private fun DrawScope.drawWave(core: Offset, r: Float, p: Float, tint: Color) {
    val fade = (minOf(p, 1 - p) / 0.15f).coerceIn(0f, 1f)
    val pivot = core + Offset(r * 0.4f, r * 0.12f)
    val angle = (25f + 30f * sin(p * TAU * 3)) * PIF / 180f
    val hand = pivot + Offset(sin(angle) * r * 0.24f, -cos(angle) * r * 0.24f)
    drawLine(tint.copy(alpha = fade), pivot, hand, r * 0.1f, StrokeCap.Round)
    drawCircle(tint.copy(alpha = fade), r * 0.08f, hand)
}

private val hatRed = Color(0xFFE5484D)
private val witchPurple = Color(0xFF3B2A5C)
private val shades = Color(0xF2111018)
private val cream = Color(0xFFFFF4D6)

private fun DrawScope.drawAccessory(a: Accessory, core: Offset, r: Float, t: Float) {
    val top = core + Offset(0f, -r * 0.42f)
    when (a) {
        Accessory.NONE -> {}
        Accessory.PARTY_HAT -> rotate(12f, top) {
            val path = Path().apply {
                moveTo(top.x - r * 0.16f, top.y); lineTo(top.x + r * 0.16f, top.y); lineTo(top.x, top.y - r * 0.38f); close()
            }
            drawPath(path, Palette.coral)
            drawLine(Palette.amber, Offset(top.x - r * 0.1f, top.y - r * 0.12f), Offset(top.x + r * 0.1f, top.y - r * 0.12f), r * 0.03f)
            drawLine(Palette.amber, Offset(top.x - r * 0.05f, top.y - r * 0.25f), Offset(top.x + r * 0.05f, top.y - r * 0.25f), r * 0.03f)
            drawCircle(Palette.amber, r * 0.05f, Offset(top.x, top.y - r * 0.38f))
        }
        Accessory.SANTA_HAT -> rotate(-8f, top) {
            val path = Path().apply {
                moveTo(top.x - r * 0.27f, top.y)
                quadraticTo(top.x - r * 0.05f, top.y - r * 0.5f, top.x + r * 0.3f, top.y - r * 0.3f)
                lineTo(top.x + r * 0.27f, top.y)
                close()
            }
            drawPath(path, hatRed)
            drawRoundRect(Color.White, Offset(top.x - r * 0.31f, top.y - r * 0.05f), Size(r * 0.62f, r * 0.11f), CornerRadius(r * 0.05f))
            drawCircle(Color.White, r * 0.07f, Offset(top.x + r * 0.3f, top.y - r * 0.3f))
        }
        Accessory.WITCH_HAT -> rotate(-6f, top) {
            drawOval(witchPurple, Offset(top.x - r * 0.4f, top.y - r * 0.05f), Size(r * 0.8f, r * 0.12f))
            val path = Path().apply {
                moveTo(top.x - r * 0.2f, top.y); lineTo(top.x + r * 0.2f, top.y); lineTo(top.x + r * 0.12f, top.y - r * 0.5f); close()
            }
            drawPath(path, witchPurple)
            drawRect(Palette.amber, Offset(top.x - r * 0.19f, top.y - r * 0.08f), Size(r * 0.38f, r * 0.05f))
        }
        Accessory.CROWN -> {
            val y = top.y + r * 0.02f
            val path = Path().apply {
                moveTo(top.x - r * 0.22f, y)
                lineTo(top.x - r * 0.22f, y - r * 0.2f); lineTo(top.x - r * 0.11f, y - r * 0.09f)
                lineTo(top.x, y - r * 0.24f); lineTo(top.x + r * 0.11f, y - r * 0.09f)
                lineTo(top.x + r * 0.22f, y - r * 0.2f); lineTo(top.x + r * 0.22f, y)
                close()
            }
            drawPath(path, Palette.amber)
            drawCircle(Palette.coral, r * 0.03f, Offset(top.x, y - r * 0.07f))
        }
        Accessory.BOW -> {
            val at = core + Offset(r * 0.3f, -r * 0.36f)
            val pink = Color(0xFFFF8FD8)
            drawPath(Path().apply { moveTo(at.x, at.y); lineTo(at.x - r * 0.14f, at.y - r * 0.09f); lineTo(at.x - r * 0.14f, at.y + r * 0.09f); close() }, pink)
            drawPath(Path().apply { moveTo(at.x, at.y); lineTo(at.x + r * 0.14f, at.y - r * 0.09f); lineTo(at.x + r * 0.14f, at.y + r * 0.09f); close() }, pink)
            drawCircle(Palette.coral, r * 0.04f, at)
        }
        Accessory.FLOWER -> {
            val at = core + Offset(-r * 0.3f, -r * 0.36f)
            for (i in 0 until 5) {
                val a = i * TAU / 5 + t * 0.2f
                drawCircle(Color(0xFFFFB3DE), r * 0.055f, at + Offset(cos(a) * r * 0.065f, sin(a) * r * 0.065f))
            }
            drawCircle(Palette.amber, r * 0.04f, at)
        }
        Accessory.SUNGLASSES -> {
            val eyes = core + Offset(0f, -r * 0.03f)
            val lens = Size(r * 0.2f, r * 0.14f)
            for (side in listOf(-1f, 1f)) {
                val cx = eyes.x + side * r * 0.17f
                drawRoundRect(shades, Offset(cx - lens.width / 2, eyes.y - lens.height / 2), lens, CornerRadius(r * 0.04f))
                drawLine(Color.White.copy(alpha = 0.35f), Offset(cx - r * 0.06f, eyes.y - r * 0.03f), Offset(cx - r * 0.02f, eyes.y - r * 0.05f), r * 0.015f, StrokeCap.Round)
            }
            drawLine(shades, Offset(eyes.x - r * 0.07f, eyes.y - r * 0.02f), Offset(eyes.x + r * 0.07f, eyes.y - r * 0.02f), r * 0.025f)
        }
        Accessory.SCARF -> {
            val y = core.y + r * 0.3f
            drawRoundRect(Palette.coral, Offset(core.x - r * 0.4f, y), Size(r * 0.8f, r * 0.12f), CornerRadius(r * 0.06f))
            for (i in -2..2) drawLine(Palette.teal, Offset(core.x + i * r * 0.14f, y + r * 0.01f), Offset(core.x + i * r * 0.14f, y + r * 0.11f), r * 0.025f)
            rotate(10f, Offset(core.x + r * 0.2f, y + r * 0.08f)) {
                drawRoundRect(Palette.coral, Offset(core.x + r * 0.15f, y + r * 0.06f), Size(r * 0.12f, r * 0.28f), CornerRadius(r * 0.04f))
            }
        }
        Accessory.HEADPHONES -> {
            drawArc(Palette.ink.copy(alpha = 0.9f), 200f, 140f, false, core - Offset(r * 0.52f, r * 0.56f), Size(r * 1.04f, r * 1.04f),
                style = Stroke(width = r * 0.05f, cap = StrokeCap.Round))
            for (side in listOf(-1f, 1f)) {
                drawRoundRect(Palette.coral, Offset(core.x + side * r * 0.5f - r * 0.065f, core.y - r * 0.17f), Size(r * 0.13f, r * 0.24f), CornerRadius(r * 0.05f))
            }
        }
        Accessory.POPCORN -> {
            val base = core + Offset(r * 0.4f, r * 0.42f)
            val bucket = Path().apply {
                moveTo(base.x - r * 0.13f, base.y - r * 0.26f); lineTo(base.x + r * 0.13f, base.y - r * 0.26f)
                lineTo(base.x + r * 0.1f, base.y); lineTo(base.x - r * 0.1f, base.y); close()
            }
            drawPath(bucket, hatRed)
            for (i in -1..1) drawLine(Color.White, Offset(base.x + i * r * 0.08f, base.y - r * 0.25f), Offset(base.x + i * r * 0.065f, base.y - r * 0.01f), r * 0.025f)
            for (i in 0 until 5) drawCircle(cream, r * 0.05f, Offset(base.x + (i - 2) * r * 0.055f, base.y - r * 0.28f - (i % 2) * r * 0.04f))
        }
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
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
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
                drawLine(Palette.teal.copy(alpha = 0.4f * sin(phase * PIF)), top, top + Offset(-r * 0.02f, r * 0.1f),
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
                drawCircle(Palette.ink.copy(alpha = 0.6f * sin(phase * PIF)), r * 0.025f, pos)
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
        val a = -PIF / 2 + ((i * 0.618f) % 1f - 0.5f) * 2.4f
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

private val cardboard = Color(0xFFC28E5C)
private val tape = Color(0xFFE8C99B)
private val frosting = Color(0xFFFFB3DE)

/** A prop held at the blob's lower left (blob.hold, or the evening forecast hint). */
private fun DrawScope.drawHeld(item: HeldItem, core: Offset, r: Float, t: Float) {
    val a = core + Offset(-r * 0.44f, r * 0.36f)
    when (item) {
        HeldItem.NONE -> {}
        HeldItem.PACKAGE -> {
            drawRect(cardboard, Offset(a.x - r * 0.13f, a.y - r * 0.1f), Size(r * 0.26f, r * 0.2f))
            drawRect(tape, Offset(a.x - r * 0.025f, a.y - r * 0.1f), Size(r * 0.05f, r * 0.2f))
            drawLine(Palette.night.copy(alpha = 0.4f), Offset(a.x - r * 0.13f, a.y - r * 0.1f), Offset(a.x + r * 0.13f, a.y - r * 0.1f), r * 0.01f)
        }
        HeldItem.UMBRELLA -> {
            drawLine(Palette.ink, a + Offset(0f, -r * 0.18f), a + Offset(0f, r * 0.1f), r * 0.02f, StrokeCap.Round)
            drawArc(Palette.ink, 0f, 180f, false, a + Offset(-r * 0.06f, r * 0.04f), Size(r * 0.06f, r * 0.08f),
                style = Stroke(width = r * 0.02f, cap = StrokeCap.Round))
            drawArc(Palette.teal, 180f, 180f, true, a + Offset(-r * 0.22f, -r * 0.36f), Size(r * 0.44f, r * 0.36f))
            for (i in 0..2) drawCircle(Palette.night, r * 0.04f, a + Offset(-r * 0.15f + i * r * 0.15f, -r * 0.17f))
        }
        HeldItem.LETTER -> {
            drawRect(Color.White, Offset(a.x - r * 0.13f, a.y - r * 0.085f), Size(r * 0.26f, r * 0.17f))
            val v = Path().apply { moveTo(a.x - r * 0.13f, a.y - r * 0.085f); lineTo(a.x, a.y + r * 0.01f); lineTo(a.x + r * 0.13f, a.y - r * 0.085f) }
            drawPath(v, Palette.night.copy(alpha = 0.5f), style = Stroke(width = r * 0.015f))
            drawCircle(Palette.coral, r * 0.025f, a + Offset(0f, r * 0.01f))
        }
        HeldItem.GIFT -> {
            drawRect(Palette.teal, Offset(a.x - r * 0.12f, a.y - r * 0.1f), Size(r * 0.24f, r * 0.2f))
            drawRect(Palette.coral, Offset(a.x - r * 0.025f, a.y - r * 0.1f), Size(r * 0.05f, r * 0.2f))
            drawRect(Palette.coral, Offset(a.x - r * 0.12f, a.y - r * 0.025f), Size(r * 0.24f, r * 0.05f))
            drawCircle(Palette.coral, r * 0.04f, a + Offset(-r * 0.04f, -r * 0.13f))
            drawCircle(Palette.coral, r * 0.04f, a + Offset(r * 0.04f, -r * 0.13f))
        }
        HeldItem.COFFEE -> {
            val cup = Path().apply {
                moveTo(a.x - r * 0.1f, a.y - r * 0.1f); lineTo(a.x + r * 0.1f, a.y - r * 0.1f)
                lineTo(a.x + r * 0.08f, a.y + r * 0.1f); lineTo(a.x - r * 0.08f, a.y + r * 0.1f); close()
            }
            drawPath(cup, cream)
            drawArc(cream, -90f, 180f, false, a + Offset(r * 0.06f, -r * 0.06f), Size(r * 0.09f, r * 0.1f), style = Stroke(width = r * 0.025f))
            drawRect(cardboard, Offset(a.x - r * 0.095f, a.y - r * 0.03f), Size(r * 0.185f, r * 0.05f))
            for (i in 0..1) {
                val x = a.x - r * 0.03f + i * r * 0.06f
                val rise = (t * 0.5f + i * 0.5f) % 1f
                drawLine(Palette.ink.copy(alpha = 0.5f * (1 - rise)), Offset(x + sin(t * 3 + i) * r * 0.015f, a.y - r * 0.13f - rise * r * 0.1f),
                    Offset(x, a.y - r * 0.12f - rise * r * 0.04f), r * 0.012f, StrokeCap.Round)
            }
        }
        HeldItem.HEART -> {
            val k = 1f + 0.08f * sin(t * 4f)
            drawHeart(a, r * 0.12f * k, Palette.coral)
        }
        HeldItem.STAR -> rotate(sin(t * 1.5f) * 12f, a) {
            val path = Path()
            for (i in 0 until 10) {
                val rad = if (i % 2 == 0) r * 0.14f else r * 0.06f
                val ang = -PIF / 2 + i * PIF / 5
                val pt = a + Offset(cos(ang) * rad, sin(ang) * rad)
                if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
            }
            path.close()
            drawPath(path, Palette.amber)
        }
        HeldItem.BALLOON -> {
            val top = a + Offset(sin(t * 1.2f) * r * 0.03f, -r * 0.5f)
            drawLine(Palette.ink.copy(alpha = 0.6f), a, top + Offset(0f, r * 0.12f), r * 0.01f)
            drawOval(Palette.coral, Offset(top.x - r * 0.1f, top.y - r * 0.12f), Size(r * 0.2f, r * 0.24f))
            drawCircle(Color.White.copy(alpha = 0.4f), r * 0.025f, top + Offset(-r * 0.04f, -r * 0.05f))
        }
        HeldItem.CAKE -> {
            drawRect(frosting, Offset(a.x - r * 0.13f, a.y - r * 0.06f), Size(r * 0.26f, r * 0.14f))
            drawRect(Color.White, Offset(a.x - r * 0.13f, a.y - r * 0.08f), Size(r * 0.26f, r * 0.04f))
            drawLine(Palette.teal, a + Offset(0f, -r * 0.08f), a + Offset(0f, -r * 0.17f), r * 0.02f)
            val flicker = 1f + 0.2f * sin(t * 12f)
            drawOval(Palette.amber, Offset(a.x - r * 0.02f, a.y - r * 0.22f * flicker), Size(r * 0.04f, r * 0.05f * flicker))
        }
        HeldItem.SUNGLASSES -> rotate(-15f, a) {
            val lens = Size(r * 0.11f, r * 0.08f)
            drawRoundRect(shades, Offset(a.x - r * 0.125f, a.y - lens.height / 2), lens, CornerRadius(r * 0.025f))
            drawRoundRect(shades, Offset(a.x + r * 0.015f, a.y - lens.height / 2), lens, CornerRadius(r * 0.025f))
            drawLine(shades, Offset(a.x - r * 0.015f, a.y - r * 0.02f), Offset(a.x + r * 0.015f, a.y - r * 0.02f), r * 0.015f)
        }
    }
}

private fun DrawScope.drawHeart(c: Offset, size: Float, color: Color) {
    drawCircle(color, size * 0.5f, c + Offset(-size * 0.45f, -size * 0.2f))
    drawCircle(color, size * 0.5f, c + Offset(size * 0.45f, -size * 0.2f))
    drawPath(Path().apply {
        moveTo(c.x - size * 0.93f, c.y - size * 0.05f); lineTo(c.x + size * 0.93f, c.y - size * 0.05f); lineTo(c.x, c.y + size); close()
    }, color)
}

/** A small turning gear: Pixel is busy on the Latitude. */
private fun DrawScope.drawGear(c: Offset, size: Float, angle: Float) {
    val ink = Palette.muted.copy(alpha = 0.9f)
    for (i in 0 until 8) {
        rotate(angle * 180f / PIF + i * 45f, c) {
            drawRect(ink, Offset(c.x - size * 0.22f, c.y - size * 1.25f), Size(size * 0.44f, size * 0.5f))
        }
    }
    drawCircle(ink, size, c)
    drawCircle(Palette.night, size * 0.4f, c)
}

/** A thought cloud while it sleeps, dreaming of the weather, a photo, a heart, music or confetti. */
private fun DrawScope.drawDream(core: Offset, r: Float, t: Float, weather: WeatherNow?, photo: androidx.compose.ui.graphics.ImageBitmap?) {
    val bob = sin(t * 0.8f) * r * 0.02f
    val d = core + Offset(-r * 0.66f, -r * 0.78f + bob)
    val puff = Palette.ink.copy(alpha = 0.16f)
    drawCircle(puff, r * 0.025f, core + Offset(-r * 0.36f, -r * 0.42f))
    drawCircle(puff, r * 0.04f, core + Offset(-r * 0.47f, -r * 0.55f))
    drawCircle(puff, r * 0.2f, d)
    drawCircle(puff, r * 0.13f, d + Offset(-r * 0.16f, r * 0.05f))
    drawCircle(puff, r * 0.13f, d + Offset(r * 0.16f, r * 0.05f))
    val theme = ((t / 40f).toInt()) % 5
    when (theme) {
        0 -> {
            val s = (weather?.tomorrowSummary?.ifEmpty { null } ?: weather?.summary).orEmpty().lowercase()
            if ("rain" in s || "shower" in s || "drizzle" in s || "thunder" in s) {
                drawCircle(Palette.ink.copy(alpha = 0.6f), r * 0.07f, d + Offset(-r * 0.03f, -r * 0.03f))
                drawCircle(Palette.ink.copy(alpha = 0.6f), r * 0.055f, d + Offset(r * 0.05f, -r * 0.01f))
                for (i in -1..1) drawLine(Palette.teal, d + Offset(i * r * 0.05f, r * 0.05f), d + Offset(i * r * 0.05f - r * 0.01f, r * 0.1f), r * 0.015f, StrokeCap.Round)
            } else {
                drawCircle(Palette.amber, r * 0.06f, d)
                for (i in 0 until 8) {
                    val ang = i * TAU / 8 + t * 0.3f
                    drawLine(Palette.amber, d + Offset(cos(ang) * r * 0.08f, sin(ang) * r * 0.08f), d + Offset(cos(ang) * r * 0.11f, sin(ang) * r * 0.11f), r * 0.015f, StrokeCap.Round)
                }
            }
        }
        1 -> if (photo != null) {
            val rad = r * 0.15f
            clipPath(Path().apply { addOval(Rect(d, rad)) }) {
                drawImage(photo, IntOffset.Zero, IntSize(photo.width, photo.height),
                    IntOffset((d.x - rad).toInt(), (d.y - rad).toInt()), IntSize((rad * 2).toInt(), (rad * 2).toInt()), alpha = 0.85f)
            }
        } else drawHeart(d, r * 0.08f, Palette.coral)
        2 -> drawHeart(d, r * 0.08f, Palette.coral)
        3 -> {
            drawOval(Palette.ink.copy(alpha = 0.8f), Offset(d.x - r * 0.06f, d.y + r * 0.02f), Size(r * 0.07f, r * 0.05f))
            drawLine(Palette.ink.copy(alpha = 0.8f), d + Offset(r * 0.005f, r * 0.04f), d + Offset(r * 0.005f, -r * 0.08f), r * 0.015f)
            drawLine(Palette.ink.copy(alpha = 0.8f), d + Offset(r * 0.005f, -r * 0.08f), d + Offset(r * 0.06f, -r * 0.05f), r * 0.015f)
        }
        else -> for (i in 0 until 7) {
            val ang = i * TAU / 7 + t * 0.4f
            drawRect(confettiColors[i % confettiColors.size], d + Offset(cos(ang) * r * 0.08f, sin(ang) * r * 0.06f), Size(r * 0.03f, r * 0.018f))
        }
    }
}

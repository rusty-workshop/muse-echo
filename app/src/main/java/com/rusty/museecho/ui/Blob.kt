package com.rusty.museecho.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.drawText
import androidx.compose.runtime.withFrameNanos
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
 * Only the full-size blob on the clock runs the routines (antics, check-ins, nudges).
 */
@Composable
internal fun Blob(
    modifier: Modifier,
    actions: UiActions,
    lookAt: Offset? = null,
    lookAtMs: Long = 0,
    tapAt: Offset? = null,
    tapAtMs: Long = 0,
    /** A small copy elsewhere (the agenda page): just the face, no side effects or extras. */
    mini: Boolean = false,
    /** Where a mini blob looks, -1..1 each way. */
    gaze: Offset? = null,
    moodOverride: Mood? = null,
    /** A colour of its own (the visiting friend). */
    tintOverride: Color? = null,
    /**
     * A full-size copy elsewhere (the Blob page): it looks and moves the same, but leaves the
     * routines (antics, check-ins, yawns, cards) to the one on the clock, so nothing happens twice.
     */
    follower: Boolean = false,
    /** A friend: wears [accessoryOverride] (or nothing), never the main blob's style or automatic hats. */
    ownStyle: Boolean = true,
    accessoryOverride: Accessory? = null,
) {
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
    val laptopSt by GadgetState.laptopStatus.collectAsState()
    // Music on the laptop counts too; how it sounds picks the moves.
    val laptopVibe = laptopSt?.takeIf { it.playing && !mini }?.musicVibe.orEmpty()
    val tvPlaying by GadgetState.tvPlaying.collectAsState()
    val workingOn by GadgetState.workingOn.collectAsState()
    val bubble by GadgetState.bubble.collectAsState()
    val timers by actions.timers().collectAsState()
    val agenda by actions.agenda().collectAsState()
    val held by GadgetState.held.collectAsState()
    val museBusy by GadgetState.museBusy.collectAsState()
    val roomDark by GadgetState.roomDark.collectAsState()
    val energy by GadgetState.blobEnergy.collectAsState()
    val muted by GadgetState.muted.collectAsState()
    val tucked by GadgetState.tuckedIn.collectAsState()
    val sniffles by GadgetState.sniffles.collectAsState()
    val nightLight by GadgetState.nightLight.collectAsState()
    val sickBlanket by GadgetState.sickBlanket.collectAsState()
    val presenting by GadgetState.presenting.collectAsState()
    val alerts by GadgetState.alerts.collectAsState()
    val reminderDay by GadgetState.reminders.collectAsState()
    val specialDays by GadgetState.specialDays.collectAsState()
    val replyText by GadgetState.reply.collectAsState()
    val turnActive by GadgetState.turnActive.collectAsState()
    val previewNow by GadgetState.preview.collectAsState()
    val previewing = rememberUpdatedState(previewNow)
    // Playing catch (or a snowball fight): hold still so the ball really lands on its head.
    val catchGame by GadgetState.friendScene.collectAsState()
    val holdStill = catchGame.throwAt > 0
    // Rusty asked it to hold still (Setup or the settings page): no idle motion, until the timer runs out.
    val stillUntil by GadgetState.stillUntil.collectAsState()
    var stillNow by remember { mutableStateOf(false) }
    LaunchedEffect(stillUntil) {
        stillNow = stillUntil > System.currentTimeMillis()
        if (stillNow && stillUntil != Long.MAX_VALUE) {
            delay(stillUntil - System.currentTimeMillis())
            stillNow = false
        }
    }
    val idleStill = stillNow
    val leaveBy by GadgetState.leaveBy.collectAsState()
    val work by GadgetState.assignments.collectAsState()
    val guest = if (mini) false else rememberGuest()
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
    val musicOn = rememberUpdatedState(nowPlaying != null || laptopVibe.isNotEmpty())
    val timerSoon = rememberUpdatedState(timers.filter { it.kind == "timer" }.minOfOrNull { it.dueAtMs })
    val thinking = remember { object { var value = 0f } }
    // Eased values carried between frames (plain fields: they're only touched while drawing).
    val sm = remember { Smooth() }

    // One clock for everything, in step with the display (vsync), so motion is
    // smooth: every frame while anything moves, 24 fps for the slow idle breath, and
    // 10 fps overnight. Levels ease toward their targets per second of real
    // time, so they look the same at any frame rate.
    LaunchedEffect(Unit) {
        var startNanos = -1L
        var lastT = 0f
        while (true) {
            val (q, s, tk) = state.value
            val now = System.currentTimeMillis()
            val r = reactionNow.value
            val reacting = r != null && now - r.atMs < r.reaction.ms
            val antsy = timerSoon.value?.let { it - now in 1..60_000 } == true
            // Full frame rate only while something is actually moving fast.
            val fast = isNewYearMidnight(Calendar.getInstance()) || s || reacting || musicOn.value || antsy ||
                (previewing.value?.let { it.untilMs > now } == true) ||
                tk == Talk.LISTENING || tk == Talk.SENDING || tk == Talk.WAITING || thinking.value > 0.02f
            // Little copies (friends, page blobs, the agenda's) are small enough that 15 fps looks the
            // same; the main one breathes at 20 fps when idle (slow motion doesn't need more).
            val frameMs = when {
                mini -> if (q) 160L else 80L
                fast -> 0L
                q -> 120L
                else -> 40L
            }
            // On a shared beat (multiples of 40 ms of wall time), so Puff, every friend and their
            // movement all redraw in the SAME frame instead of each asking for its own: with a crowd
            // over, unsynced timers meant a full-screen redraw nearly every vsync.
            if (frameMs > 20) awaitBeat(frameMs)
            withFrameNanos { nanos ->
                if (startNanos < 0) startNanos = nanos
                t = (nanos - startNanos) / 1_000_000_000f
            }
            val dt = (t - lastT).coerceIn(0f, 0.1f)
            lastT = t
            fun ease(rate: Float) = 1f - kotlin.math.exp(-rate * dt)
            val micTarget = if (tk == Talk.LISTENING) (actions.micLevel() * 2.5f).coerceAtMost(1f) else 0f
            mic += (micTarget - mic) * ease(if (micTarget > mic) 30f else 8f)
            val voiceTarget = if (s) {
                val out = GadgetState.outputLevel()
                // System TTS gives no audio to measure: fall back to a syllable-rate flutter.
                if (out >= 0f) out else (0.5f + 0.5f * sin(t * 17f)) * (0.6f + 0.4f * sin(t * 5.3f))
            } else 0f
            voice += (voiceTarget - voice) * ease(if (voiceTarget > voice) 35f else 12f)
            val musicTarget = if (musicOn.value) GadgetState.musicLevel().let { if (it >= 0f) it else 0.6f } else 0f
            music += (musicTarget - music) * ease(10f)
            if (t > nextBlink + 0.22f) {
                blinkAt = nextBlink
                // Mostly single blinks, sometimes a quick double.
                nextBlink = t + if ((t * 7.3f).toInt() % 5 == 0) 0.4f else 2.5f + ((t * 13.7f) % 3.5f)
            }
        }
    }

    // Quiet hours start with a yawn and end with a stretch and a look at the day ahead.
    var wasQuiet by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(quiet) {
        if (mini || follower) return@LaunchedEffect
        val before = wasQuiet
        wasQuiet = quiet
        if (before == false && quiet) GadgetState.react(Reaction.YAWN)
        if (before == true && !quiet) {
            GadgetState.react(Reaction.STRETCH)
            delay(Reaction.STRETCH.ms + 200)
            checkIn(weather, agenda, server, morning = true, seconds = 600)
            if (GadgetState.specialDays.value.isNotEmpty()) {
                delay(1_500)
                GadgetState.react(Reaction.TEAR_PAGE)
            }
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
        if (mini || follower) return@LaunchedEffect
        val at = tapAt ?: return@LaunchedEffect
        if (sizePx <= 0f || System.currentTimeMillis() - tapAtMs > 1_000) return@LaunchedEffect
        val c = Offset(sizePx / 2, sizePx / 2)
        val d = at - c
        // The note it holds (bottom left) when it's today's reminders.
        val r0 = sizePx / 2
        val noteAt = Offset(sizePx / 2 - r0 * 0.44f, sizePx / 2 + r0 * 0.36f)
        val onNote = (at - noteAt).getDistance() < r0 * 0.22f
        val dist = sqrt(d.x * d.x + d.y * d.y)
        if (sm.prop == HeldItem.NOTE && sm.noteIsReminders && (onNote || dist < sizePx * 0.32f)) {
            GadgetState.chirp(com.rusty.museecho.gadget.BlobSound.TAP)
            GadgetState.react(Reaction.HOP)
            val day = GadgetState.reminders.value
            GadgetState.show(com.rusty.museecho.gadget.Screen.Text("📝 Today's reminders", day.items.joinToString("\n") { r ->
                (if (r.time.isNotBlank()) "${r.time}  " else "• ") + r.text
            }))
        } else if (dist < sizePx * 0.32f && com.rusty.museecho.gadget.GadgetService.instance?.life?.friends?.tuckBlanket() == true) {
            GadgetState.chirp(com.rusty.museecho.gadget.BlobSound.TAP)
        } else if (dist < sizePx * 0.32f) {
            GadgetState.chirp(com.rusty.museecho.gadget.BlobSound.TAP)
            com.rusty.museecho.gadget.DayLog.poked()
            checkIn(weather, agenda, server, morning = false, seconds = 12)
        }
    }

    // The server coming back is worth a little cheer.
    var lastOverall by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(server?.overall) {
        if (mini || follower) return@LaunchedEffect
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
        if (mini || follower) return@LaunchedEffect
        val before = wasDark
        wasDark = roomDark
        if (before == true && roomDark == false && Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in 5..11) {
            litAtMs = System.currentTimeMillis()
            GadgetState.react(Reaction.STRETCH)
        }
    }

    // Nobody's talked to it in hours: it tries to get some attention.
    LaunchedEffect(Unit) {
        if (mini || follower) return@LaunchedEffect
        while (true) {
            val base = (40f - 30f * GadgetState.blobEnergy.value) * 60_000f
            delay((base + Math.random() * base).toLong())
            val (q, s, tk) = state.value
            val now = System.currentTimeMillis()
            if (!q && !s && tk == Talk.IDLE && !musicOn.value && now - GadgetState.lastChat > 6 * 3_600_000L && !GadgetState.holdingStill()) {
                GadgetState.react(Reaction.NUDGE)
                GadgetState.say(BlobBrain.BORED.random(), 20)
            }
        }
    }

    // Agenda countdown: a card for anything starting within 10 minutes, with a nudge at 10 and at the start.
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        var cheered = false
        while (true) {
            delay(15_000)
            tick = System.currentTimeMillis()
            // Happy New Year!
            val cal = Calendar.getInstance()
            val midnight = cal.get(Calendar.MONTH) == Calendar.JANUARY && cal.get(Calendar.DAY_OF_MONTH) == 1 &&
                cal.get(Calendar.HOUR_OF_DAY) == 0 && cal.get(Calendar.MINUTE) < 2
            if (midnight && !cheered && !mini && !follower) {
                cheered = true
                GadgetState.react(Reaction.CELEBRATE)
                GadgetState.say("Happy New Year! 🎆", 600)
            }
            if (!midnight) cheered = false
        }
    }
    val soonEvent = remember(agenda, tick) { upcomingEvent(agenda, tick) }
    val nudged = remember { mutableSetOf<String>() }
    LaunchedEffect(soonEvent) {
        if (mini || follower) return@LaunchedEffect
        val (title, mins) = soonEvent ?: return@LaunchedEffect
        if (mins in 9..10 && nudged.add("$title@10")) GadgetState.react(Reaction.NUDGE)
        if (mins == 0 && nudged.add("$title@0")) {
            GadgetState.react(Reaction.HOP)
            GadgetState.say("Time for $title!", 60)
        }
    }

    // Idle antics: every 3 to 60 minutes depending on its energy, if nobody's around and nothing's going on.
    LaunchedEffect(Unit) {
        if (mini || follower) return@LaunchedEffect
        val antics = listOf(Reaction.HICCUP, Reaction.STRETCH, Reaction.PEEK, Reaction.WANDER, Reaction.HOP, Reaction.YAWN)
        while (true) {
            val base = (40f - 37f * GadgetState.blobEnergy.value) * 60_000f
            delay((base + Math.random() * base).toLong())
            val (q, s, tk) = state.value
            val idleFor = System.currentTimeMillis() - GadgetState.lastTouch
            if (!q && !s && tk == Talk.IDLE && !GadgetState.ringing.value && !musicOn.value && idleFor > 60_000 && !GadgetState.holdingStill()) {
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

    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    // Things that change by the minute, worked out once a minute rather than every frame.
    val firstToday = remember(agenda, tick) { firstEventMinutes(agenda) }
    // Something with a time tomorrow (all-day things like holidays don't need a bag packed).
    val hasTomorrow = remember(agenda, tick) { tomorrowEvents(agenda).any { BlobBrain.minutesOfDay(it.time) != null } }
    // Only while one of today's reminders is still to come (or has no time); not once they've all passed.
    val remindersToday = remember(reminderDay, tick) { nextReminder(reminderDay, tick) != null }
    val wetAlert = remember(alerts) { alerts.any { a -> listOf("flood", "rain", "storm", "hurricane", "wind", "tornado").any { it in a.event.lowercase() } } }
    val severeAlert = remember(alerts) { alerts.any { it.severity.equals("Severe", true) || it.severity.equals("Extreme", true) } }
    val forecast = remember(weather) { forecastProp(weather) }
    // What happened today (or yesterday, after midnight), to dream about.
    val dreamTopics = remember(tick / 600_000) { dreamTopics(com.rusty.museecho.gadget.DayLog.dreamDay()) }
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
            // The settings page's playground forcing a look for a few seconds.
            val pv = previewNow?.takeIf { it.untilMs > nowMs && !mini }
            fun forced(kind: String) = pv?.takeIf { it.kind == kind }?.value
            val forcedState = forced("state")
            val playingMusic = nowPlaying != null || laptopVibe.isNotEmpty() || forcedState == "MUSIC"
            val vibe = if (nowPlaying == null) laptopVibe else "groove"
            val newYear = isNewYearMidnight(cal)
            var mood = when {
                moodOverride != null -> moodOverride
                pixelMood != null -> pixelMood
                tucked || forcedState == "TUCKED_IN" || forcedState == "SLEEP" -> Mood.SLEEPY
                // Woken by the night light: groggy, eyes half shut.
                nightLight && !speaking && talk == Talk.IDLE -> Mood.DROWSY
                newYear -> Mood.HAPPY
                quiet && !upLate -> Mood.SLEEPY
                quiet -> Mood.DROWSY
                lightsOut && talk == Talk.IDLE && !speaking -> Mood.DROWSY
                talk == Talk.LISTENING -> Mood.CURIOUS
                talk == Talk.ERROR -> Mood.SAD
                (serverDown || severeAlert) && talk == Talk.IDLE && !speaking -> Mood.WORRIED
                playingMusic && vibe == "calm" && !speaking && talk == Talk.IDLE -> Mood.DROWSY
                playingMusic && !speaking && talk == Talk.IDLE -> Mood.HAPPY
                else -> Mood.NEUTRAL
            }
            when (react) {
                Reaction.CELEBRATE, Reaction.STRETCH, Reaction.WAVE -> mood = Mood.HAPPY
                Reaction.WINK -> mood = Mood.WINK
                Reaction.YAWN, Reaction.TUCK_IN -> mood = Mood.SLEEPY
                Reaction.STARTLE -> mood = Mood.SURPRISED
                Reaction.LEAN_IN -> mood = Mood.CURIOUS
                Reaction.HICCUP -> if (hiccupSpike(p) > 0.3f) mood = Mood.SURPRISED
                else -> {}
            }

            val ownColor = style.takeIf { it.active(nowMs) }?.color?.let { Color(it) }
            var tint = when {
                speaking -> Palette.teal
                talk == Talk.LISTENING -> Palette.coral
                talk == Talk.SENDING || talk == Talk.WAITING -> Palette.amber
                tintOverride != null -> tintOverride
                link == Link.CONNECTED -> ownColor ?: seasonal(cal)
                else -> Palette.muted
            }
            if (serverDown && talk == Talk.IDLE && !speaking) tint = lerp(tint, Palette.coral, 0.55f)
            forced("color")?.let { name -> com.rusty.museecho.gadget.BlobStyles.parseColor(name)?.let { tint = Color(it) } }

            val evening = hour >= 17
            val accessory = if (!ownStyle) accessoryOverride else forced("accessory")?.let { v -> Accessory.entries.firstOrNull { it.name == v } }
                ?: style.takeIf { it.active(nowMs) }?.accessory
                ?: autoAccessory(cal, weather, playingMusic, tvPlaying && evening)

            // -- How it moves ------------------------------------------------------------
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
                Reaction.COVER_EARS -> { val k = sin(p * PIF); sx = 1f - 0.05f * k; sy = 1f - 0.04f * k }
                Reaction.TEAR_PAGE -> { dy = -abs(sin(p * TAU)) * r * 0.03f; lookX = -0.6f; lookY = 0.6f }
                Reaction.TUCK_IN -> { val k = sin(p * PIF); sy = 1f - 0.05f * k; dy = r * 0.03f * k }
                Reaction.LEAN_IN -> { val k = sin(p * PIF); sx = 1f + 0.1f * k; sy = 1f + 0.1f * k; dy = r * 0.04f * k; lookY = 0.3f }
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

            // Music: slow songs get a sleepy sway, loud ones a headbang, the rest a bob
            // (to its real loudness when the ROM lets us measure it).
            if (playingMusic && react == null) when (vibe) {
                "calm" -> {
                    dx += sin(t * 1.3f) * r * 0.05f
                    dy -= abs(sin(t * 1.3f)) * r * 0.015f
                }
                "hype" -> {
                    val bang = abs(sin(t * PIF * 2.3f))
                    dy -= (1 - bang) * r * 0.07f
                    sy *= 1f - 0.05f * bang
                    if (!looking) { lookY = 0.2f + 0.7f * bang; looking = true }
                }
                else -> {
                    val bob = abs(sin(t * PIF * 2f))
                    dy -= bob * r * 0.05f * (0.4f + 0.6f * music)
                    sy *= 1f - 0.03f * (1 - bob)
                }
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

            // A running timer: the hourglass shows how much is left, and turns over when a new one starts
            // or time is added.
            val runningTimer = timers.filter { it.kind == "timer" && it.dueAtMs > nowMs }.minByOrNull { it.dueAtMs }
            val timerKey = runningTimer?.let { "${it.id}@${it.dueAtMs}" }
            if (timerKey != sm.timerKey) {
                if (timerKey != null && sm.timerKey != null) sm.flipAt = t
                sm.timerKey = timerKey
            }
            val sand = runningTimer?.let { ((it.dueAtMs - nowMs).toFloat() / it.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f) }
                ?: if (forcedState == "HOURGLASS") 1f - (pv!!.untilMs - nowMs).let { 1f - it / 5_000f }.coerceIn(0f, 1f) else 0f
            val flipP = ((t - sm.flipAt) / 0.7f).coerceIn(0f, 1f)

            // Getting ready: in the hour before the day's first timed event, it fidgets and checks the clock.
            val nowMin = hour * 60 + cal.get(Calendar.MINUTE)
            if (firstToday != null && firstToday - nowMin in 15..60 && react == null && talk == Talk.IDLE && !quiet) {
                val ph = (t % 5f) / 0.5f
                if (ph < 1f) dy -= sin(ph * PIF) * r * 0.04f
                if (!looking && (t % 5f) < 1.5f) { lookX = 1f; lookY = -0.1f; looking = true }
            }

            // What it's holding: what the brief is about right now, a tearing calendar, Pixel's prop,
            // the weather alert, today's reminders, tomorrow's bag, or tomorrow's weather.
            sm.noteIsReminders = false
            val prop = forced("prop")?.let { v -> HeldItem.entries.firstOrNull { it.name == v } }
                ?: (if (forcedState == "HOURGLASS") HeldItem.HOURGLASS else null)
                ?: presenting
                ?: (if (react == Reaction.TEAR_PAGE) HeldItem.CALENDAR else null)
                ?: held?.takeIf { it.untilMs > nowMs }?.item
                ?: (if (runningTimer != null) HeldItem.HOURGLASS else null)
                ?: (if (wetAlert) HeldItem.UMBRELLA else null)
                ?: (if (remindersToday && !guest) HeldItem.NOTE.also { sm.noteIsReminders = true } else null)
                ?: (if (hour >= 18 && !quiet && hasTomorrow && !guest) HeldItem.BAG else null)
                ?: if (hour >= 18 && !quiet) forecast else null
            val wobble = if (react == Reaction.DIZZY) sin(p * TAU * 3) * 10f * (1 - p) else 0f

            // Munching popcorn now and then.
            val munch = if (accessory == Accessory.POPCORN && (t % 5f) < 1.5f) abs(sin(t * 9f)) else 0f

            // Hyper: a little hop every few seconds.
            if (energy > 0.8f && react == null && talk == Talk.IDLE && !quiet && !idleStill) {
                val ph = (t % 4f) / 0.35f
                if (ph < 1f) dy -= sin(ph * PIF) * r * 0.05f * (energy - 0.8f) * 5f
            }
            if (gaze != null) { lookX = gaze.x; lookY = gaze.y }

            // Mid-game: no wandering, hopping or bobbing (a bonk still makes it flinch).
            if (holdStill && react != Reaction.STARTLE) { dx = 0f; dy = 0f; sx = 1f; sy = 1f }

            // -- Easing: nothing jumps. Each value glides toward its target at its own pace. --
            val dt = (t - sm.lastT).coerceIn(0f, 0.1f)
            sm.lastT = t
            fun ease(rate: Float) = 1f - kotlin.math.exp(-rate * dt)
            if (!sm.started) {
                sm.started = true
                sm.lookX = lookX; sm.lookY = lookY; sm.tint = tint
            }
            sm.lookX += (lookX - sm.lookX) * ease(9f)
            sm.lookY += (lookY - sm.lookY) * ease(9f)
            sm.dx += (dx - sm.dx) * ease(22f)
            sm.dy += (dy - sm.dy) * ease(22f)
            sm.sx += (sx - sm.sx) * ease(22f)
            sm.sy += (sy - sm.sy) * ease(22f)
            sm.wobble += (wobble - sm.wobble) * ease(22f)
            sm.tint = lerp(sm.tint, tint, ease(4f))
            // Phases advance by elapsed time, so a change of speed never makes them skip.
            sm.breath += dt * TAU / (9f - 4f * energy)
            sm.drift += dt * (0.6f + 0.8f * energy) / 14f
            sm.energyAmp += ((0.04f + 0.06f * energy) - sm.energyAmp) * ease(2f)
            val breatheNow = if (holdStill) 1f else if (idleStill) 1f + 0.08f * voice + 0.12f * mic else 0.99f + sm.energyAmp * sin(sm.breath) + 0.08f * voice + 0.12f * mic
            val angle = (sm.drift % 1f) * TAU
            val shift = if (holdStill || idleStill) Offset.Zero else Offset(cos(angle) * r * 0.18f, sin(angle) * r * 0.18f)
            val body = c + Offset(sm.dx, sm.dy)
            // Mood, accessory and prop changes cross-fade instead of popping.
            if (mood != sm.mood) { sm.prevMood = sm.mood; sm.mood = mood; sm.moodAt = t }
            if (accessory != sm.accessory) { sm.prevAccessory = sm.accessory; sm.accessory = accessory; sm.accessoryAt = t }
            if (prop != sm.prop) { sm.prevProp = sm.prop; sm.prop = prop; sm.propAt = t }
            val moodF = ((t - sm.moodAt) / 0.28f).coerceIn(0f, 1f)
            val accF = ((t - sm.accessoryAt) / 0.35f).coerceIn(0f, 1f)
            val propF = ((t - sm.propAt) / 0.35f).coerceIn(0f, 1f)
            val tintNow = sm.tint

            // -- Behind the body: weather and the alert glow ---------------------------------
            if (!mini) {
                val weatherNow = when (forced("weather")) {
                    null -> weather?.summary
                    "HEAVY_RAIN" -> "Heavy rain"
                    "THUNDERSTORMS" -> "Thunderstorms"
                    else -> forced("weather")!!.lowercase()
                }
                drawWeather(weatherNow, body, r, t)
                if (playingMusic) drawNotes(body + Offset(-r * 0.55f, -r * 0.35f), r, t, vibe)
                drawHoliday(forced("holiday")?.let(::holidayCalendar) ?: cal, body, r, t, weatherNow)
            }
            if ((alertGlow || forcedState == "ALERT_GLOW") && !mini) {
                val a = 0.22f + 0.18f * sin(t * 3f)
                drawCircle(Brush.radialGradient(listOf(Color.Transparent, Palette.amber.copy(alpha = a), Color.Transparent), body, r * 1.05f), r * 1.05f, body)
            }

            // -- The body ----------------------------------------------------------------
            withTransform({
                translate(sm.dx, sm.dy)
                scale(breatheNow * sm.sx, breatheNow * sm.sy, Offset(c.x, c.y + r * 0.5f))
                rotate(sm.wobble, c)
            }) {
                val tint = tintNow
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

                // A blink closes and opens the lids smoothly instead of switching.
                val bp = ((t - blinkAt) / 0.22f)
                val lid = if (bp in 0f..1f) sin(bp * PIF) else 0f
                val faceAt = core + Offset(0f, -r * 0.03f)
                val look = Offset(sm.lookX, sm.lookY)
                if (moodF < 1f) drawFace(faceAt, r, sm.prevMood, look, lid, voice, munch, react, p, t, eyeInk.copy(alpha = 0.88f * (1 - moodF)))
                drawFace(faceAt, r, mood, look, lid, voice, munch, react, p, t, eyeInk.copy(alpha = 0.88f * moodF))
                // The sniffles: a rosy nose and flushed cheeks.
                if (sniffles) {
                    drawCircle(Color(0xFFFF6B7A).copy(alpha = 0.85f), r * 0.04f, faceAt + Offset(0f, r * 0.07f))
                    for (sd in listOf(-1f, 1f)) drawOval(Color(0xFFFF7A9A).copy(alpha = 0.35f), faceAt + Offset(sd * r * 0.2f - r * 0.06f, r * 0.07f), Size(r * 0.12f, r * 0.06f))
                }
                if (tucked || sickBlanket || react == Reaction.TUCK_IN || forcedState == "TUCKED_IN") drawBlanket(core, r, if (react == Reaction.TUCK_IN) p else 1f, t)
                popIn(accF, core) { sm.accessory?.let { drawAccessory(it, core, r, t) } }
                if (accF < 1f) popIn(1f - accF, core) { sm.prevAccessory?.let { drawAccessory(it, core, r, t) } }
                if (!mini) {
                    val hand = core + Offset(-r * 0.44f, r * 0.36f)
                    popIn(propF, hand) { sm.prop?.let { drawHeld(it, core, r, t, sand, flipP, tint) } }
                    if (propF < 1f) popIn(1f - propF, hand) { sm.prevProp?.let { drawHeld(it, core, r, t, sand, flipP, tint) } }
                    if (react == Reaction.TEAR_PAGE) drawTornPage(hand, r, p)
                }
                if ((museBusy || forcedState == "BUSY_GEAR") && talk == Talk.IDLE && !mini) drawGear(core + Offset(r * 0.52f, -r * 0.46f), r * 0.09f, t * 1.5f)
                if (mood == Mood.SLEEPY && react == null && !mini) drawDream(core, r, t, weather, dreamPhoto, dreamTopics)
                if (react == Reaction.COVER_EARS) drawEarCovers(core, r, p, tint)
                if ((muted || forcedState == "MUTED") && !mini) drawMuted(core + Offset(-r * 0.5f, -r * 0.42f), r * 0.07f)
            }

            // A new album started on the laptop: a little record spins beside it for a while.
            if (!mini && nowMs - GadgetState.vinylAt < 20_000) {
                val fade = (1f - (nowMs - GadgetState.vinylAt - 17_000) / 3_000f).coerceIn(0f, 1f)
                drawVinyl(body + Offset(r * 0.62f, r * 0.42f), r * 0.17f, t, fade)
            }

            // -- In front: confetti --------------------------------------------------------
            if (react == Reaction.CELEBRATE) drawConfetti(body, r, (nowMs - active!!.atMs) / 1000f, p)

            // Thinking out loud: a thought cloud with dots while waiting, then the first words as they stream in.
            if (!mini) {
                if (replyText.length != sm.replyLen) { sm.replyLen = replyText.length; sm.replyAt = t }
                // Only for questions asked here, not messages Pixel sends on its own.
                val streaming = turnActive && talk == Talk.REPLIED && !speaking && replyText.isNotBlank() && t - sm.replyAt < 4f
                val waiting = turnActive && (talk == Talk.SENDING || talk == Talk.WAITING)
                val thinkTarget = if (waiting || streaming || forcedState == "THINKING") 1f else 0f
                sm.think += (thinkTarget - sm.think) * ease(8f)
                thinking.value = sm.think
                if (sm.think > 0.02f) {
                    val words = if (streaming) replyText.split(Regex("\\s+")).take(6).joinToString(" ") + "…" else null
                    drawThought(body + Offset(r * 0.5f, -r * 0.92f), r, t, sm.think, words, textMeasurer)
                }
            }
        }

        // Speech bubble, up and to the left, with a tail pointing down at the blob.
        // It pops in from the tail and fades away, keeping its last words while it goes.
        val lastBubble = remember { mutableStateOf("") }
        bubbleShown?.let { if (lastBubble.value != it) lastBubble.value = it }
        androidx.compose.animation.AnimatedVisibility(
            visible = !mini && bubbleShown != null,
            modifier = Modifier.align(Alignment.TopStart).layout { m, cons ->
                val placeable = m.measure(cons.copy(minWidth = 0, maxWidth = 270.dp.roundToPx()))
                // Bottom of the column (the tail's tip) lands just above the blob's upper-left edge.
                layout(placeable.width, placeable.height) {
                    placeable.place(4.dp.roundToPx(), 76.dp.roundToPx() - placeable.height)
                }
            },
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                androidx.compose.animation.scaleIn(androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 500f),
                    initialScale = 0.5f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.2f, 1f)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(250)) +
                androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(250), targetScale = 0.7f,
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.2f, 1f)),
        ) {
            val bubbleInk = Palette.ink.copy(alpha = 0.94f)
            androidx.compose.foundation.layout.Column {
                Text(
                    lastBubble.value, color = Palette.night, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
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
        // Time to head out for something with an address (see Chores.leaveBy). Guest mode skips
        // everything personal and keeps only the server sign.
        if (!guest) run {
            val nowCal = Calendar.getInstance().apply { timeInMillis = tick }
            val nowMin = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)
            leaveBy.entries.filter { it.value - nowMin in -10..45 }.minByOrNull { it.value }?.let { (key, at) ->
                val title = key.substringBefore('|')
                val clock = "%d:%02d".format(((at / 60 + 11) % 12) + 1, at % 60)
                cards += (if (at - nowMin <= 0) "🚗 Leave now for $title" else "🚗 Leave by $clock · $title") to Palette.coral
            }
        }
        if (!guest) soonEvent?.let { (title, mins) ->
            cards += when {
                mins > 0 -> "⏰ $title in $mins min"
                mins == 0 -> "⏰ $title now"
                else -> "⏰ $title started"
            } to Palette.teal
        }
        if (!guest) held?.takeIf { it.untilMs > tick && it.text.isNotEmpty() }?.let { cards += it.text to Palette.ink }
        if (!guest) nextReminder(reminderDay, tick)?.let { cards += "📝 $it" to Color(0xFFFFE680) }
        // The next piece of schoolwork due within a week.
        if (!guest) work.mapNotNull { a -> dueInDays(a)?.let { a to it } }.filter { it.second in 0..6 }.minByOrNull { it.second }?.let { (a, d) ->
            cards += "📚 ${a.title} · due ${dueLabel(d).lowercase().let { if (d >= 2) it.replaceFirstChar { c -> c.uppercase() } else it }}" to dueColor(d)
        }
        val tickCal = Calendar.getInstance().apply { timeInMillis = tick }
        if (tickCal.get(Calendar.HOUR_OF_DAY) >= 18 && !quiet && !guest) {
            tomorrowEvents(agenda).firstOrNull()?.let { e ->
                cards += ("🎒 Tomorrow: " + (if (e.time.isNotBlank()) "${e.time} " else "") + e.title) to Palette.violet.copy(alpha = 0.9f).let { lerp(it, Palette.ink, 0.5f) }
            }
        }
        if (!guest) specialDays.firstOrNull()?.let { d ->
            sleepsUntil(d)?.takeIf { it in 0..60 }?.let { n ->
                val e = d.emoji.ifEmpty { "🗓" }
                cards += (if (n == 0) "$e ${d.title} is today!" else "$e $n sleep${if (n == 1) "" else "s"} until ${d.title}") to Palette.amber
            }
        }
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
        if (cards.size > 3) cards.subList(3, cards.size).clear()
        // Shown by the home screen just above the quick-action buttons, so neither hides the other.
        val published = cards.map { (text, color) -> text to color.toArgb().toLong() }
        LaunchedEffect(published, mini) { if (!mini && !follower) GadgetState.setBlobCards(published) }
    }
}

/** The next agenda item starting within 10 minutes (or started up to 5 minutes ago), as title to minutes away. */
private fun upcomingEvent(agenda: AgendaDay, nowMs: Long): Pair<String, Int>? {
    val today = SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date(nowMs))
    if (!BlobBrain.isToday(agenda.date, java.time.LocalDate.now())) return null
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
    val guest = GadgetState.guestMode()
    if (!guest && BlobBrain.isToday(agenda.date, java.time.LocalDate.now())) {
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
    if (!guest) GadgetState.specialDays.value.firstOrNull()?.let { d ->
        val days = sleepsUntil(d)
        if (days != null && days in 0..60) parts += if (days == 0) "${d.title} is today!" else "$days sleep${if (days == 1) "" else "s"} until ${d.title}"
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
    at: Offset, r: Float, mood: Mood, look: Offset, lid: Float,
    voice: Float, munch: Float, react: Reaction?, p: Float, t: Float,
    ink: Color = eyeInk,
) {
    if (ink.alpha <= 0.01f) return
    @Suppress("NAME_SHADOWING") val eyeInk = ink
    val open = 1f - 0.88f * lid
    val gap = r * 0.17f
    val w = r * 0.075f
    val h = r * 0.14f
    val stroke = Stroke(width = r * 0.04f, cap = StrokeCap.Round)
    val lk = Offset(look.x * r * 0.06f, look.y * r * 0.05f)
    val left = at + Offset(-gap, 0f) + lk
    val right = at + Offset(gap, 0f) + lk

    fun oval(center: Offset, scale: Float = 1f, squash: Float = 1f) {
        val ew = w * scale
        val eh = h * squash * scale * open
        drawOval(eyeInk, Offset(center.x - ew, center.y - eh), Size(ew * 2, eh * 2))
    }
    // Half-closed: just the lower half of the eye under a heavy lid.
    fun lidded(center: Offset) {
        val eh = h * 0.75f * open
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

    if (react == Reaction.COVER_EARS) {
        // Squeezed shut: > <
        for ((eye, dir) in listOf(left to 1f, right to -1f)) {
            drawLine(eyeInk, eye + Offset(-w * dir, -h * 0.5f), eye + Offset(w * dir, 0f), r * 0.03f, StrokeCap.Round)
            drawLine(eyeInk, eye + Offset(w * dir, 0f), eye + Offset(-w * dir, h * 0.5f), r * 0.03f, StrokeCap.Round)
        }
        smile(w * 1.0f, -h * 0.3f)
        return
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
        else -> drawCostume(a, core, top, r, t)
    }
}

/** The raincoat and the monthly costumes from Puff's closet. */
private fun DrawScope.drawCostume(a: Accessory, core: Offset, top: Offset, r: Float, t: Float) {
    val eyes = core + Offset(0f, -r * 0.03f)
    when (a) {
        Accessory.RAINCOAT -> {
            // A yellow hood over the head and a collar with buttons.
            val yellow = Color(0xFFFFD23F)
            drawArc(yellow, 180f, 180f, true, Offset(core.x - r * 0.5f, top.y - r * 0.16f), Size(r * 1.0f, r * 0.62f))
            drawArc(Color(0xFFE0B52A), 180f, 180f, false, Offset(core.x - r * 0.5f, top.y - r * 0.16f), Size(r * 1.0f, r * 0.62f), style = Stroke(r * 0.03f))
            val y = core.y + r * 0.3f
            drawRoundRect(yellow, Offset(core.x - r * 0.42f, y), Size(r * 0.84f, r * 0.16f), CornerRadius(r * 0.06f))
            for (i in -1..1) drawCircle(Color(0xFF3B6EA8), r * 0.025f, Offset(core.x + i * r * 0.16f, y + r * 0.08f))
        }
        Accessory.EARMUFFS -> {
            drawArc(Color(0xFFB0B8C8), 200f, 140f, false, core - Offset(r * 0.5f, r * 0.55f), Size(r * 1.0f, r * 1.0f), style = Stroke(r * 0.04f, cap = StrokeCap.Round))
            for (side in listOf(-1f, 1f)) {
                val c = Offset(core.x + side * r * 0.48f, core.y - r * 0.1f)
                drawCircle(Color(0xFFFFB3C7), r * 0.11f, c)
                for (k in 0 until 6) drawCircle(Color.White.copy(alpha = 0.5f), r * 0.025f, c + Offset(cos(k * TAU / 6) * r * 0.06f, sin(k * TAU / 6) * r * 0.06f))
            }
        }
        Accessory.HEART_GLASSES -> for (side in listOf(-1f, 1f)) {
            val c = Offset(eyes.x + side * r * 0.17f, eyes.y)
            drawHeart(c + Offset(0f, -r * 0.02f), r * 0.075f, Color(0xFFFF4F7B))
            drawLine(Color(0xFFFF4F7B), Offset(eyes.x - r * 0.06f, eyes.y - r * 0.02f), Offset(eyes.x + r * 0.06f, eyes.y - r * 0.02f), r * 0.025f)
        }
        Accessory.CLOVER_HAT -> rotate(-6f, top) {
            val green = Color(0xFF2E9E5B)
            drawRect(green, Offset(top.x - r * 0.17f, top.y - r * 0.3f), Size(r * 0.34f, r * 0.3f))
            drawOval(green, Offset(top.x - r * 0.32f, top.y - r * 0.05f), Size(r * 0.64f, r * 0.1f))
            drawRect(Color(0xFF1A1A1A), Offset(top.x - r * 0.17f, top.y - r * 0.1f), Size(r * 0.34f, r * 0.05f))
            val c = Offset(top.x + r * 0.08f, top.y - r * 0.2f)
            for (k in 0 until 3) drawCircle(Color(0xFF7FE0A8), r * 0.035f, c + Offset(cos(k * TAU / 3 - 1.57f) * r * 0.035f, sin(k * TAU / 3 - 1.57f) * r * 0.035f))
        }
        Accessory.BUNNY_EARS -> for (side in listOf(-1f, 1f)) {
            val base = Offset(core.x + side * r * 0.16f, top.y + r * 0.02f)
            rotate(side * 12f + sin(t * 2f + side) * 4f, base) {
                drawOval(Color.White, Offset(base.x - r * 0.08f, base.y - r * 0.42f), Size(r * 0.16f, r * 0.44f))
                drawOval(Color(0xFFFFB3C7), Offset(base.x - r * 0.04f, base.y - r * 0.36f), Size(r * 0.08f, r * 0.32f))
            }
        }
        Accessory.FLOWER_CROWN -> for (k in 0 until 7) {
            val x = core.x - r * 0.36f + k * r * 0.12f
            val y = top.y + r * 0.04f - sin(k / 6f * PI.toFloat()) * r * 0.08f
            val col = listOf(Color(0xFFFFB3DE), Color(0xFFFFE07A), Color(0xFFB3D8FF))[k % 3]
            for (p in 0 until 5) drawCircle(col, r * 0.03f, Offset(x + cos(p * TAU / 5) * r * 0.03f, y + sin(p * TAU / 5) * r * 0.03f))
            drawCircle(Color(0xFFFFC94D), r * 0.018f, Offset(x, y))
        }
        Accessory.SUNHAT -> rotate(-5f, top) {
            val straw = Color(0xFFF2D49B)
            drawOval(straw, Offset(top.x - r * 0.52f, top.y - r * 0.06f), Size(r * 1.04f, r * 0.16f))
            drawArc(straw, 180f, 180f, true, Offset(top.x - r * 0.24f, top.y - r * 0.26f), Size(r * 0.48f, r * 0.5f))
            drawRect(Color(0xFFFF7A8A), Offset(top.x - r * 0.24f, top.y - r * 0.06f), Size(r * 0.48f, r * 0.05f))
        }
        Accessory.STAR_GLASSES -> for (side in listOf(-1f, 1f)) {
            val c = Offset(eyes.x + side * r * 0.17f, eyes.y)
            drawStar5(c, r * 0.13f, Color(0xFF4F8BFF))
            drawCircle(Color.White.copy(alpha = 0.35f + 0.35f * kotlin.math.abs(sin(t * 3f + side))), r * 0.02f, c + Offset(-r * 0.04f, -r * 0.04f))
        }
        Accessory.SNORKEL -> {
            drawRoundRect(Color(0xFF45D3C7).copy(alpha = 0.85f), Offset(eyes.x - r * 0.3f, eyes.y - r * 0.1f), Size(r * 0.6f, r * 0.2f), CornerRadius(r * 0.08f))
            drawRoundRect(Color(0xFFBFE6F5).copy(alpha = 0.6f), Offset(eyes.x - r * 0.26f, eyes.y - r * 0.07f), Size(r * 0.52f, r * 0.14f), CornerRadius(r * 0.06f))
            val tube = Offset(core.x + r * 0.34f, eyes.y)
            drawLine(Color(0xFFFF9A3C), tube, tube + Offset(0f, -r * 0.42f), r * 0.06f, StrokeCap.Round)
            drawLine(Color(0xFFFF9A3C), tube + Offset(0f, -r * 0.42f), tube + Offset(-r * 0.06f, -r * 0.46f), r * 0.06f, StrokeCap.Round)
        }
        Accessory.GRAD_CAP -> rotate(-8f, top) {
            val ink = Color(0xFF26223A)
            drawRect(ink, Offset(top.x - r * 0.16f, top.y - r * 0.12f), Size(r * 0.32f, r * 0.12f))
            drawPath(Path().apply {
                moveTo(top.x - r * 0.36f, top.y - r * 0.14f); lineTo(top.x, top.y - r * 0.24f); lineTo(top.x + r * 0.36f, top.y - r * 0.14f)
                lineTo(top.x, top.y - r * 0.05f); close()
            }, ink)
            drawLine(Color(0xFFFFC94D), Offset(top.x, top.y - r * 0.15f), Offset(top.x + r * 0.28f, top.y - r * 0.08f), r * 0.02f)
            drawLine(Color(0xFFFFC94D), Offset(top.x + r * 0.28f, top.y - r * 0.08f), Offset(top.x + r * 0.28f + sin(t * 2f) * r * 0.02f, top.y + r * 0.08f), r * 0.025f)
        }
        Accessory.SCARECROW_HAT -> rotate(-10f, top) {
            val straw = Color(0xFFD9B26A)
            drawOval(straw, Offset(top.x - r * 0.46f, top.y - r * 0.06f), Size(r * 0.92f, r * 0.14f))
            drawPath(Path().apply {
                moveTo(top.x - r * 0.2f, top.y); lineTo(top.x - r * 0.12f, top.y - r * 0.3f); lineTo(top.x + r * 0.14f, top.y - r * 0.26f); lineTo(top.x + r * 0.2f, top.y); close()
            }, Color(0xFFB8893C))
            drawRect(Color(0xFF7A4A2A), Offset(top.x - r * 0.18f, top.y - r * 0.08f), Size(r * 0.37f, r * 0.05f))
            // Straw poking out from under the brim.
            for (k in -3..3) drawLine(straw, Offset(top.x + k * r * 0.12f, top.y + r * 0.04f), Offset(top.x + k * r * 0.13f + r * 0.02f, top.y + r * 0.12f), r * 0.015f)
        }
        Accessory.LEAF_CROWN -> for (k in 0 until 6) {
            val x = core.x - r * 0.32f + k * r * 0.13f
            val y = top.y + r * 0.03f - sin(k / 5f * PI.toFloat()) * r * 0.08f
            val col = listOf(Color(0xFFE0782A), Color(0xFFC0392B), Color(0xFFF2B33D))[k % 3]
            rotate(-30f + k * 12f, Offset(x, y)) { drawOval(col, Offset(x - r * 0.05f, y - r * 0.09f), Size(r * 0.1f, r * 0.17f)) }
        }
        Accessory.ANTLERS -> for (side in listOf(-1f, 1f)) {
            val brown = Color(0xFF8A5A3C)
            val base = Offset(core.x + side * r * 0.2f, top.y + r * 0.04f)
            val tip = base + Offset(side * r * 0.12f, -r * 0.34f)
            drawLine(brown, base, tip, r * 0.045f, StrokeCap.Round)
            drawLine(brown, base + Offset(side * r * 0.04f, -r * 0.14f), base + Offset(side * r * 0.2f, -r * 0.2f), r * 0.035f, StrokeCap.Round)
            drawLine(brown, base + Offset(side * r * 0.08f, -r * 0.25f), base + Offset(-side * r * 0.04f, -r * 0.36f), r * 0.03f, StrokeCap.Round)
            if (side > 0) drawCircle(Color(0xFFE5484D), r * 0.05f, core + Offset(0f, r * 0.06f))
        }
        else -> {}
    }
}

private fun DrawScope.drawStar5(c: Offset, s: Float, color: Color) {
    drawPath(Path().apply {
        for (k in 0 until 10) {
            val a = -PI.toFloat() / 2 + k * PI.toFloat() / 5
            val rr = if (k % 2 == 0) s * 0.6f else s * 0.26f
            val p = c + Offset(cos(a) * rr, sin(a) * rr)
            if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }, color)
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

/** Humming along: little music notes floating up beside it (faster for loud songs). */
private fun DrawScope.drawNotes(from: Offset, r: Float, t: Float, vibe: String) {
    val speed = if (vibe == "hype") 2.6f else if (vibe == "calm") 5.5f else 3.8f
    for (i in 0 until 3) {
        val phase = ((t / speed) + i / 3f) % 1f
        val pos = from + Offset(-phase * r * 0.2f + sin(phase * TAU * 1.5f) * r * 0.06f, -phase * r * 0.6f)
        val alpha = (if (phase < 0.15f) phase / 0.15f else 1f - (phase - 0.15f) / 0.85f) * 0.85f
        val ink = (if (i % 2 == 0) Palette.violet else Palette.teal).copy(alpha = alpha)
        val s = r * 0.045f
        drawOval(ink, Offset(pos.x - s * 1.2f, pos.y - s * 0.8f), Size(s * 2.4f, s * 1.6f))
        drawLine(ink, Offset(pos.x + s * 1.05f, pos.y), Offset(pos.x + s * 1.05f, pos.y - s * 4f), r * 0.018f)
        drawLine(ink, Offset(pos.x + s * 1.05f, pos.y - s * 4f), Offset(pos.x + s * 2.2f, pos.y - s * 3f), r * 0.018f)
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
private fun DrawScope.drawHeld(item: HeldItem, core: Offset, r: Float, t: Float, sand: Float = 0f, flip: Float = 1f, tint: Color = Palette.violet) {
    val a = core + Offset(-r * 0.44f, r * 0.36f)
    when (item) {
        HeldItem.NONE -> {}
        HeldItem.FORTUNE -> {
            // A fortune cookie cracked in two, the little slip of paper sticking out.
            val tan = Color(0xFFE8B86A)
            for (side in listOf(-1f, 1f)) drawArc(tan, if (side < 0) 90f else -90f, 180f, true,
                Offset(a.x + side * r * 0.07f - r * 0.09f, a.y - r * 0.08f), Size(r * 0.18f, r * 0.16f))
            rotate(-12f, a) { drawRect(Color.White, Offset(a.x - r * 0.03f, a.y - r * 0.2f), Size(r * 0.06f, r * 0.2f)) }
            drawLine(Palette.coral, Offset(a.x - r * 0.01f, a.y - r * 0.16f), Offset(a.x + r * 0.02f, a.y - r * 0.06f), r * 0.01f)
        }
        HeldItem.MIC -> {
            drawLine(Color(0xFF55596A), a + Offset(0f, -r * 0.05f), a + Offset(0f, r * 0.14f), r * 0.03f, StrokeCap.Round)
            drawRoundRect(Color(0xFF8C93A8), Offset(a.x - r * 0.06f, a.y - r * 0.2f), Size(r * 0.12f, r * 0.17f), CornerRadius(r * 0.06f))
            for (k in 1..3) drawLine(Color(0xFF55596A), Offset(a.x - r * 0.05f, a.y - r * 0.2f + k * r * 0.04f), Offset(a.x + r * 0.05f, a.y - r * 0.2f + k * r * 0.04f), r * 0.008f)
        }
        HeldItem.WATERING_CAN -> rotate(-20f + sin(t * 2f) * 4f, a) {
            // Tilted, pouring a few drops.
            val can = Color(0xFF6FB3A8)
            drawRoundRect(can, Offset(a.x - r * 0.1f, a.y - r * 0.08f), Size(r * 0.2f, r * 0.16f), CornerRadius(r * 0.03f))
            drawLine(can, Offset(a.x + r * 0.09f, a.y - r * 0.02f), Offset(a.x + r * 0.2f, a.y - r * 0.1f), r * 0.03f, StrokeCap.Round)
            drawArc(can, 180f, 180f, false, Offset(a.x - r * 0.07f, a.y - r * 0.15f), Size(r * 0.12f, r * 0.12f), style = Stroke(width = r * 0.02f))
            for (i in 0..2) {
                val p = ((t * 1.5f + i * 0.33f) % 1f)
                drawCircle(Color(0xFF9FE3F0).copy(alpha = 1f - p), r * 0.012f, Offset(a.x + r * (0.22f + 0.02f * i), a.y - r * 0.08f + p * r * 0.25f))
            }
        }
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
        HeldItem.SUITCASE -> {
            drawRoundRect(cardboard, Offset(a.x - r * 0.14f, a.y - r * 0.09f), Size(r * 0.28f, r * 0.19f), CornerRadius(r * 0.03f))
            drawArc(Palette.ink, 180f, 180f, false, Offset(a.x - r * 0.05f, a.y - r * 0.15f), Size(r * 0.1f, r * 0.1f),
                style = Stroke(width = r * 0.02f))
            drawLine(tape, Offset(a.x - r * 0.14f, a.y), Offset(a.x + r * 0.14f, a.y), r * 0.015f)
            drawCircle(Palette.coral, r * 0.025f, a + Offset(r * 0.08f, r * 0.04f))
        }
        HeldItem.CAMERA -> {
            drawRoundRect(Palette.ink, Offset(a.x - r * 0.14f, a.y - r * 0.08f), Size(r * 0.28f, r * 0.18f), CornerRadius(r * 0.03f))
            drawRect(Palette.ink, Offset(a.x - r * 0.05f, a.y - r * 0.12f), Size(r * 0.1f, r * 0.05f))
            drawCircle(Palette.night, r * 0.06f, a + Offset(0f, r * 0.01f))
            drawCircle(Palette.teal, r * 0.035f, a + Offset(0f, r * 0.01f))
            // Flash every couple of seconds.
            if ((t % 2.5f) < 0.12f) drawCircle(Color.White.copy(alpha = 0.6f), r * 0.12f, a + Offset(r * 0.1f, -r * 0.08f))
        }
        HeldItem.BAG -> rotate(-10f + sin(t * 1.1f) * 3f, a) {
            // A backpack held up by its top loop: rounded body, flap, front pocket with a zip, side straps.
            val body = lerp(Palette.teal, Color(0xFF2E8C85), 0.25f)
            val dark = lerp(body, Palette.night, 0.35f)
            drawRoundRect(Color.Black.copy(alpha = 0.22f), Offset(a.x - r * 0.11f, a.y - r * 0.1f), Size(r * 0.25f, r * 0.29f), CornerRadius(r * 0.07f))
            drawArc(Palette.ink.copy(alpha = 0.85f), 180f, 180f, false, Offset(a.x - r * 0.045f, a.y - r * 0.2f), Size(r * 0.09f, r * 0.09f), style = Stroke(width = r * 0.02f))
            drawRoundRect(body, Offset(a.x - r * 0.12f, a.y - r * 0.13f), Size(r * 0.24f, r * 0.29f), CornerRadius(r * 0.07f))
            // Flap over the top.
            drawRoundRect(dark, Offset(a.x - r * 0.12f, a.y - r * 0.13f), Size(r * 0.24f, r * 0.09f), CornerRadius(r * 0.06f))
            drawCircle(Palette.amber, r * 0.017f, Offset(a.x, a.y - r * 0.05f))
            // Front pocket and its zip.
            drawRoundRect(dark, Offset(a.x - r * 0.08f, a.y + r * 0.02f), Size(r * 0.16f, r * 0.1f), CornerRadius(r * 0.03f))
            drawLine(Palette.ink.copy(alpha = 0.6f), Offset(a.x - r * 0.06f, a.y + r * 0.045f), Offset(a.x + r * 0.06f, a.y + r * 0.045f), r * 0.01f)
            // Straps down the sides.
            for (sx in listOf(-1f, 1f)) drawLine(dark, Offset(a.x + sx * r * 0.1f, a.y - r * 0.05f), Offset(a.x + sx * r * 0.1f, a.y + r * 0.13f), r * 0.02f)
        }
        HeldItem.CALENDAR -> {
            drawRect(Color.White, Offset(a.x - r * 0.11f, a.y - r * 0.1f), Size(r * 0.22f, r * 0.2f))
            drawRect(Palette.coral, Offset(a.x - r * 0.11f, a.y - r * 0.1f), Size(r * 0.22f, r * 0.06f))
            for (i in 0..2) for (j in 0..1) drawCircle(Palette.night.copy(alpha = 0.5f), r * 0.012f, a + Offset(-r * 0.06f + i * r * 0.06f, r * 0.0f + j * r * 0.05f))
        }
        HeldItem.NOTE -> {
            // A little notepad held up in its "hand": tilted, a soft shadow, a spiral on top, and
            // the hand itself over the bottom corner.
            rotate(-14f + sin(t * 1.2f) * 3f, a) {
                drawRoundRect(Color.Black.copy(alpha = 0.25f), Offset(a.x - r * 0.1f, a.y - r * 0.1f), Size(r * 0.24f, r * 0.27f), CornerRadius(r * 0.02f))
                drawRoundRect(Color(0xFFFFE680), Offset(a.x - r * 0.12f, a.y - r * 0.13f), Size(r * 0.24f, r * 0.27f), CornerRadius(r * 0.02f))
                for (i in 0..3) drawCircle(Palette.night.copy(alpha = 0.5f), r * 0.012f, Offset(a.x - r * 0.08f + i * r * 0.053f, a.y - r * 0.13f))
                for (i in 0..2) drawLine(Palette.night.copy(alpha = 0.35f), Offset(a.x - r * 0.08f, a.y - r * 0.06f + i * r * 0.055f),
                    Offset(a.x + r * 0.08f, a.y - r * 0.06f + i * r * 0.055f), r * 0.012f)
            }
            drawCircle(tint, r * 0.065f, a + Offset(r * 0.09f, r * 0.12f))
        }
        HeldItem.SERVER -> {
            for (i in 0..2) {
                val y = a.y - r * 0.12f + i * r * 0.085f
                drawRoundRect(Palette.muted, Offset(a.x - r * 0.12f, y), Size(r * 0.24f, r * 0.07f), CornerRadius(r * 0.015f))
                val on = (t * 3f + i).toInt() % 3 != 0
                drawCircle(if (on) Palette.teal else Palette.night, r * 0.012f, Offset(a.x + r * 0.08f, y + r * 0.035f))
            }
        }
        HeldItem.SUN -> {
            drawCircle(Palette.amber, r * 0.07f, a)
            for (i in 0 until 8) {
                val ang = i * TAU / 8 + t * 0.4f
                drawLine(Palette.amber, a + Offset(cos(ang) * r * 0.1f, sin(ang) * r * 0.1f), a + Offset(cos(ang) * r * 0.14f, sin(ang) * r * 0.14f), r * 0.02f, StrokeCap.Round)
            }
        }
        HeldItem.HOURGLASS -> drawHourglass(a, r, sand, flip, t)
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

/** Things from the day worth dreaming about. */
internal enum class DreamTopic { TV, OUT, TALK, TIMER, WATER, MUSIC, FRIEND, POKE }

internal fun dreamTopics(f: com.rusty.museecho.gadget.DayFacts): List<DreamTopic> = buildList {
    if (f.tvMinutes >= 15) add(DreamTopic.TV)
    if (f.events.any { "went out" in it }) add(DreamTopic.OUT)
    if (f.talks > 0) add(DreamTopic.TALK)
    if (f.timers > 0) add(DreamTopic.TIMER)
    if (f.nudges > 0) add(DreamTopic.WATER)
    if (f.musicMinutes >= 10) add(DreamTopic.MUSIC)
    if (f.friendVisits > 0) add(DreamTopic.FRIEND)
    if (f.pokes > 0) add(DreamTopic.POKE)
}

private fun DrawScope.drawVinyl(c: Offset, rad: Float, t: Float, alpha: Float) {
    drawCircle(Color(0xFF151218).copy(alpha = alpha), rad, c)
    for (i in 1..3) drawCircle(Color.White.copy(alpha = 0.08f * alpha), rad * (0.45f + i * 0.15f), c, style = Stroke(rad * 0.03f))
    drawCircle(Palette.coral.copy(alpha = alpha), rad * 0.3f, c)
    val a = t * 4f
    drawLine(Color.White.copy(alpha = 0.35f * alpha), c + Offset(cos(a) * rad * 0.35f, sin(a) * rad * 0.35f),
        c + Offset(cos(a) * rad * 0.9f, sin(a) * rad * 0.9f), rad * 0.06f, StrokeCap.Round)
    drawCircle(Palette.night.copy(alpha = alpha), rad * 0.06f, c)
}

/** A doodle of something from the day, inside the dream cloud at [d]. */
private fun DrawScope.drawDayDoodle(topic: DreamTopic, d: Offset, r: Float, t: Float) {
    val ink = Palette.ink.copy(alpha = 0.8f)
    when (topic) {
        DreamTopic.TV -> {
            drawRoundRect(ink, Offset(d.x - r * 0.09f, d.y - r * 0.06f), Size(r * 0.18f, r * 0.12f), CornerRadius(r * 0.02f), style = Stroke(r * 0.015f))
            drawRect(Palette.teal.copy(alpha = 0.5f + 0.3f * sin(t * 3f)), Offset(d.x - r * 0.07f, d.y - r * 0.045f), Size(r * 0.14f, r * 0.09f))
            drawLine(ink, d + Offset(-r * 0.04f, -r * 0.06f), d + Offset(-r * 0.07f, -r * 0.1f), r * 0.012f)
            drawLine(ink, d + Offset(r * 0.04f, -r * 0.06f), d + Offset(r * 0.07f, -r * 0.1f), r * 0.012f)
        }
        DreamTopic.OUT -> {
            // A little door, ajar.
            drawRect(Palette.amber.copy(alpha = 0.8f), Offset(d.x - r * 0.06f, d.y - r * 0.1f), Size(r * 0.12f, r * 0.19f))
            drawRect(Palette.night.copy(alpha = 0.7f), Offset(d.x - r * 0.06f, d.y - r * 0.1f), Size(r * 0.05f, r * 0.19f))
            drawCircle(Palette.night, r * 0.012f, d + Offset(r * 0.035f, 0f))
        }
        DreamTopic.TALK -> {
            drawRoundRect(ink, Offset(d.x - r * 0.09f, d.y - r * 0.06f), Size(r * 0.18f, r * 0.1f), CornerRadius(r * 0.04f))
            drawPath(Path().apply { moveTo(d.x - r * 0.04f, d.y + r * 0.04f); lineTo(d.x - r * 0.06f, d.y + r * 0.08f); lineTo(d.x, d.y + r * 0.04f); close() }, ink)
            for (i in -1..1) drawCircle(Palette.night, r * 0.012f, d + Offset(i * r * 0.04f, -r * 0.01f))
        }
        DreamTopic.TIMER -> {
            drawCircle(ink, r * 0.08f, d, style = Stroke(r * 0.015f))
            val a = t * 1.5f
            drawLine(ink, d, d + Offset(cos(a) * r * 0.06f, sin(a) * r * 0.06f), r * 0.015f, StrokeCap.Round)
        }
        DreamTopic.WATER -> drawPath(Path().apply {
            moveTo(d.x, d.y - r * 0.1f)
            cubicTo(d.x + r * 0.08f, d.y, d.x + r * 0.06f, d.y + r * 0.07f, d.x, d.y + r * 0.07f)
            cubicTo(d.x - r * 0.06f, d.y + r * 0.07f, d.x - r * 0.08f, d.y, d.x, d.y - r * 0.1f)
        }, Palette.teal)
        DreamTopic.MUSIC -> drawVinyl(d, r * 0.09f, t, 0.9f)
        DreamTopic.FRIEND -> {
            drawCircle(Palette.coral.copy(alpha = 0.8f), r * 0.06f, d + Offset(-r * 0.05f, 0f))
            drawCircle(Palette.teal.copy(alpha = 0.8f), r * 0.05f, d + Offset(r * 0.06f, r * 0.01f))
        }
        DreamTopic.POKE -> for (i in 0 until 5) {
            val ang = i * TAU / 5 - PIF / 2
            drawLine(Palette.amber, d, d + Offset(cos(ang) * r * 0.08f, sin(ang) * r * 0.08f), r * 0.02f, StrokeCap.Round)
        }
    }
}

/** A thought cloud while it sleeps, dreaming of its day, the weather, a photo, a heart, music or confetti. */
private fun DrawScope.drawDream(core: Offset, r: Float, t: Float, weather: WeatherNow?, photo: androidx.compose.ui.graphics.ImageBitmap?,
    topics: List<DreamTopic> = emptyList()) {
    val bob = sin(t * 0.8f) * r * 0.02f
    val d = core + Offset(-r * 0.66f, -r * 0.78f + bob)
    val puff = Palette.ink.copy(alpha = 0.16f)
    drawCircle(puff, r * 0.025f, core + Offset(-r * 0.36f, -r * 0.42f))
    drawCircle(puff, r * 0.04f, core + Offset(-r * 0.47f, -r * 0.55f))
    drawCircle(puff, r * 0.2f, d)
    drawCircle(puff, r * 0.13f, d + Offset(-r * 0.16f, r * 0.05f))
    drawCircle(puff, r * 0.13f, d + Offset(r * 0.16f, r * 0.05f))
    // Mostly the day's own moments (each for 40 s), with the old dreams mixed in.
    val slot = (t / 40f).toInt()
    if (topics.isNotEmpty() && slot % 3 != 2) {
        drawDayDoodle(topics[(slot / 3 * 2 + slot % 3) % topics.size], d, r, t)
        return
    }
    val theme = slot % 5
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

/** New Year's: a few minutes either side of midnight. */
private fun isNewYearMidnight(cal: Calendar): Boolean {
    val m = cal.get(Calendar.MONTH)
    val d = cal.get(Calendar.DAY_OF_MONTH)
    val h = cal.get(Calendar.HOUR_OF_DAY)
    val min = cal.get(Calendar.MINUTE)
    return (m == Calendar.DECEMBER && d == 31 && h == 23 && min >= 58) || (m == Calendar.JANUARY && d == 1 && h == 0 && min < 30)
}

/** The blob's own colour shifts with the seasons: warm in autumn, icy in winter, greener in spring. */
private fun seasonal(cal: Calendar): Color = when (cal.get(Calendar.MONTH)) {
    Calendar.SEPTEMBER, Calendar.OCTOBER, Calendar.NOVEMBER -> lerp(Palette.violet, Color(0xFFFF9F4A), 0.4f)
    Calendar.DECEMBER, Calendar.JANUARY, Calendar.FEBRUARY -> lerp(Palette.violet, Color(0xFF5AA9FF), 0.45f)
    Calendar.MARCH, Calendar.APRIL, Calendar.MAY -> lerp(Palette.violet, Color(0xFF6EDB8F), 0.4f)
    else -> Palette.violet
}

/** Holiday touches behind the blob: December snow, New Year's and July 4th fireworks, Valentine's hearts, Halloween bats. */
private fun DrawScope.drawHoliday(cal: Calendar, c: Offset, r: Float, t: Float, weather: String?) {
    val m = cal.get(Calendar.MONTH)
    val d = cal.get(Calendar.DAY_OF_MONTH)
    val h = cal.get(Calendar.HOUR_OF_DAY)
    when {
        isNewYearMidnight(cal) || (m == Calendar.JULY && d == 4 && h in 20..23) -> drawFireworks(c, r, t)
        m == Calendar.DECEMBER && weather?.lowercase()?.contains("snow") != true -> {
            for (i in 0 until 8) {
                val x = ((i * 0.618f + 0.3f) % 1f) * 2f - 1f
                val phase = (t * 0.08f + i * 0.31f) % 1f
                val pos = c + Offset((x + 0.04f * sin(t * 1.1f + i)) * r * 1.05f, (-1f + phase * 2f) * r)
                drawCircle(Color.White.copy(alpha = 0.35f * sin(phase * PIF)), r * 0.02f, pos)
            }
        }
        m == Calendar.FEBRUARY && d == 14 -> {
            for (i in 0 until 5) {
                val phase = (t * 0.12f + i * 0.2f) % 1f
                val pos = c + Offset(((i * 0.618f) % 1f * 1.6f - 0.8f) * r + sin(t + i) * r * 0.05f, (0.9f - phase * 1.9f) * r)
                drawHeartAlpha(pos, r * 0.05f, Palette.coral.copy(alpha = 0.6f * sin(phase * PIF)))
            }
        }
        m == Calendar.OCTOBER && d == 31 && h >= 17 -> {
            for (i in 0 until 2) {
                val phase = (t * 0.07f + i * 0.5f) % 1f
                val pos = c + Offset((-1.1f + phase * 2.2f) * r, (-0.85f + 0.1f * sin(t * 2 + i)) * r)
                val flap = sin(t * 14f + i) * r * 0.04f
                val ink = Palette.night.copy(alpha = 0.9f)
                drawLine(ink, pos, pos + Offset(-r * 0.08f, -flap), r * 0.025f, StrokeCap.Round)
                drawLine(ink, pos, pos + Offset(r * 0.08f, -flap), r * 0.025f, StrokeCap.Round)
                drawCircle(ink, r * 0.025f, pos)
            }
        }
    }
}

private fun DrawScope.drawHeartAlpha(c: Offset, size: Float, color: Color) {
    drawCircle(color, size * 0.5f, c + Offset(-size * 0.45f, -size * 0.2f))
    drawCircle(color, size * 0.5f, c + Offset(size * 0.45f, -size * 0.2f))
    drawPath(Path().apply {
        moveTo(c.x - size * 0.93f, c.y - size * 0.05f); lineTo(c.x + size * 0.93f, c.y - size * 0.05f); lineTo(c.x, c.y + size); close()
    }, color)
}

/** Three bursts going off in turn around the blob. */
private fun DrawScope.drawFireworks(c: Offset, r: Float, t: Float) {
    val spots = listOf(Offset(-0.7f, -0.75f), Offset(0.75f, -0.6f), Offset(0.1f, -1.0f))
    spots.forEachIndexed { i, spot ->
        val phase = ((t + i * 0.8f) % 2.4f) / 2.4f
        if (phase > 0.75f) return@forEachIndexed
        val p = phase / 0.75f
        val center = c + Offset(spot.x * r, spot.y * r)
        val color = confettiColors[(i + (t / 2.4f).toInt()) % confettiColors.size]
        for (k in 0 until 10) {
            val a = k * TAU / 10
            val reach = r * 0.28f * p
            val pos = center + Offset(cos(a) * reach, sin(a) * reach + p * p * r * 0.05f)
            drawCircle(color.copy(alpha = 1f - p), r * 0.022f, pos)
        }
    }
}

/** Two hands clapped over where its ears would be. */
private fun DrawScope.drawEarCovers(core: Offset, r: Float, p: Float, tint: Color) {
    val k = (minOf(p, 1 - p) / 0.15f).coerceIn(0f, 1f)
    for (side in listOf(-1f, 1f)) {
        drawCircle(tint.copy(alpha = k), r * 0.1f, core + Offset(side * r * 0.46f, -r * 0.05f))
    }
}

/** A tiny speaker with a cross: the volume is at zero. */
private fun DrawScope.drawMuted(at: Offset, size: Float) {
    val ink = Palette.muted
    drawRect(ink, Offset(at.x - size, at.y - size * 0.4f), Size(size * 0.6f, size * 0.8f))
    drawPath(Path().apply {
        moveTo(at.x - size * 0.4f, at.y - size * 0.4f); lineTo(at.x + size * 0.2f, at.y - size); lineTo(at.x + size * 0.2f, at.y + size)
        lineTo(at.x - size * 0.4f, at.y + size * 0.4f); close()
    }, ink)
    drawLine(Palette.coral, Offset(at.x + size * 0.5f, at.y - size * 0.4f), Offset(at.x + size * 1.2f, at.y + size * 0.4f), size * 0.25f, StrokeCap.Round)
    drawLine(Palette.coral, Offset(at.x + size * 0.5f, at.y + size * 0.4f), Offset(at.x + size * 1.2f, at.y - size * 0.4f), size * 0.25f, StrokeCap.Round)
}

/** Values the blob eases between frames. */
private class Smooth {
    var started = false
    var lastT = 0f
    var lookX = 0f
    var lookY = 0f
    var dx = 0f
    var dy = 0f
    var sx = 1f
    var sy = 1f
    var wobble = 0f
    var tint = Palette.violet
    var breath = 0f
    var drift = 0f
    var energyAmp = 0.07f
    var mood = Mood.NEUTRAL
    var prevMood = Mood.NEUTRAL
    var moodAt = -10f
    var accessory: Accessory? = null
    var prevAccessory: Accessory? = null
    var accessoryAt = -10f
    var prop: HeldItem? = null
    var prevProp: HeldItem? = null
    /** The note it holds is today's reminders (tap it for the list), not some other note. */
    var noteIsReminders = false
    var propAt = -10f
    var replyLen = 0
    var replyAt = -10f
    var think = 0f
    var timerKey: String? = null
    var flipAt = -10f
}

/** Draws [block] scaled up from [anchor] with a little overshoot as [f] goes 0 to 1 (and faded). */
private inline fun DrawScope.popIn(f: Float, anchor: Offset, block: DrawScope.() -> Unit) {
    if (f <= 0.01f) return
    if (f >= 1f) { block(); return }
    // Ease-out-back: grows past full size a touch, then settles.
    val c1 = 1.7f
    val x = f - 1f
    val k = (1f + (c1 + 1f) * x * x * x + c1 * x * x).coerceAtLeast(0f)
    withTransform({ scale(k, k, anchor) }) { block() }
}

/** A striped blanket pulled up over its lower half ([f] 0..1 while tucking in). */
private fun DrawScope.drawBlanket(core: Offset, r: Float, f: Float, t: Float) {
    val e = 1f - (1f - f) * (1f - f)
    val top = core.y + r * 0.62f - e * r * 0.5f
    val left = core.x - r * 0.62f
    val width = r * 1.24f
    val height = core.y + r * 0.62f - top
    if (height <= 1f) return
    val rise = sin(t * 1.4f) * r * 0.008f
    drawRoundRect(Color(0xFF6E8BD9), Offset(left, top + rise), Size(width, height + r * 0.1f), CornerRadius(r * 0.12f))
    for (i in 1..3) {
        val y = top + rise + i * height / 4f
        if (y < core.y + r * 0.6f) drawLine(Color(0xFF9DB2EE), Offset(left + r * 0.04f, y), Offset(left + width - r * 0.04f, y), r * 0.025f)
    }
    drawRoundRect(Color(0xFFF3EEF8), Offset(left - r * 0.02f, top + rise - r * 0.04f), Size(width + r * 0.04f, r * 0.09f), CornerRadius(r * 0.04f))
}

/** A calendar page flying off and fluttering down. */
private fun DrawScope.drawTornPage(hand: Offset, r: Float, p: Float) {
    if (p < 0.25f) return
    val q = (p - 0.25f) / 0.75f
    val pos = hand + Offset(q * r * 0.35f + sin(q * 9f) * r * 0.05f, -r * 0.1f + q * q * r * 0.6f)
    rotate(q * 280f, pos) {
        drawRect(Color.White.copy(alpha = 1f - q), Offset(pos.x - r * 0.09f, pos.y - r * 0.07f), Size(r * 0.18f, r * 0.14f))
        drawRect(Palette.coral.copy(alpha = 1f - q), Offset(pos.x - r * 0.09f, pos.y - r * 0.07f), Size(r * 0.18f, r * 0.04f))
    }
}

/** A thought cloud above-right: three bouncing dots, or the reply's first words. */
private fun DrawScope.drawThought(at: Offset, r: Float, t: Float, alpha: Float, words: String?, tm: androidx.compose.ui.text.TextMeasurer) {
    val ink = Palette.ink.copy(alpha = 0.9f * alpha)
    drawCircle(ink, r * 0.03f, at + Offset(-r * 0.28f, r * 0.34f))
    drawCircle(ink, r * 0.05f, at + Offset(-r * 0.18f, r * 0.22f))
    if (words == null) {
        drawRoundRect(ink, Offset(at.x - r * 0.2f, at.y - r * 0.1f), Size(r * 0.44f, r * 0.24f), CornerRadius(r * 0.12f))
        for (i in 0..2) {
            val bounce = abs(sin(t * 5f - i * 0.6f)) * r * 0.03f
            drawCircle(Palette.night.copy(alpha = 0.8f * alpha), r * 0.028f, at + Offset(-r * 0.08f + i * r * 0.1f, r * 0.02f - bounce))
        }
    } else {
        val style = androidx.compose.ui.text.TextStyle(color = Palette.night.copy(alpha = alpha), fontSize = 12.sp)
        val layout = tm.measure(words, style, constraints = androidx.compose.ui.unit.Constraints(maxWidth = (r * 1.5f).toInt()), maxLines = 2)
        val w = layout.size.width + r * 0.16f
        val h = layout.size.height + r * 0.1f
        val topLeft = Offset(at.x - r * 0.2f, at.y - h + r * 0.14f)
        drawRoundRect(ink, topLeft, Size(w, h), CornerRadius(r * 0.1f))
        drawText(layout, topLeft = topLeft + Offset(r * 0.08f, r * 0.05f))
    }
}

/** Minutes after midnight of today's first timed event, if the agenda is today's. */
private fun firstEventMinutes(agenda: AgendaDay): Int? {
    if (!BlobBrain.isToday(agenda.date, java.time.LocalDate.now())) return null
    return agenda.items.mapNotNull { BlobBrain.minutesOfDay(it.time) }.minOrNull()
}

/** Tomorrow's events from the agenda's "Coming up" days. */
private fun tomorrowEvents(agenda: AgendaDay): List<com.rusty.museecho.integrations.AgendaItem> {
    val tomorrow = java.time.LocalDate.now().plusDays(1)
    return agenda.upcoming.firstOrNull { BlobBrain.isToday(it.date, tomorrow) }?.items.orEmpty()
}

/** Sleeps until a countdown day (0 = today), or null if its date is unreadable. */
private fun sleepsUntil(d: com.rusty.museecho.integrations.SpecialDay): Int? = runCatching {
    java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), java.time.LocalDate.parse(d.date)).toInt()
}.getOrNull()

/** The next of today's reminders still to come (or untimed), for its card. */
private fun nextReminder(day: com.rusty.museecho.integrations.ReminderDay, nowMs: Long): String? {
    if (day.date != java.time.LocalDate.now().toString()) return null
    val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
    val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    val upcoming = day.items.filter { r -> BlobBrain.minutesOfDay(r.time)?.let { it + 5 >= nowMin } ?: true }
    val next = upcoming.minByOrNull { BlobBrain.minutesOfDay(it.time) ?: Int.MAX_VALUE } ?: return null
    return (if (next.time.isNotBlank()) "${next.time} " else "") + next.text
}

/** An hourglass: [sand] is the share still to fall (top bulb), and [flip] 0..1 turns it over. */
private fun DrawScope.drawHourglass(a: Offset, r: Float, sand: Float, flip: Float, t: Float) {
    val turn = if (flip < 1f) 180f * (1f - (1f - flip) * (1f - flip)) - 180f else 0f
    rotate(turn, a) {
        val w = r * 0.11f
        val h = r * 0.16f
        val glass = Palette.ink.copy(alpha = 0.35f)
        // Frame: caps top and bottom.
        drawRoundRect(lerp(Palette.amber, Palette.night, 0.35f), Offset(a.x - w * 1.25f, a.y - h - r * 0.03f), Size(w * 2.5f, r * 0.035f), CornerRadius(r * 0.015f))
        drawRoundRect(lerp(Palette.amber, Palette.night, 0.35f), Offset(a.x - w * 1.25f, a.y + h), Size(w * 2.5f, r * 0.035f), CornerRadius(r * 0.015f))
        val bulbs = Path().apply {
            moveTo(a.x - w, a.y - h); lineTo(a.x + w, a.y - h)
            quadraticTo(a.x + w, a.y - h * 0.2f, a.x + w * 0.12f, a.y)
            quadraticTo(a.x + w, a.y + h * 0.2f, a.x + w, a.y + h)
            lineTo(a.x - w, a.y + h)
            quadraticTo(a.x - w, a.y + h * 0.2f, a.x - w * 0.12f, a.y)
            quadraticTo(a.x - w, a.y - h * 0.2f, a.x - w, a.y - h)
            close()
        }
        drawPath(bulbs, glass)
        val sandColor = Palette.amber
        // Top: sand left, sinking toward the neck.
        if (sand > 0.01f) {
            val top = a.y - h * 0.85f * sand
            val half = w * (0.15f + 0.8f * sand)
            drawPath(Path().apply { moveTo(a.x - half, top); lineTo(a.x + half, top); lineTo(a.x, a.y - r * 0.005f); close() }, sandColor)
            // The falling stream, flickering.
            if (flip >= 1f) drawLine(sandColor.copy(alpha = 0.7f + 0.3f * sin(t * 20f)), Offset(a.x, a.y), Offset(a.x, a.y + h * 0.95f), r * 0.012f)
        }
        // Bottom: a growing pile.
        val pile = 1f - sand
        if (pile > 0.01f) {
            val peak = a.y + h - h * 0.8f * pile
            drawPath(Path().apply { moveTo(a.x - w * 0.95f, a.y + h); lineTo(a.x + w * 0.95f, a.y + h); lineTo(a.x, peak); close() }, sandColor)
        }
    }
}

/** A calendar date that makes drawHoliday show [name] (for the playground). */
private fun holidayCalendar(name: String): Calendar? = Calendar.getInstance().apply {
    when (name) {
        "FIREWORKS" -> { set(Calendar.MONTH, Calendar.JANUARY); set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 5) }
        "DECEMBER_SNOW" -> { set(Calendar.MONTH, Calendar.DECEMBER); set(Calendar.DAY_OF_MONTH, 10); set(Calendar.HOUR_OF_DAY, 12) }
        "VALENTINE_HEARTS" -> { set(Calendar.MONTH, Calendar.FEBRUARY); set(Calendar.DAY_OF_MONTH, 14); set(Calendar.HOUR_OF_DAY, 12) }
        "HALLOWEEN_BATS" -> { set(Calendar.MONTH, Calendar.OCTOBER); set(Calendar.DAY_OF_MONTH, 31); set(Calendar.HOUR_OF_DAY, 20) }
        else -> return null
    }
}

/** Sleep until the next multiple of [periodMs] of wall-clock time (a beat shared by every animated thing). */
internal suspend fun awaitBeat(periodMs: Long) {
    delay(periodMs - System.currentTimeMillis() % periodMs)
}

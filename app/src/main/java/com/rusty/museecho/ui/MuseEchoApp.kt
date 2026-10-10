package com.rusty.museecho.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rusty.museecho.gadget.GadgetState
import com.rusty.museecho.gadget.Link
import com.rusty.museecho.gadget.Screen
import com.rusty.museecho.gadget.Talk
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

interface UiActions {
    fun toggleTalk()
    fun sendText(text: String)
    fun micLevel(): Float
    fun dismissScreen()
    fun clearCaptions()
    fun pair()
    fun cancelPair()
    fun reconnect()
    fun unpair()
    fun saveToken(token: String): String?
    fun hasToken(): Boolean
    fun isPaired(): Boolean
    fun bleName(): String
    fun nodeId(): String
    fun dismissRinging()
    fun cancelTimer(id: String)
    fun blePairingSupported(): Boolean
    fun setMuseName(name: String)
    fun laptopHost(): String
    fun hasLaptopToken(): Boolean
    fun saveLaptop(host: String, token: String?)
    fun timers(): kotlinx.coroutines.flow.StateFlow<List<com.rusty.museecho.integrations.Countdown>>
    fun ringing(): kotlinx.coroutines.flow.StateFlow<List<com.rusty.museecho.integrations.Countdown>>
    fun showImage(bitmap: android.graphics.Bitmap)
    fun refreshQuiet()
    fun setMicOff(off: Boolean)
    fun morningBrief()
    fun notes(): kotlinx.coroutines.flow.StateFlow<com.rusty.museecho.integrations.PinnedNote>
    fun removeNote(item: String)
    fun glance(): kotlinx.coroutines.flow.StateFlow<com.rusty.museecho.integrations.GlanceLine>
    fun history(): kotlinx.coroutines.flow.StateFlow<List<com.rusty.museecho.integrations.Exchange>>
    fun togglePlayback()
    fun stopPlayback()
    fun saveWeatherLocation(lat: Double, lon: Double)
    fun voiceLevel(): String
    fun setVoiceLevel(choice: String)
    fun voiceStatus(): kotlinx.coroutines.flow.StateFlow<com.rusty.museecho.gadget.NeuralVoice.Status>
    fun voiceInstalled(level: com.rusty.museecho.gadget.NeuralVoice.Level): Boolean
    suspend fun voiceSpeedTest(): String
    suspend fun testWakePhrase(phrase: String): String
    /** Ask the home-screen app to pin a Muse Echo icon; returns what happened. */
    fun pinToHomeScreen(): String
    fun agenda(): kotlinx.coroutines.flow.StateFlow<com.rusty.museecho.integrations.AgendaDay>
    fun installUpdate(onResult: (String) -> Unit)
    suspend fun checkForUpdate(): String
    fun hubHost(): String
    fun setHubHost(host: String)
    fun quickActions(): kotlinx.coroutines.flow.StateFlow<List<com.rusty.museecho.integrations.QuickAction>>
    fun runQuickAction(a: com.rusty.museecho.integrations.QuickAction)
    fun saveQuickActions(list: List<com.rusty.museecho.integrations.QuickAction>)
    fun framePhotos(): List<java.io.File>
    fun frameDelayMin(): Int
    fun setFrameDelayMin(min: Int)
    fun wakeSensitivity(): com.rusty.museecho.gadget.WakeWord.Sensitivity
    fun setWakeSensitivity(s: com.rusty.museecho.gadget.WakeWord.Sensitivity)
    fun wakeWordEnabled(): Boolean
    fun wakePhrase(): String
    fun wakeModelPresent(): Boolean
    fun setWakeWord(enabled: Boolean, phrase: String): String?
    /** The homelab page: fresh details from the echo-hub. */
    suspend fun homelab(): com.rusty.museecho.integrations.Homelab?
    /** Ask Muse something from a button (shown with [label] instead of what was "heard"). */
    fun ask(prompt: String, label: String)
    suspend fun backupNow(): String?
    fun restoreBackup(onResult: (String) -> Unit)
    fun lastBackupMs(): Long
    suspend fun syncPhotosNow(): Int
}

internal object Palette {
    val night = Color(0xFF0E0B14)
    val dusk = Color(0xFF1B1526)
    val ink = Color(0xFFF3EEF8)
    val muted = Color(0xFF9C93AB)
    val violet = Color(0xFF8B6CFF)
    val coral = Color(0xFFFF7A8A)
    val teal = Color(0xFF45D3C7)
    val amber = Color(0xFFFFC266)
    val card = Color(0xFF231C30)
}

@Composable
fun MuseEchoApp(actions: UiActions) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Palette.violet, background = Palette.night, surface = Palette.card)) {
        val screen by GadgetState.screen.collectAsState()
        var showSetup by remember { mutableStateOf(false) }
        var showKeyboard by remember { mutableStateOf(false) }
        var showHistory by remember { mutableStateOf(false) }
        val micOff by GadgetState.micOff.collectAsState()

        Box(Modifier.fillMaxSize().background(Palette.night)) {
            // Pictures and cards fade and grow in; the clock eases back afterwards.
            androidx.compose.animation.AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    (fadeIn(tween(450)) + androidx.compose.animation.scaleIn(tween(450), initialScale = 0.96f)) togetherWith
                        (fadeOut(tween(300)) + androidx.compose.animation.scaleOut(tween(300), targetScale = 1.02f))
                },
                label = "screen",
            ) { s ->
                when (s) {
                    Screen.Idle -> IdlePages(actions)
                    is Screen.Image -> ImageScreen(s, actions::dismissScreen)
                    is Screen.Text -> TextScreen(s, actions::dismissScreen)
                }
            }
            Row(Modifier.align(Alignment.TopStart).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(Modifier)
                val showPill by GadgetState.serverPill.collectAsState()
                if (showPill) ServerLight(Modifier)
            }
            UpdateChip(Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp), actions)
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                IconButton(onClick = { actions.setMicOff(!micOff) }) {
                    Icon(if (micOff) Icons.Filled.MicOff else Icons.Filled.Mic, if (micOff) "Turn mic on" else "Turn mic off",
                        tint = if (micOff) Palette.coral else Palette.muted)
                }
                IconButton(onClick = actions::morningBrief) {
                    Icon(Icons.Filled.WbSunny, "Morning brief", tint = Palette.muted)
                }
                IconButton(onClick = { showHistory = true }) {
                    Icon(Icons.Filled.History, "History", tint = Palette.muted)
                }
                IconButton(onClick = { showKeyboard = true }) {
                    Icon(Icons.Filled.Keyboard, "Type to Muse", tint = Palette.muted)
                }
                IconButton(onClick = { showSetup = true }) {
                    Icon(Icons.Filled.Settings, "Setup", tint = Palette.muted)
                }
            }
            QuickActionRow(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 30.dp), actions)
            BlobCards(Modifier.align(Alignment.BottomStart), actions)
            Captions(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 120.dp, bottom = 18.dp), actions)
            TalkButton(Modifier.align(Alignment.BottomEnd).padding(18.dp), actions)

            Column(Modifier.align(Alignment.TopCenter).padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                InternetBanner()
                AlertBanner()
                TimerChips(Modifier, actions)
                NowPlayingChip(Modifier.padding(top = 6.dp), actions)
            }
            IdleFrameWatcher(actions)
            Slideshow(actions)
            RingScreen(actions)
            val asleep by GadgetState.asleep.collectAsState()
            AnimatedVisibility(asleep, enter = fadeIn(tween(1200)), exit = fadeOut(tween(400))) {
                Box(Modifier.fillMaxSize().background(Color.Black))
            }
            val panelIn = fadeIn(tween(250)) + androidx.compose.animation.slideInVertically(tween(300)) { it / 12 }
            val panelOut = fadeOut(tween(200)) + androidx.compose.animation.slideOutVertically(tween(250)) { it / 12 }
            AnimatedVisibility(showHistory, enter = panelIn, exit = panelOut) { HistoryPanel(actions, onClose = { showHistory = false }) }
            AnimatedVisibility(showKeyboard, enter = panelIn, exit = panelOut) {
                TypeSheet(onSend = { actions.sendText(it); showKeyboard = false }, onClose = { showKeyboard = false })
            }
            AnimatedVisibility(showSetup, enter = panelIn, exit = panelOut) { SetupPanel(actions, onClose = { showSetup = false }) }
        }
    }
}

@Composable
private fun IdleFace(actions: UiActions) {
    val link by GadgetState.link.collectAsState()
    val name by GadgetState.museName.collectAsState()
    val now = rememberNow()

    val note by actions.notes().collectAsState()
    val glanceLine by actions.glance().collectAsState()
    val weather by GadgetState.weather.collectAsState()
    val hasNote = note.items.isNotEmpty() || note.title.isNotEmpty()
    // Where the screen was last tapped, in the blob's own coordinates, so its eyes can look there.
    var rowCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var blobCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var lookAt by remember { mutableStateOf<Offset?>(null) }
    var lookAtMs by remember { mutableStateOf(0L) }
    var tapAt by remember { mutableStateOf<Offset?>(null) }
    var tapAtMs by remember { mutableStateOf(0L) }
    Row(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var downAt: Offset? = null
                    var downMs = 0L
                    while (true) {
                        // Only watches (Initial pass, nothing consumed): buttons and swipes still get it.
                        val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val change = e.changes.firstOrNull() ?: continue
                        val row = rowCoords
                        val blob = blobCoords
                        if (row == null || blob == null || !row.isAttached || !blob.isAttached) continue
                        if (change.pressed && !change.previousPressed) {
                            // Eyes follow the finger as soon as it touches.
                            downAt = change.position
                            downMs = System.currentTimeMillis()
                            val inBlob = blob.localPositionOf(row, change.position)
                            lookAt = inBlob
                            lookAtMs = downMs
                            // Press and hold the blob itself: talk while held, send on release (walkie-talkie).
                            val c = Offset(blob.size.width / 2f, blob.size.height / 2f)
                            if ((inBlob - c).getDistance() < blob.size.width * 0.34f) {
                                val start = change.position
                                val early = withTimeoutOrNull(HOLD_TO_TALK_MS) {
                                    while (true) {
                                        val c2 = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.firstOrNull() ?: continue
                                        if (!c2.pressed || (c2.position - start).getDistance() > 24f) return@withTimeoutOrNull c2
                                    }
                                    @Suppress("UNREACHABLE_CODE") null
                                }
                                if (early == null) {
                                    if (GadgetState.talk.value != Talk.LISTENING) actions.toggleTalk()
                                    do {
                                        val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    } while (ev.changes.any { it.pressed })
                                    if (GadgetState.talk.value == Talk.LISTENING) actions.toggleTalk()
                                    downAt = null
                                } else if (!early.pressed) {
                                    // Let go quickly without moving: an ordinary tap.
                                    if ((early.position - start).getDistance() < 24f) {
                                        tapAt = blob.localPositionOf(row, early.position)
                                        tapAtMs = System.currentTimeMillis()
                                    }
                                    downAt = null
                                }
                            }
                        } else if (!change.pressed && change.previousPressed) {
                            // A real tap (short, didn't move) is what counts as poking the blob.
                            val start = downAt
                            if (start != null && (change.position - start).getDistance() < 24f &&
                                System.currentTimeMillis() - downMs < 500
                            ) {
                                tapAt = blob.localPositionOf(row, change.position)
                                tapAtMs = System.currentTimeMillis()
                            }
                            downAt = null
                        }
                    }
                }
            }
            .onGloballyPositioned { rowCoords = it }
            .padding(horizontal = if (hasNote) 24.dp else 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(if (hasNote) 170.dp else 220.dp).onGloballyPositioned { blobCoords = it }, contentAlignment = Alignment.Center) {
            Blob(Modifier.fillMaxSize(), actions, lookAt, lookAtMs, tapAt, tapAtMs)
        }
        Spacer(Modifier.width(if (hasNote) 20.dp else 36.dp))
        Column(Modifier.weight(1f)) {
            val faceName by GadgetState.clockFace.collectAsState()
            ClockFaceView(ClockFace.of(faceName), now, compact = hasNote)
            if (faceName != ClockFace.CLASSIC.name) Spacer(Modifier.height(8.dp))
            Text(SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now), color = Palette.muted, fontSize = 20.sp)
            weather?.let { w ->
                Text("${w.tempF}° ${w.summary}  ·  H ${w.highF}° L ${w.lowF}°", color = Palette.ink.copy(alpha = 0.85f), fontSize = 17.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
            val line = glanceLine.text.takeIf { glanceLine.untilMs == 0L || now.time < glanceLine.untilMs }.orEmpty()
            if (line.isNotEmpty()) {
                Text(line, color = Palette.amber, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                when (link) {
                    Link.CONNECTED -> "Tap the mic to talk to $name"
                    Link.NOT_PAIRED, Link.NO_TOKEN -> "Open setup (top right) to pair with $name"
                    else -> "Connecting to $name…"
                },
                color = Palette.muted.copy(alpha = 0.8f), fontSize = 15.sp,
            )
        }
        if (hasNote) NoteCard(note, actions)
    }
}

/** Active weather alerts (National Weather Service), one chip each; tap for the details. */
@Composable
private fun AlertBanner() {
    val all by GadgetState.alerts.collectAsState()
    val closed by GadgetState.dismissedAlerts.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Closing a chip only hides the banner; the blob keeps worrying while the alert lasts.
    val alerts = all.filter { it.id !in closed }
    fun close(ids: Collection<String>) {
        val next = closed + ids
        GadgetState.setDismissedAlerts(next)
        com.rusty.museecho.gadget.Prefs(ctx).dismissedAlerts = next
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = alerts.isNotEmpty(),
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Most serious first: only two fit.
            val rank = mapOf("extreme" to 0, "severe" to 1, "moderate" to 2, "minor" to 3)
            val warningsFirst = alerts.sortedWith(compareBy({ rank[it.severity.lowercase()] ?: 4 }, { if ("warning" in it.event.lowercase()) 0 else 1 }))
            warningsFirst.take(2).forEach { a ->
                val severe = a.severity.equals("Severe", true) || a.severity.equals("Extreme", true)
                val until = if (a.endsMs > 0) " until " + SimpleDateFormat("EEE h a", Locale.US).format(Date(a.endsMs)) else ""
                Row(
                    Modifier.padding(bottom = 4.dp).background(if (severe) Palette.coral else Palette.amber, RoundedCornerShape(50)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("⚠ ${a.event}$until", color = Palette.night, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable {
                            GadgetState.show(Screen.Text(a.event, listOf(a.headline, a.description, a.instruction)
                                .filter { it.isNotBlank() }.joinToString("\n\n")))
                        }.padding(start = 14.dp, top = 5.dp, bottom = 5.dp, end = 6.dp))
                    // Close: hides this chip (a third alert, if any, moves up).
                    Text("✕", color = Palette.night.copy(alpha = 0.7f), fontSize = 14.sp,
                        modifier = Modifier.clickable { close(listOf(a.id)) }.padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp))
                }
            }
        }
    }
}

/** The clock face, and the agenda one swipe to the left. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun IdlePages(actions: UiActions) {
    val pager = androidx.compose.foundation.pager.rememberPagerState(pageCount = { 3 })
    LaunchedEffect(pager.currentPage) { GadgetState.setIdlePage(pager.currentPage) }
    val talk by GadgetState.talk.collectAsState()
    // Anything happening brings the clock back.
    LaunchedEffect(talk) { if (talk != Talk.IDLE) pager.animateScrollToPage(0) }
    LaunchedEffect(pager.currentPage) {
        if (pager.currentPage != 0) {
            delay(120_000)
            pager.animateScrollToPage(0)
        }
    }
    // Swiping back and forth quickly makes the blob dizzy: count direction changes in the last 3 seconds.
    LaunchedEffect(pager) {
        var last = 0f
        var dir = 0
        val turns = ArrayDeque<Long>()
        androidx.compose.runtime.snapshotFlow { pager.currentPage + pager.currentPageOffsetFraction }.collect { pos ->
            val step = pos - last
            if (kotlin.math.abs(step) < 0.02f) return@collect
            last = pos
            val d = if (step > 0) 1 else -1
            if (dir != 0 && d != dir) {
                val now = System.currentTimeMillis()
                turns.addLast(now)
                while (turns.isNotEmpty() && now - turns.first() > 3_000) turns.removeFirst()
                if (turns.size >= 4) {
                    turns.clear()
                    GadgetState.react(com.rusty.museecho.gadget.Reaction.DIZZY)
                }
            }
            dir = d
        }
    }
    androidx.compose.foundation.pager.HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
        when (page) {
            0 -> IdleFace(actions)
            1 -> AgendaPage(actions)
            else -> HomelabPage(actions)
        }
    }
}

@Composable
private fun AgendaPage(actions: UiActions) {
    val day by actions.agenda().collectAsState()
    val name by GadgetState.museName.collectAsState()
    val today = SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date())
    val scroll = rememberScrollState()
    // A little blob keeps you company here, reading along as you scroll through the day.
    val frac = if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f
    val nowMin = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
    val isToday = com.rusty.museecho.gadget.BlobBrain.isToday(day.date, java.time.LocalDate.now())
    val soon = isToday &&
        day.items.any { item -> com.rusty.museecho.gadget.BlobBrain.minutesOfDay(item.time)?.let { it - nowMin in 0..60 } == true }
    Box(Modifier.fillMaxSize()) {
        Blob(
            Modifier.align(Alignment.TopEnd).padding(top = 70.dp, end = 30.dp).size(120.dp), actions,
            mini = true,
            gaze = Offset(-0.9f, -0.5f + frac),
            moodOverride = when {
                day.items.isEmpty() -> com.rusty.museecho.gadget.Mood.DROWSY
                soon -> com.rusty.museecho.gadget.Mood.HAPPY
                else -> null
            },
        )
    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 180.dp, top = 64.dp, bottom = 84.dp)) {
        Text(day.date.ifEmpty { today }, color = Palette.amber, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        if (!isToday) {
            Text("From an earlier day", color = Palette.coral, fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        if (day.items.isEmpty()) {
            Body("Nothing on the agenda. $name fills this in with the morning brief, or ask \"what's on today?\"", Palette.muted)
        }
        Column(Modifier.verticalScroll(scroll)) {
            day.items.forEach { item ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    Text(item.time, color = Palette.teal, fontSize = 18.sp, modifier = Modifier.width(110.dp))
                    Text(item.title, color = Palette.ink, fontSize = 20.sp, lineHeight = 26.sp)
                }
            }
            // The next few days, so an empty today still shows what's ahead.
            if (day.upcoming.isNotEmpty()) {
                Text("Coming up", color = Palette.amber, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
                day.upcoming.forEach { up ->
                    Text(friendlyDate(up.date), color = Palette.muted, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                    up.items.forEach { item ->
                        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                            Text(item.time, color = Palette.teal.copy(alpha = 0.8f), fontSize = 16.sp, modifier = Modifier.width(110.dp))
                            Text(item.title, color = Palette.ink.copy(alpha = 0.85f), fontSize = 17.sp, lineHeight = 22.sp)
                        }
                    }
                }
            }
        }
    }
    }
}

/** "2026-10-11" as "Tomorrow" / "Monday, Oct 12"; other text as it is. */
private fun friendlyDate(date: String): String {
    val d = runCatching { java.time.LocalDate.parse(date.trim().take(10)) }.getOrNull() ?: return date
    val today = java.time.LocalDate.now()
    return when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US))
    }
}

private const val HOLD_TO_TALK_MS = 380L
private const val CAPTION_ASIDE_MS = 8_000L

/** The Latitude in detail: Kuma monitors with 24-hour uptime, disk, memory, load, battery, containers and the last backup. */
@Composable
private fun HomelabPage(actions: UiActions) {
    val lab by GadgetState.homelab.collectAsState()
    var failed by remember { mutableStateOf(false) }
    // Fresh while the page is on screen.
    LaunchedEffect(Unit) {
        while (true) {
            val h = actions.homelab()
            if (h != null) GadgetState.setHomelab(h)
            failed = h == null
            delay(30_000)
        }
    }
    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 64.dp, bottom = 84.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Homelab", color = Palette.amber, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            val h = lab
            if (h != null) {
                val (color, label) = when (h.overall) {
                    "up" -> Palette.teal to "all up"
                    "down" -> Palette.coral to "something's down"
                    else -> Palette.amber to "checking"
                }
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Text("  $label", color = Palette.muted, fontSize = 15.sp)
            }
            if (failed && h == null) Text("can't reach the Latitude", color = Palette.coral, fontSize = 15.sp)
        }
        val h = lab ?: return@Column
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxSize()) {
            // Services: tap one to have Pixel look into it.
            Column(Modifier.weight(1.1f).verticalScroll(rememberScrollState())) {
                Text("Services · 24 h uptime  (tap to ask Pixel)", color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                h.monitors.forEach { m ->
                    val color = when (m.status) { "up" -> Palette.teal; "down" -> Palette.coral; else -> Palette.amber }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                            actions.ask("Check on ${m.name} on the Latitude and tell me in a sentence or two how it's doing " +
                                "(Kuma says ${m.status}). Don't change anything.", "Checking ${m.name}")
                        }.padding(vertical = 7.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).background(color, CircleShape))
                        Text(m.name, color = Palette.ink, fontSize = 18.sp, modifier = Modifier.padding(start = 10.dp).weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(m.uptime_24h?.let { "%.1f%%".format(it) } ?: "—", color = Palette.muted, fontSize = 16.sp)
                    }
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                @Composable
                fun meter(label: String, pct: Double?, detail: String) {
                    val p = ((pct ?: 0.0) / 100).toFloat().coerceIn(0f, 1f)
                    val color = when { p >= 0.9f -> Palette.coral; p >= 0.75f -> Palette.amber; else -> Palette.teal }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = Palette.muted, fontSize = 14.sp, modifier = Modifier.width(70.dp))
                        Box(Modifier.weight(1f).height(8.dp).background(Palette.card, RoundedCornerShape(50))) {
                            Box(Modifier.fillMaxWidth(p).height(8.dp).background(color, RoundedCornerShape(50)))
                        }
                        Text(detail, color = Palette.ink, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp).width(80.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                meter("Disk", h.disk.used_pct, "${h.disk.used_pct.toInt()}% of ${h.disk.total_gb.toInt()} GB")
                meter("Memory", h.memory.used_pct, "${h.memory.used_pct?.toInt() ?: "?"}%")
                val load = h.load.firstOrNull() ?: 0.0
                meter("Load", 100.0 * load / h.cpus.coerceAtLeast(1), "%.2f".format(load))
                h.battery?.let { b -> meter("Battery", b.percent.toDouble().let { 100 - it }, "${b.percent}%${if (b.on_ac) " ⚡" else " (battery!)"}") }
                val backupLine = h.backup.at?.let { at ->
                    val then = runCatching { java.time.OffsetDateTime.parse(at).toInstant().toEpochMilli() }.getOrNull()
                    val ago = then?.let { ((System.currentTimeMillis() - it) / 3_600_000).toInt() }
                    (if (h.backup.ok) "✓ " else "✗ ") + (ago?.let { if (it < 48) "${it}h ago" else "${it / 24}d ago" } ?: at)
                } ?: "never"
                Text("Backup  $backupLine${if (h.backup.running) " (running)" else ""}",
                    color = if (h.backup.ok) Palette.ink else Palette.coral, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp))
                h.uptime_s?.let { Text("Up ${it / 86_400}d ${(it % 86_400) / 3600}h", color = Palette.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
                Text("Containers", color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    h.containers.forEach { c ->
                        val bad = c.state != "running" || c.health == "unhealthy"
                        Text(c.name, color = Palette.night, fontSize = 12.sp,
                            modifier = Modifier.background(if (bad) Palette.coral else Palette.teal.copy(alpha = 0.85f), RoundedCornerShape(50))
                                .padding(horizontal = 9.dp, vertical = 3.dp))
                    }
                }
            }
        }
    }
}

/** Homelab health from the echo-hub: a dot by the status chip; tap for details. */
@Composable
private fun ServerLight(modifier: Modifier) {
    val status by GadgetState.server.collectAsState()
    val s = status ?: return
    var open by remember { mutableStateOf(false) }
    val down = s.monitors.filter { it.status == "down" }
    val (color, label) = when (s.overall) {
        "up" -> Palette.teal to "Server OK"
        "down" -> Palette.coral to "Server: ${down.size} down"
        else -> Palette.amber to "Server: check"
    }
    Column(modifier) {
        Row(
            Modifier.background(Palette.card.copy(alpha = 0.8f), RoundedCornerShape(50)).clickable { open = !open }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(label, color = Palette.ink, fontSize = 13.sp)
        }
        if (open) {
            Column(Modifier.padding(top = 6.dp).background(Palette.card, RoundedCornerShape(12.dp)).padding(10.dp)) {
                s.monitors.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(when (m.status) {
                            "up" -> Palette.teal
                            "down" -> Palette.coral
                            else -> Palette.amber
                        }, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text("${m.name}: ${m.status}", color = Palette.ink, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** "Update ready" chip; tapping it downloads the build and opens Android's install confirmation. */
@Composable
private fun UpdateChip(modifier: Modifier, actions: UiActions) {
    val update by GadgetState.update.collectAsState()
    val u = update ?: return
    var msg by remember(u) { mutableStateOf<String?>(null) }
    Text(msg ?: "Update ${u.versionName} ready, tap to install", color = Palette.night, fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.background(Palette.amber, RoundedCornerShape(50)).clickable {
            msg = "Downloading…"
            actions.installUpdate { msg = it }
        }.padding(horizontal = 12.dp, vertical = 6.dp))
}

/** The pinned note: tap a line to tick it off. */
@Composable
private fun NoteCard(note: com.rusty.museecho.integrations.PinnedNote, actions: UiActions) {
    Column(
        Modifier.width(220.dp).heightIn(max = 300.dp).background(Palette.card.copy(alpha = 0.9f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (note.title.isNotEmpty()) {
            Text(note.title, color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            note.items.forEach { item ->
                Text("•  $item", color = Palette.ink, fontSize = 15.sp, lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth().clickable { actions.removeNote(item) }.padding(vertical = 3.dp))
            }
        }
    }
}

@Composable
private fun NowPlayingChip(modifier: Modifier, actions: UiActions) {
    val playing by GadgetState.nowPlaying.collectAsState()
    val title = playing ?: return
    Row(
        modifier.background(Palette.card.copy(alpha = 0.9f), RoundedCornerShape(50)).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("♪  $title", color = Palette.ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp))
        IconButton(onClick = actions::togglePlayback, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.PlayArrow, "Pause or resume", tint = Palette.muted, modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = actions::stopPlayback, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Stop, "Stop", tint = Palette.muted, modifier = Modifier.size(20.dp))
        }
    }
}

/** Today's (and earlier) exchanges on the Echo, newest first. */
@Composable
private fun HistoryPanel(actions: UiActions, onClose: () -> Unit) {
    val items by actions.history().collectAsState()
    val day = SimpleDateFormat("EEE MMM d", Locale.US)
    val time = SimpleDateFormat("h:mm a", Locale.US)
    Box(Modifier.fillMaxSize().background(Palette.night)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("History", color = Palette.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Done") }
            }
            if (items.isEmpty()) Body("Nothing yet.", Palette.muted)
            Column(Modifier.verticalScroll(rememberScrollState())) {
                var lastDay = ""
                items.asReversed().forEach { e ->
                    val d = day.format(Date(e.at))
                    if (d != lastDay) {
                        SectionTitle(d)
                        lastDay = d
                    }
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Palette.card, RoundedCornerShape(12.dp)).padding(12.dp)) {
                        Text(listOfNotNull(time.format(Date(e.at)), e.source).joinToString("  ·  "), color = Palette.muted, fontSize = 12.sp)
                        if (e.heard.isNotEmpty()) Text("“${e.heard}”", color = Palette.muted, fontSize = 15.sp)
                        Text(com.rusty.museecho.gadget.Markdown.toAnnotated(e.reply), color = Palette.ink, fontSize = 16.sp, lineHeight = 22.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberNow(): Date {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L - System.currentTimeMillis() % 1000L)
            now = Date()
        }
    }
    return now
}

@Composable
private fun ImageScreen(s: Screen.Image, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss)) {
        Image(s.bitmap.asImageBitmap(), contentDescription = s.caption, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        if (s.caption.isNotEmpty()) {
            Text(s.caption, color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = 16.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun TextScreen(s: Screen.Text, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Palette.dusk, Palette.night))).clickable(onClick = onDismiss)
            .padding(start = 40.dp, end = 120.dp, top = 56.dp, bottom = 40.dp),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (s.title.isNotEmpty()) {
                Text(s.title, color = Palette.amber, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
            }
            Text(s.body, color = Palette.ink, fontSize = if (s.body.length < 120) 34.sp else 24.sp, lineHeight = if (s.body.length < 120) 42.sp else 32.sp)
        }
    }
}

@Composable
private fun StatusChip(modifier: Modifier) {
    val link by GadgetState.link.collectAsState()
    val detail by GadgetState.linkDetail.collectAsState()
    val name by GadgetState.museName.collectAsState()
    val micOff by GadgetState.micOff.collectAsState()
    val (color, label) = if (micOff) Palette.coral to "Mic off" else when (link) {
        Link.CONNECTED -> Palette.teal to "Connected to ${if (detail.isEmpty() || detail == "Muse") name else detail}"
        Link.CONNECTING -> Palette.amber to "Connecting"
        Link.RECONNECTING -> Palette.amber to detail.ifEmpty { "Reconnecting" }
        Link.NOT_PAIRED -> Palette.coral to "Not paired"
        Link.NO_TOKEN -> Palette.coral to "Needs SDK token"
    }
    Row(
        modifier.background(Palette.card.copy(alpha = 0.8f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Palette.ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
    }
}

@Composable
private fun Captions(modifier: Modifier, actions: UiActions) {
    val heard by GadgetState.heard.collectAsState()
    val reply by GadgetState.reply.collectAsState()
    val talk by GadgetState.talk.collectAsState()
    val images by GadgetState.replyImages.collectAsState()
    val source by GadgetState.replySource.collectAsState()
    val followUp by GadgetState.followUp.collectAsState()
    val handsFree by GadgetState.handsFree.collectAsState()
    val page by GadgetState.idlePage.collectAsState()
    val speaking by GadgetState.speaking.collectAsState()
    // On the agenda and homelab pages the caption sits smaller, and steps aside after a few
    // seconds once nothing is happening, so the page underneath stays readable.
    val compact = page != 0
    var stepAside by remember { mutableStateOf(false) }
    LaunchedEffect(reply, heard, talk, compact, speaking) {
        stepAside = false
        if (compact && !speaking && (talk == Talk.IDLE || talk == Talk.REPLIED || talk == Talk.ERROR)) {
            delay(CAPTION_ASIDE_MS)
            stepAside = true
        }
    }
    val visible = (heard.isNotEmpty() || reply.isNotEmpty() || talk != Talk.IDLE) && !stepAside
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        Column(
            Modifier.then(if (compact) Modifier.fillMaxWidth(0.62f) else Modifier.fillMaxWidth()).animateContentSize().background(Palette.card.copy(alpha = 0.92f), RoundedCornerShape(18.dp))
                .clickable(onClick = actions::clearCaptions).padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            val name = GadgetState.museName.value
            val status = when {
                talk == Talk.LISTENING && followUp -> "Listening for your reply…"
                talk == Talk.LISTENING && handsFree -> "Listening… it sends when you stop talking"
                talk == Talk.LISTENING -> "Listening… tap the button again to send"
                talk == Talk.SENDING -> "Sending to $name…"
                talk == Talk.WAITING -> "$name is thinking…"
                else -> null
            }
            source?.let { Text(it, color = Palette.amber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (heard.isNotEmpty()) Text("“$heard”", color = Palette.muted, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (status != null) Text(status, color = Palette.amber, fontSize = 15.sp)
            if (reply.isNotEmpty() || images.isNotEmpty()) {
                Row(verticalAlignment = Alignment.Top) {
                    if (reply.isNotEmpty()) {
                        Column(Modifier.weight(1f).heightIn(max = if (compact) 56.dp else 150.dp).verticalScroll(rememberScrollState())) {
                            Text(com.rusty.museecho.gadget.Markdown.toAnnotated(reply),
                                color = if (talk == Talk.ERROR) Palette.coral else Palette.ink,
                                fontSize = if (compact) 15.sp else 20.sp, lineHeight = if (compact) 20.sp else 27.sp,
                                maxLines = if (compact) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    images.take(3).forEach { bitmap ->
                        Spacer(Modifier.width(10.dp))
                        Image(bitmap.asImageBitmap(), contentDescription = "Picture from $name",
                            modifier = Modifier.size(width = 120.dp, height = 90.dp).clip(RoundedCornerShape(10.dp))
                                .clickable { actions.showImage(bitmap) },
                            contentScale = ContentScale.Crop)
                    }
                }
            }
        }
    }
}

@Composable
private fun TalkButton(modifier: Modifier, actions: UiActions) {
    val talk by GadgetState.talk.collectAsState()
    val link by GadgetState.link.collectAsState()
    val listening = talk == Talk.LISTENING
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(listening) {
        while (listening) {
            level = level * 0.6f + actions.micLevel() * 0.4f
            delay(50)
        }
        level = 0f
    }
    val micOff by GadgetState.micOff.collectAsState()
    // Works offline too: the Echo answers the basics itself.
    val enabled = (link != Link.NOT_PAIRED && !micOff) || listening
    Box(
        modifier.size(84.dp).scale(1f + level * 0.35f).clip(CircleShape)
            .background(if (listening) Palette.coral else if (enabled) Palette.violet else Palette.card)
            .clickable(enabled = enabled, onClick = actions::toggleTalk),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (listening) Icons.Filled.Stop else if (micOff) Icons.Filled.MicOff else Icons.Filled.Mic, if (listening) "Send" else "Talk",
            tint = if (enabled) Color.White else Palette.muted, modifier = Modifier.size(38.dp))
    }
}

@Composable
private fun TypeSheet(onSend: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClose), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier.padding(top = 30.dp).widthIn(max = 640.dp).fillMaxWidth().background(Palette.card, RoundedCornerShape(16.dp))
                .clickable(enabled = false) {}.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Message Muse") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend(text.trim()) }),
            )
            TextButton(onClick = { if (text.isNotBlank()) onSend(text.trim()) }) { Text("Send") }
        }
    }
}

/** Running timers and upcoming alarms, as small chips at the top; tap one to cancel it. */
@Composable
private fun TimerChips(modifier: Modifier, actions: UiActions) {
    val items by actions.timers().collectAsState()
    val now = rememberNow()
    // Alarms only show when they're within the next 12 hours, to keep the face calm.
    val shown = items.filter { !it.isAlarm || it.dueAtMs - now.time < 12 * 3600_000L }.take(3)
    if (shown.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { item ->
            Row(
                Modifier.background(Palette.card.copy(alpha = 0.9f), RoundedCornerShape(50))
                    .clickable { actions.cancelTimer(item.id) }.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (item.isAlarm) "⏰" else "⏱", fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (item.isAlarm) "${item.label} · ${SimpleDateFormat("h:mm a", Locale.US).format(Date(item.dueAtMs))}"
                    else "${item.label} · ${com.rusty.museecho.integrations.Timers.clock(item.dueAtMs - now.time)}",
                    color = Palette.ink, fontSize = 13.sp, maxLines = 1,
                )
            }
        }
    }
}

/** Preset requests, tap to send; hidden while a conversation is on screen. */
@Composable
private fun QuickActionRow(modifier: Modifier, actions: UiActions) {
    val list by actions.quickActions().collectAsState()
    val talk by GadgetState.talk.collectAsState()
    val heard by GadgetState.heard.collectAsState()
    val reply by GadgetState.reply.collectAsState()
    val link by GadgetState.link.collectAsState()
    val screen by GadgetState.screen.collectAsState()
    // Only over the plain home screen: never on top of a picture, a text card or a reply.
    val show = !(list.isEmpty() || talk != Talk.IDLE || heard.isNotEmpty() || reply.isNotEmpty() || link != Link.CONNECTED ||
        screen !is Screen.Idle)
    AnimatedVisibility(show, modifier, enter = fadeIn(tween(300)), exit = fadeOut(tween(200))) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        list.forEach { a ->
            Text(a.label, color = Palette.ink, fontSize = 15.sp, maxLines = 1,
                modifier = Modifier.background(Palette.card.copy(alpha = 0.92f), RoundedCornerShape(50))
                    .clickable { actions.runQuickAction(a) }.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
    }
}

/** Whether the quick-action row is on screen (the blob's cards sit above it then). */
@Composable
private fun quickActionsShowing(actions: UiActions): Boolean {
    val list by actions.quickActions().collectAsState()
    val talk by GadgetState.talk.collectAsState()
    val heard by GadgetState.heard.collectAsState()
    val reply by GadgetState.reply.collectAsState()
    val link by GadgetState.link.collectAsState()
    val screen by GadgetState.screen.collectAsState()
    return !(list.isEmpty() || talk != Talk.IDLE || heard.isNotEmpty() || reply.isNotEmpty() || link != Link.CONNECTED ||
        screen !is Screen.Idle)
}

/**
 * The blob's cards (an event soon, when to leave, reminders, what it's holding, server signs),
 * under the blob on the clock page and always above the quick-action buttons, so neither
 * hides the other. Hidden while a reply caption is up.
 */
@Composable
private fun BlobCards(modifier: Modifier, actions: UiActions) {
    val cards by GadgetState.blobCards.collectAsState()
    val page by GadgetState.idlePage.collectAsState()
    val screen by GadgetState.screen.collectAsState()
    val heard by GadgetState.heard.collectAsState()
    val reply by GadgetState.reply.collectAsState()
    val talk by GadgetState.talk.collectAsState()
    val quick = quickActionsShowing(actions)
    val captions = heard.isNotEmpty() || reply.isNotEmpty() || talk != Talk.IDLE
    val bottom by androidx.compose.animation.core.animateDpAsState(if (quick) 86.dp else 20.dp, tween(300), label = "cardsBottom")
    AnimatedVisibility(
        cards.isNotEmpty() && page == 0 && screen is Screen.Idle && !captions,
        modifier.padding(start = 20.dp, bottom = bottom).width(260.dp),
        enter = fadeIn(tween(300)), exit = fadeOut(tween(200)),
    ) {
        Column(Modifier.animateContentSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            cards.forEach { (text, color) ->
                androidx.compose.runtime.key(text) {
                    Text(text, color = Palette.night, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 3.dp).background(Color(color), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
        }
    }
}

/** A chip when the internet itself is down (Wi-Fi may still be up); the Echo answers offline meanwhile. */
@Composable
private fun InternetBanner() {
    val internet by GadgetState.internet.collectAsState()
    AnimatedVisibility(internet == false, enter = fadeIn() + androidx.compose.animation.expandVertically(),
        exit = fadeOut() + androidx.compose.animation.shrinkVertically()) {
        Text("📡 No internet: answering offline", color = Palette.night, fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 4.dp).background(Palette.muted, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 5.dp))
    }
}

/** Starts the photo frame after the configured idle time, when nothing else is going on. */
@Composable
private fun IdleFrameWatcher(actions: UiActions) {
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            val minutes = actions.frameDelayMin()
            val busy = GadgetState.talk.value != Talk.IDLE || GadgetState.speaking.value || GadgetState.ringing.value ||
                GadgetState.screen.value !is Screen.Idle || GadgetState.quiet.value
            if (busy) GadgetState.lastTouch = System.currentTimeMillis()
            if (minutes > 0 && !busy && !GadgetState.frameShowing.value &&
                System.currentTimeMillis() - GadgetState.lastTouch >= minutes * 60_000L && actions.framePhotos().isNotEmpty()
            ) {
                GadgetState.showFrame(true)
            }
        }
    }
}

/** The photo frame: a slow crossfading slideshow with the clock; any tap ends it. */
@Composable
private fun Slideshow(actions: UiActions) {
    val showing by GadgetState.frameShowing.collectAsState()
    AnimatedVisibility(showing, enter = fadeIn(tween(900)), exit = fadeOut(tween(600))) { SlideshowContent(actions) }
}

@Composable
private fun SlideshowContent(actions: UiActions) {
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(Unit) {
        var i = 0
        while (true) {
            val photos = actions.framePhotos()
            if (photos.isEmpty()) {
                GadgetState.showFrame(false)
                return@LaunchedEffect
            }
            val file = photos[i % photos.size]
            bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.rusty.museecho.gadget.EchoCommands.decodeScaled(file.readBytes()) }.getOrNull()
            } ?: bitmap
            i++
            delay(30_000)
            // Stop when something else needs the screen.
            if (GadgetState.talk.value != Talk.IDLE || GadgetState.ringing.value || GadgetState.quiet.value) {
                GadgetState.showFrame(false)
                return@LaunchedEffect
            }
        }
    }
    val now = rememberNow()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(targetState = bitmap, animationSpec = tween(1500), label = "frame") { b ->
            if (b != null) Image(b.asImageBitmap(), contentDescription = "Photo", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(
            Modifier.align(Alignment.BottomStart).padding(20.dp)
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(SimpleDateFormat("h:mm", Locale.US).format(now), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Light)
            Text(SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now), color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
        }
    }
}

/** Full-screen while a timer or alarm rings; any tap silences it. */
@Composable
private fun RingScreen(actions: UiActions) {
    val ringingNow by actions.ringing().collectAsState()
    // Keep the last labels while it fades out.
    var ringing by remember { mutableStateOf(ringingNow) }
    if (ringingNow.isNotEmpty() && ringingNow != ringing) ringing = ringingNow
    AnimatedVisibility(ringingNow.isNotEmpty(), enter = fadeIn(tween(250)) + androidx.compose.animation.scaleIn(tween(250), initialScale = 1.05f),
        exit = fadeOut(tween(400))) { RingContent(ringing, actions) }
}

@Composable
private fun RingContent(ringing: List<com.rusty.museecho.integrations.Countdown>, actions: UiActions) {
    val transition = rememberInfiniteTransition(label = "ring")
    val pulse by transition.animateFloat(0.85f, 1.1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    Box(
        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Palette.coral.copy(alpha = 0.55f), Palette.night)))
            .clickable(onClick = actions::dismissRinging),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (ringing.any { it.isAlarm }) "⏰" else "⏱", fontSize = 64.sp, modifier = Modifier.scale(pulse))
            Spacer(Modifier.height(8.dp))
            Text(ringing.joinToString(" · ") { it.label }, color = Palette.ink, fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text("Tap anywhere to stop", color = Palette.muted, fontSize = 16.sp)
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, color = Palette.amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
}

@Composable
internal fun Body(text: String, color: Color = Palette.ink) {
    Text(text, color = color, fontSize = 14.sp, lineHeight = 19.sp)
}

@Composable
internal fun Mono(text: String) {
    Text(text, color = Palette.muted, fontSize = 11.sp, lineHeight = 14.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, textAlign = TextAlign.Start)
}

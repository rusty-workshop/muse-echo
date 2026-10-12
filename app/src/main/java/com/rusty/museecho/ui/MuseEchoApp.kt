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
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
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
    /** The buttons showing now (this part of the day's set, else the all-day ones). */
    fun quickActions(): kotlinx.coroutines.flow.StateFlow<List<com.rusty.museecho.integrations.QuickAction>>
    /** The all-day buttons, as edited in Setup. */
    fun allDayQuickActions(): List<com.rusty.museecho.integrations.QuickAction>
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
    /** Guest mode for [hours]; 0 turns it off. */
    fun setGuestMode(hours: Int)
    suspend fun forecast(): com.rusty.museecho.integrations.Forecast?
    fun tvAvailable(): Boolean
    suspend fun tvKey(key: String): Boolean
    suspend fun tvApps(): List<Pair<String, String>>
    suspend fun tvLaunch(id: String): Boolean
    fun laptopAvailable(): Boolean
    suspend fun laptopShortcuts(): List<Pair<String, String>>
    suspend fun laptopShortcut(key: String): String
    suspend fun restartContainer(name: String): Pair<Boolean, String>
    fun albumFile(shot: com.rusty.museecho.gadget.AlbumShot): java.io.File?
    suspend fun spotifySearch(q: String): Pair<List<com.rusty.museecho.integrations.SpotifyItem>, String?>
    suspend fun spotifyQueue(): Pair<com.rusty.museecho.integrations.SpotifyItem?, List<com.rusty.museecho.integrations.SpotifyItem>>?
    suspend fun spotify(uri: String, queue: Boolean): Pair<Boolean, String>
    suspend fun spotifyImage(url: String): android.graphics.Bitmap?
    /** Whether the laptop link is set up (for "Send to laptop"). */
    fun canSendToLaptop(): Boolean
    /** Puts [text] on the laptop's clipboard and shows a notification there. */
    fun sendToLaptop(text: String, onResult: (Boolean) -> Unit)
    /** The touch pad: start a timer / set an alarm ("7:30 am", days MONDAY.. or none). Null, or what went wrong. */
    fun addTimer(ms: Long): String?
    fun addAlarm(time: String, days: List<String>): String?
    /** Music on the laptop: PLAY / PAUSE / NEXT / PREVIOUS / SET_VOLUME / MUTE / UNMUTE. */
    fun laptopMedia(command: String, intArg: Int = 0)
}

/** Whether guest mode is on right now (turns itself off on time, even with nothing else changing). */
@Composable
internal fun rememberGuest(): Boolean {
    val until by GadgetState.guestUntil.collectAsState()
    var on by remember(until) { mutableStateOf(until > System.currentTimeMillis()) }
    LaunchedEffect(until) {
        val left = until - System.currentTimeMillis()
        if (left > 0) {
            delay(left)
            on = false
        }
    }
    return on
}


@Composable
fun MuseEchoApp(actions: UiActions) {
    // The chosen colour theme, applied before anything draws.
    val themeName by GadgetState.theme.collectAsState()
    val desktopColors by GadgetState.desktopColors.collectAsState()
    // Read here, in composition (a read only inside SideEffect wouldn't recompose on a change).
    val colors = remember(themeName, desktopColors) {
        val t = AppTheme.of(themeName)
        (if (t == AppTheme.LAPTOP_COLOURS) desktopTheme(desktopColors) else null) ?: t.colors
    }
    androidx.compose.runtime.SideEffect { Palette.theme = colors }
    MaterialTheme(colorScheme = darkColorScheme(primary = Palette.violet, background = Palette.night, surface = Palette.card)) {
        val screen by GadgetState.screen.collectAsState()
        var showSetup by remember { mutableStateOf(false) }
        var showKeyboard by remember { mutableStateOf(false) }
        var showHistory by remember { mutableStateOf(false) }
        val micOff by GadgetState.micOff.collectAsState()

        var showQuick by remember { mutableStateOf(false) }
        val density = androidx.compose.ui.platform.LocalDensity.current
        Box(
            Modifier.fillMaxSize().background(Palette.night).pointerInput(Unit) {
                // A swipe down from the very top edge opens the volume / brightness panel.
                val edge = with(density) { 30.dp.toPx() }
                val pull = with(density) { 70.dp.toPx() }
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes
                            .firstOrNull { it.pressed && !it.previousPressed } ?: continue
                        if (down.position.y > edge) continue
                        while (true) {
                            val c = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            if (c.position.y - down.position.y > pull) {
                                showQuick = true
                                break
                            }
                        }
                    }
                }
            },
        ) {
            val bgName by GadgetState.background.collectAsState()
            androidx.compose.animation.Crossfade(targetState = AppBackground.of(bgName), animationSpec = tween(800), label = "bg") { bg ->
                AppBackgroundView(bg, actions)
            }
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
                    is Screen.Reader -> ReaderScreen(s, actions)
                }
            }
            // The status chips stop short of the icons on the right (their width varies), and the laptop
            // chip shrinks with an ellipsis rather than running into them.
            var iconsWidth by remember { mutableStateOf(0) }
            val density = androidx.compose.ui.platform.LocalDensity.current
            Row(
                Modifier.align(Alignment.TopStart).padding(14.dp).padding(end = with(density) { iconsWidth.toDp() } + 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(Modifier)
                val showPill by GadgetState.serverPill.collectAsState()
                if (showPill) ServerLight(Modifier)
                LaptopChip(Modifier.weight(1f, fill = false), actions)
            }
            UpdateChip(Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp), actions)
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp).onSizeChanged { iconsWidth = it.width }) {
                IconButton(onClick = { actions.setMicOff(!micOff) }) {
                    Icon(if (micOff) Icons.Filled.MicOff else Icons.Filled.Mic, if (micOff) "Turn mic on" else "Turn mic off",
                        tint = if (micOff) Palette.coral else Palette.muted)
                }
                IconButton(onClick = actions::morningBrief) {
                    Icon(Icons.Filled.WbSunny, "Morning brief", tint = Palette.muted)
                }
                val guest = rememberGuest()
                // Guest mode: hides personal things (agenda, notes, history, reminders) for 4 hours.
                IconButton(onClick = { actions.setGuestMode(if (guest) 0 else 4) }) {
                    Icon(if (guest) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        if (guest) "Guest mode on (tap to end)" else "Guest mode", tint = if (guest) Palette.coral else Palette.muted)
                }
                if (!guest) {
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.Filled.History, "History", tint = Palette.muted)
                    }
                }
                IconButton(onClick = { showKeyboard = true }) {
                    Icon(Icons.Filled.Keyboard, "Type to Muse", tint = Palette.muted)
                }
                IconButton(onClick = { showSetup = true }) {
                    Icon(Icons.Filled.Settings, "Setup", tint = Palette.muted)
                }
            }
            QuickActionRow(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 30.dp), actions)
            Captions(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 120.dp, bottom = 18.dp), actions)
            TalkButton(Modifier.align(Alignment.BottomEnd).padding(18.dp), actions)

            Column(Modifier.align(Alignment.TopCenter).padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                InternetBanner()
                AlertBanner()
                TimerChips(Modifier, actions)
                NowPlayingChip(Modifier.padding(top = 6.dp), actions)
                LaptopNotes()
            }
            IdleFrameWatcher(actions)
            Slideshow(actions)
            RingScreen(actions)
            val asleep by GadgetState.asleep.collectAsState()
            val nightScene by GadgetState.nightScene.collectAsState()
            AnimatedVisibility(asleep, enter = fadeIn(tween(1200)), exit = fadeOut(tween(400))) {
                NightScreen(NightScene.of(nightScene), actions)
            }
            val panelIn = fadeIn(tween(250)) + androidx.compose.animation.slideInVertically(tween(300)) { it / 12 }
            val panelOut = fadeOut(tween(200)) + androidx.compose.animation.slideOutVertically(tween(250)) { it / 12 }
            AnimatedVisibility(showHistory, enter = panelIn, exit = panelOut) { HistoryPanel(actions, onClose = { showHistory = false }) }
            AnimatedVisibility(showKeyboard, enter = panelIn, exit = panelOut) {
                TypeSheet(onSend = { actions.sendText(it); showKeyboard = false }, onClose = { showKeyboard = false })
            }
            AnimatedVisibility(showSetup, enter = panelIn, exit = panelOut) { SetupPanel(actions, onClose = { showSetup = false }) }
            QuickPanel(showQuick) { showQuick = false }
            val padOpen by GadgetState.timePad.collectAsState()
            TimePad(padOpen, actions) { GadgetState.setTimePad(false) }
            VisitorPrompt(actions)
            TalentVote()
            // Warm evenings: a faint orange wash from around sunset to sunrise (touches pass through).
            val warmth by GadgetState.warmth.collectAsState()
            val warmNow by androidx.compose.animation.core.animateFloatAsState(warmth, tween(4000), label = "warm")
            if (warmNow > 0.01f) Box(Modifier.fillMaxSize().background(Color(0xFFFF8A3D).copy(alpha = 0.11f * warmNow)))
            CleaningOverlay()
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
    val hasNote = (note.items.isNotEmpty() || note.title.isNotEmpty()) && !rememberGuest()
    // Where the screen was last tapped, in the blob's own coordinates, so its eyes can look there.
    var rowCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var blobCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var lookAt by remember { mutableStateOf<Offset?>(null) }
    var lookAtMs by remember { mutableStateOf(0L) }
    var tapAt by remember { mutableStateOf<Offset?>(null) }
    var tapAtMs by remember { mutableStateOf(0L) }
    // The blob's centre and half-size in this page, for its room and its visiting friend.
    var blobCenter by remember { mutableStateOf(Offset.Unspecified) }
    var blobHalf by remember { mutableStateOf(0f) }
    val quickShowing = quickActionsShowing(actions)
    val chipsShowing by GadgetState.blobCards.collectAsState()
    val thingNow by GadgetState.dailyThing.collectAsState()
    Box(Modifier.fillMaxSize()) {
    // Its room, behind it here as well as on the Blob page.
    BlobRoom(blobCenter, blobHalf, Modifier.fillMaxSize())
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
            .onGloballyPositioned { r ->
                rowCoords = r
                blobCoords?.takeIf { b -> b.isAttached && r.isAttached }?.let { b ->
                    blobCenter = r.localPositionOf(b, Offset(b.size.width / 2f, b.size.height / 2f))
                }
            }
            .padding(horizontal = if (hasNote) 24.dp else 40.dp)
            // Centred between the status chips at the top and the quick-action buttons at the bottom,
            // so however much shows under the clock, nothing slides under the buttons.
            .padding(top = 52.dp, bottom = if (quickShowing) 78.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(if (hasNote) 170.dp else 220.dp).onGloballyPositioned {
            blobCoords = it
            blobHalf = it.size.width / 2f
            GadgetState.blobBounds[0] = it.boundsInWindow().toAndroidRect()
            // Measured against the whole page (the row's outer bounds), padding included.
            rowCoords?.takeIf { r -> r.isAttached && it.isAttached }?.let { r ->
                blobCenter = r.localPositionOf(it, Offset(it.size.width / 2f, it.size.height / 2f))
            }
        }, contentAlignment = Alignment.Center) {
            Blob(Modifier.fillMaxSize(), actions, lookAt, lookAtMs, tapAt, tapAtMs)
        }
        Spacer(Modifier.width(if (hasNote) 20.dp else 36.dp))
        Column(Modifier.weight(1f)) {
            val faceName by GadgetState.clockFace.collectAsState()
            // Hold the clock for 3 seconds: the screen ignores touches for 30, so it can be wiped.
            ClockFaceView(ClockFace.of(faceName), now, compact = hasNote, modifier = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // A quick tap opens the timer / alarm pad; holding 3 s starts cleaning mode.
                    val letGo = withTimeoutOrNull(CLEAN_HOLD_MS) { if (waitForUpOrCancellation() != null) 1 else 0 }
                    when (letGo) {
                        null -> GadgetState.startCleaning(CLEAN_SECONDS)
                        1 -> GadgetState.setTimePad(true)
                        else -> {}
                    }
                }
            })
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
            // The how-to line fills the space only when there are no chips there.
            val hasThing = thingNow?.date == java.time.LocalDate.now().toString()
            if (chipsShowing.isEmpty() && !hasThing || link != Link.CONNECTED) Text(
                when (link) {
                    Link.CONNECTED -> "Tap the mic to talk to $name"
                    Link.NOT_PAIRED, Link.NO_TOKEN -> "Open setup (top right) to pair with $name"
                    else -> "Connecting to $name…"
                },
                color = Palette.muted.copy(alpha = 0.8f), fontSize = 15.sp,
            )
            BlobCards()
        }
        if (hasNote) NoteCard(note, actions)
    }
    // Visiting friends show up here too.
    FriendLayer(blobCenter, blobHalf, actions)
    PetTapTarget(blobCenter, blobHalf)
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
    // Guest mode leaves out the agenda page.
    val guest = rememberGuest()
    val order by GadgetState.pageOrder.collectAsState()
    val hidden by GadgetState.pagesHidden.collectAsState()
    // The clock first, then the other pages in the chosen order, minus hidden ones and ones that
    // aren't set up (TV, laptop). Guest mode leaves out the schedule and the laptop pages.
    val pages = buildList {
        add(0)
        HomePage.ordered(order).forEach { pg ->
            val ok = pg.name !in hidden && when (pg) {
                HomePage.AGENDA -> !guest
                HomePage.TV -> actions.tvAvailable()
                HomePage.LAPTOP, HomePage.SPOTIFY -> !guest && actions.laptopAvailable()
                else -> true
            }
            if (ok) add(pg.id)
        }
    }
    val pager = androidx.compose.foundation.pager.rememberPagerState(pageCount = { pages.size })
    LaunchedEffect(pager.currentPage, guest) { GadgetState.setIdlePage(pages.getOrElse(pager.currentPage) { 0 }) }
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
        when (val id = pages.getOrElse(page) { 0 }) {
            0 -> IdleFace(actions)
            1 -> AgendaPage(actions)
            8 -> BlobPage(actions)
            else -> Box(Modifier.fillMaxSize()) {
                when (id) {
                    3 -> ForecastPage(actions)
                    4 -> TvRemotePage(actions)
                    5 -> LaptopPage(actions)
                    7 -> SpotifyPage(actions)
                    9 -> NeighbourhoodPage(actions)
                    else -> HomelabPage(actions)
                }
                // The blob keeps you company here too: a little copy, same mood, peeking at the page.
                PageBlob(Modifier.align(Alignment.BottomEnd).padding(end = 128.dp, bottom = 14.dp), actions)
            }
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
            // Schoolwork due soon.
            val work by GadgetState.assignments.collectAsState()
            if (work.isNotEmpty()) {
                Text("Due soon", color = Palette.amber, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
                work.take(6).forEach { a ->
                    val days = dueInDays(a)
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        Text(dueLabel(days), color = dueColor(days), fontSize = 16.sp, modifier = Modifier.width(110.dp))
                        Text((if (a.course.isNotBlank()) "${a.course}: " else "") + a.title + (if (a.time.isNotBlank()) " · ${a.time}" else ""),
                            color = Palette.ink.copy(alpha = 0.9f), fontSize = 17.sp, lineHeight = 22.sp)
                    }
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

/** Days until an assignment is due (0 = today), or null if its date is unreadable. */
internal fun dueInDays(a: com.rusty.museecho.integrations.Assignment): Int? = runCatching {
    java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), java.time.LocalDate.parse(a.due)).toInt()
}.getOrNull()

internal fun dueLabel(days: Int?): String = when (days) {
    null -> "?"
    0 -> "Today"
    1 -> "Tomorrow"
    else -> if (days < 7) java.time.LocalDate.now().plusDays(days.toLong()).dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(3) else "in $days days"
}

/** Red on the day, amber the day before, teal otherwise. */
internal fun dueColor(days: Int?): Color = when {
    days == null -> Palette.muted
    days <= 0 -> Palette.coral
    days == 1 -> Palette.amber
    else -> Palette.teal
}

/** Download (and upload) speed from the Latitude's tests over the last two weeks. */
@Composable
private fun SpeedChart(tests: List<com.rusty.museecho.integrations.SpeedTest>) {
    val last = tests.last()
    Text("Internet  ↓ ${last.down?.toInt() ?: "?"} / ↑ ${last.up?.toInt() ?: "?"} Mbps", color = Palette.ink, fontSize = 14.sp,
        modifier = Modifier.padding(top = 8.dp))
    Canvas(Modifier.fillMaxWidth().height(46.dp).padding(top = 4.dp)) {
        val max = (tests.maxOf { it.down ?: 0.0 } * 1.1).coerceAtLeast(1.0)
        fun line(pick: (com.rusty.museecho.integrations.SpeedTest) -> Double?, color: Color) {
            val pts = tests.mapIndexedNotNull { i, t -> pick(t)?.let { v -> Offset(size.width * i / (tests.size - 1), size.height * (1f - (v / max).toFloat())) } }
            if (pts.size < 2) return
            val path = androidx.compose.ui.graphics.Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
            drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        drawLine(Palette.card, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        line({ it.up }, Palette.violet.copy(alpha = 0.7f))
        line({ it.down }, Palette.teal)
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
private const val CLEAN_HOLD_MS = 3_000L
private const val CLEAN_SECONDS = 30
private const val CAPTION_ASIDE_MS = 8_000L

/** The Latitude in detail: Kuma monitors with 24-hour uptime, disk, memory, load, battery, containers and the last backup. */
@Composable
private fun HomelabPage(actions: UiActions) {
    val lab by GadgetState.homelab.collectAsState()
    var confirmRestart by remember { mutableStateOf<String?>(null) }
    var restartNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // "Restart <container>?" — only after a press and hold, and only once confirmed.
    confirmRestart?.let { name ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRestart = null },
            title = { Text("Restart $name?") },
            text = { Text("Restarts the container on the Latitude (it's back in a few seconds).") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmRestart = null
                    restartNote = "Restarting $name…"
                    scope.launch {
                        val (ok, msg) = actions.restartContainer(name)
                        restartNote = if (ok) "✓ $name restarted" else "✗ $msg"
                        actions.homelab()?.let { GadgetState.setHomelab(it) }
                    }
                }) { Text("Restart") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmRestart = null }) { Text("Cancel") } },
        )
    }
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
            // Pixel's weekly write-up (Sunday evenings).
            val recap by GadgetState.weekRecap.collectAsState()
            recap?.let { (text, at) ->
                Spacer(Modifier.weight(1f))
                val day = java.text.SimpleDateFormat("MMM d", Locale.US).format(Date(at))
                Text("📰 This week ($day)", color = Palette.ink, fontSize = 14.sp,
                    modifier = Modifier.background(Palette.card, RoundedCornerShape(50))
                        .clickable { GadgetState.show(Screen.Text("This week on the Latitude", text)) }
                        .padding(horizontal = 12.dp, vertical = 6.dp))
            }
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
                if (h.speed.size >= 2) SpeedChart(h.speed)
                val backupLine = h.backup.at?.let { at ->
                    val then = runCatching { java.time.OffsetDateTime.parse(at).toInstant().toEpochMilli() }.getOrNull()
                    val ago = then?.let { ((System.currentTimeMillis() - it) / 3_600_000).toInt() }
                    (if (h.backup.ok) "✓ " else "✗ ") + (ago?.let { if (it < 48) "${it}h ago" else "${it / 24}d ago" } ?: at)
                } ?: "never"
                Text("Backup  $backupLine${if (h.backup.running) " (running)" else ""}",
                    color = if (h.backup.ok) Palette.ink else Palette.coral, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp))
                h.uptime_s?.let { Text("Up ${it / 86_400}d ${(it % 86_400) / 3600}h", color = Palette.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }

                Text("Containers  (press and hold to restart)", color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                restartNote?.let { Text(it, color = Palette.teal, fontSize = 13.sp) }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    h.containers.forEach { c ->
                        val bad = c.state != "running" || c.health == "unhealthy"
                        Text(c.name, color = Palette.night, fontSize = 12.sp,
                            modifier = Modifier.background(if (bad) Palette.coral else Palette.teal.copy(alpha = 0.85f), RoundedCornerShape(50))
                                .pointerInput(c.name) { detectTapGestures(onLongPress = { confirmRestart = c.name }) }
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
                // Once the reply is complete: open it full screen, or send it to the laptop.
                val done = talk == Talk.REPLIED || talk == Talk.IDLE
                if (done && reply.isNotBlank() && !compact) {
                    var sent by remember(reply) { mutableStateOf<String?>(null) }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (reply.length > READ_MORE_CHARS) {
                            CaptionButton("📖 Read more") { GadgetState.show(Screen.Reader(source ?: name, reply)) }
                        }
                        if (actions.canSendToLaptop()) {
                            CaptionButton(sent ?: "💻 Send to laptop") {
                                sent = "Sending…"
                                actions.sendToLaptop(com.rusty.museecho.gadget.Markdown.toAnnotated(reply).text) { ok ->
                                    sent = if (ok) "✓ On the laptop's clipboard" else "✗ Laptop didn't answer"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val READ_MORE_CHARS = 220

@Composable
private fun CaptionButton(label: String, onClick: () -> Unit) {
    Text(label, color = Palette.ink, fontSize = 14.sp,
        modifier = Modifier.background(Palette.night.copy(alpha = 0.6f), RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp))
}

/** A long reply, full screen and bigger: scroll by touch; ✕ closes; can go to the laptop too. */
@Composable
private fun ReaderScreen(s: Screen.Reader, actions: UiActions) {
    var sent by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Palette.dusk, Palette.night)))) {
        Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 110.dp, top = 56.dp).verticalScroll(rememberScrollState())) {
            Text(s.title, color = Palette.amber, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text(com.rusty.museecho.gadget.Markdown.toAnnotated(s.markdown), color = Palette.ink, fontSize = 25.sp, lineHeight = 36.sp)
            Spacer(Modifier.height(48.dp))
        }
        Column(Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(56.dp).background(Palette.card, CircleShape).clickable(onClick = actions::dismissScreen),
                contentAlignment = Alignment.Center) { Text("✕", color = Palette.ink, fontSize = 22.sp) }
            if (actions.canSendToLaptop()) {
                Box(Modifier.size(56.dp).background(Palette.card, CircleShape).clickable {
                    sent = "…"
                    actions.sendToLaptop(com.rusty.museecho.gadget.Markdown.toAnnotated(s.markdown).text) { ok -> sent = if (ok) "✓" else "✗" }
                }, contentAlignment = Alignment.Center) { Text(sent ?: "💻", color = Palette.ink, fontSize = 22.sp) }
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
 * The blob's cards (an event soon, when to leave, reminders, schoolwork, countdowns, server
 * signs) as a row of chips under the clock, beside the blob rather than over it. Hidden while
 * a reply caption is up.
 */
@Composable
private fun BlobCards() {
    val cards by GadgetState.blobCards.collectAsState()
    val heard by GadgetState.heard.collectAsState()
    val reply by GadgetState.reply.collectAsState()
    val talk by GadgetState.talk.collectAsState()
    val captions = heard.isNotEmpty() || reply.isNotEmpty() || talk != Talk.IDLE
    val thing by GadgetState.dailyThing.collectAsState()
    val hasThing = thing?.date == java.time.LocalDate.now().toString()
    AnimatedVisibility(
        (cards.isNotEmpty() || hasThing) && !captions,
        enter = fadeIn(tween(300)) + androidx.compose.animation.expandVertically(tween(300)),
        exit = fadeOut(tween(200)) + androidx.compose.animation.shrinkVertically(tween(250)),
    ) {
        androidx.compose.foundation.layout.FlowRow(
            Modifier.padding(top = 2.dp).animateContentSize(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Today's word / fact rides along with the chips rather than taking a line of its own.
            DailyThingSign()
            cards.forEach { (text, color) ->
                androidx.compose.runtime.key(text) {
                    Text(text, color = Palette.night, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.background(Color(color), RoundedCornerShape(50))
                            .padding(horizontal = 11.dp, vertical = 5.dp))
                }
            }
        }
    }
}

/** The laptop's battery and what it's playing, beside the status chips while it's home (not in guest mode). */
@Composable
private fun LaptopChip(modifier: Modifier = Modifier, actions: UiActions) {
    val st by GadgetState.laptopStatus.collectAsState()
    val guest = rememberGuest()
    // Still polled overnight (for the wind-down nudge), but the chip stays hidden then, as before.
    val quiet by GadgetState.quiet.collectAsState()
    val s = st
    AnimatedVisibility(s != null && !guest && !quiet, modifier, enter = fadeIn(), exit = fadeOut()) {
        if (s == null) return@AnimatedVisibility
        val battery = s.batteryPercent?.let { p -> "$p%" + if (s.charging) " ⚡" else "" }
        val playing = if (s.playing && s.track.isNotBlank()) "▶ " + s.track + (if (s.artist.isNotBlank()) " – ${s.artist}" else "") else null
        var open by remember { mutableStateOf(false) }
        Box {
            Text(listOfNotNull("💻", battery, playing).joinToString("  "),
                color = if (s.batteryPercent != null && s.batteryPercent <= 15 && !s.charging) Palette.coral else Palette.ink,
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.background(Palette.card.copy(alpha = 0.8f), RoundedCornerShape(50))
                    .clickable { open = !open }
                    .padding(horizontal = 12.dp, vertical = 6.dp))
            if (open) LaptopMusicPopup(s, actions) { open = false }
        }
    }
}

/** Play / pause, skip and volume for whatever the laptop is playing; closes itself after a while. */
@Composable
private fun LaptopMusicPopup(s: com.rusty.museecho.integrations.LaptopStatus, actions: UiActions, onClose: () -> Unit) {
    var touched by remember { mutableStateOf(0) }
    LaunchedEffect(touched) {
        delay(12_000)
        onClose()
    }
    androidx.compose.ui.window.Popup(
        alignment = Alignment.TopStart, offset = androidx.compose.ui.unit.IntOffset(0, 120),
        onDismissRequest = onClose,
    ) {
        Column(
            Modifier.width(320.dp).background(Palette.card, RoundedCornerShape(18.dp)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (s.track.isNotBlank()) s.track else "Nothing playing", color = Palette.ink, fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (s.artist.isNotBlank()) Text(s.artist, color = Palette.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                @Composable
                fun key(label: String, size: Int, command: String) {
                    Box(
                        Modifier.size(size.dp).background(Palette.violet.copy(alpha = if (size > 50) 1f else 0.35f), CircleShape)
                            .clickable { touched++; actions.laptopMedia(command) },
                        contentAlignment = Alignment.Center,
                    ) { Text(label, color = Palette.ink, fontSize = (size / 2.4).sp) }
                }
                key("⏮", 46, "PREVIOUS")
                key(if (s.playing) "⏸" else "▶", 60, if (s.playing) "PAUSE" else "PLAY")
                key("⏭", 46, "NEXT")
            }
            var vol by remember(s.volume) { mutableStateOf(s.volume.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (s.muted) "🔇" else "🔊", fontSize = 20.sp,
                    modifier = Modifier.clickable { touched++; actions.laptopMedia(if (s.muted) "UNMUTE" else "MUTE") }.padding(6.dp))
                androidx.compose.material3.Slider(
                    value = vol, onValueChange = { vol = it; touched++ }, valueRange = 0f..100f,
                    onValueChangeFinished = { actions.laptopMedia("SET_VOLUME", vol.toInt()) },
                    modifier = Modifier.weight(1f),
                )
                Text("${vol.toInt()}%", color = Palette.muted, fontSize = 13.sp, modifier = Modifier.width(44.dp).padding(start = 6.dp))
            }
        }
    }
}

/**
 * Swipe down from the top edge: the Echo's own volume (with mute) and screen brightness
 * (with auto). Closes with a tap outside or after 15 seconds untouched.
 */
@Composable
private fun QuickPanel(open: Boolean, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val audio = remember { ctx.getSystemService(android.media.AudioManager::class.java) }
    var touched by remember { mutableStateOf(0) }
    LaunchedEffect(open, touched) {
        if (open) {
            delay(15_000)
            onClose()
        }
    }
    AnimatedVisibility(open, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClose))
    }
    AnimatedVisibility(open,
        enter = androidx.compose.animation.slideInVertically(tween(260)) { -it } + fadeIn(tween(200)),
        exit = androidx.compose.animation.slideOutVertically(tween(220)) { -it } + fadeOut(tween(200))) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.padding(top = 10.dp).width(560.dp).background(Palette.card, RoundedCornerShape(22.dp))
                    .clickable(enabled = false) {}.padding(horizontal = 22.dp, vertical = 16.dp),
            ) {
                val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                var vol by remember(open) { mutableStateOf(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat()) }
                var muted by remember(open) { mutableStateOf(audio.isStreamMute(android.media.AudioManager.STREAM_MUSIC)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (muted || vol == 0f) "🔇" else "🔊", fontSize = 26.sp, modifier = Modifier.clickable {
                        touched++
                        audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                            if (muted) android.media.AudioManager.ADJUST_UNMUTE else android.media.AudioManager.ADJUST_MUTE, 0)
                        muted = !muted
                    }.padding(8.dp))
                    androidx.compose.material3.Slider(
                        value = vol, valueRange = 0f..max.toFloat(), steps = (max - 1).coerceAtLeast(0),
                        onValueChange = {
                            vol = it; touched++
                            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, it.toInt(), 0)
                            if (muted && it > 0f) muted = false
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text("${(100 * vol / max).toInt()}%", color = Palette.muted, fontSize = 15.sp, modifier = Modifier.width(52.dp).padding(start = 8.dp))
                }
                val prefs = remember { com.rusty.museecho.gadget.Prefs(ctx) }
                var auto by remember(open) { mutableStateOf(prefs.autoBrightness) }
                var bright by remember(open) { mutableStateOf(prefs.manualBrightness.takeIf { it >= 0f } ?: 0.6f) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("☀️", fontSize = 26.sp, modifier = Modifier.padding(8.dp))
                    androidx.compose.material3.Slider(
                        value = bright, valueRange = 0.04f..1f,
                        onValueChange = {
                            // Picking a brightness by hand turns auto off (the switch turns it back on).
                            bright = it; touched++
                            prefs.manualBrightness = it
                            if (auto) { auto = false; prefs.autoBrightness = false }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text("Auto", color = if (auto) Palette.teal else Palette.muted, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
                    androidx.compose.material3.Switch(checked = auto, onCheckedChange = { touched++; auto = it; prefs.autoBrightness = it })
                }
            }
        }
    }
}

/** Shown while touches are locked for wiping the screen, with the seconds left. */
@Composable
private fun CleaningOverlay() {
    val until by GadgetState.cleaningUntil.collectAsState()
    var left by remember { mutableStateOf(0) }
    LaunchedEffect(until) {
        while (true) {
            left = ((until - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)
            if (left == 0) break
            delay(250)
        }
    }
    AnimatedVisibility(left > 0, enter = fadeIn(tween(300)), exit = fadeOut(tween(400))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🧽", fontSize = 64.sp)
                Text("Cleaning mode", color = Palette.ink, fontSize = 28.sp, fontWeight = FontWeight.Light)
                Text("Touches are ignored for $left s", color = Palette.muted, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** The laptop's desktop notifications as quiet chips: each fades after 20 s; tap to read it in full. */
@Composable
private fun LaptopNotes() {
    val notes by GadgetState.laptopNotes.collectAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.animateContentSize()) {
        notes.forEach { n ->
            androidx.compose.runtime.key(n.id) {
                LaunchedEffect(n.id) {
                    delay((n.atMs + 20_000 - System.currentTimeMillis()).coerceAtLeast(0))
                    GadgetState.dismissLaptopNote(n.id)
                }
                val shown = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(shown, enter = fadeIn(tween(300)) + androidx.compose.animation.expandVertically()) {
                    Text("💻 ${n.app}: ${n.title.ifBlank { n.text }}", color = Palette.ink, fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp).widthIn(max = 420.dp)
                            .background(Palette.card.copy(alpha = 0.9f), RoundedCornerShape(50))
                            .clickable {
                                GadgetState.dismissLaptopNote(n.id)
                                GadgetState.show(Screen.Text("💻 ${n.app}", listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString("\n\n")))
                            }
                            .padding(horizontal = 12.dp, vertical = 5.dp))
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
    val now = rememberNow()
    SlideshowContent(actions, now)
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


/** A new phone joined the Wi-Fi: offer guest mode for 4 hours. Goes away by itself after 10 minutes. */
@Composable
private fun VisitorPrompt(actions: UiActions) {
    val who by GadgetState.visitorPrompt.collectAsState()
    val name = who ?: return
    LaunchedEffect(name) {
        delay(10 * 60_000L)
        GadgetState.setVisitorPrompt(null)
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { GadgetState.setVisitorPrompt(null) },
        title = { Text(if (name.isNotEmpty()) "Hi, $name! 👋" else "A visitor! 👋") },
        text = { Text("Turn on guest mode for 4 hours? It hides the agenda, notes, reminders and the laptop.") },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                actions.setGuestMode(4)
                GadgetState.setVisitorPrompt(null)
            }) { Text("Guest mode") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { GadgetState.setVisitorPrompt(null) }) { Text("No thanks") } },
    )
}


/** The talent show's vote: tap who was best (it goes away after a minute and a half). */
@Composable
private fun TalentVote() {
    val v by GadgetState.vote.collectAsState()
    val (acts, _) = v ?: return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { GadgetState.setVote(null) },
        title = { Text("🎭 Who was best?") },
        text = { Text("Tap your favourite act.") },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                acts.forEach { (id, name) ->
                    androidx.compose.material3.TextButton(onClick = { GadgetState.voted = id; GadgetState.setVote(null) }) { Text(name) }
                }
            }
        },
    )
}

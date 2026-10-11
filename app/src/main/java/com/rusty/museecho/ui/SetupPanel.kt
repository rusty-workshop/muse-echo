package com.rusty.museecho.ui

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rusty.museecho.gadget.BlePairing
import com.rusty.museecho.gadget.GadgetState
import com.rusty.museecho.gadget.Link
import com.rusty.museecho.gadget.Prefs
import kotlinx.coroutines.launch

/** Pairing, SDK token, preferences and the connection log, as a two-column overlay. */
@Composable
fun SetupPanel(actions: UiActions, onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val link by GadgetState.link.collectAsState()
    val linkDetail by GadgetState.linkDetail.collectAsState()
    val ble by GadgetState.ble.collectAsState()
    val bleDetail by GadgetState.bleDetail.collectAsState()
    val log by GadgetState.log.collectAsState()
    // Re-read on each link change: pairing and token state live in files.
    val paired = remember(link, ble) { actions.isPaired() }
    var hasToken by remember(link, ble) { mutableStateOf(actions.hasToken()) }
    var token by remember { mutableStateOf("") }
    var tokenMessage by remember { mutableStateOf<String?>(null) }
    var speak by remember { mutableStateOf(prefs.speakReplies) }
    var front by remember { mutableStateOf(prefs.comeToFront) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var showPairing by remember { mutableStateOf(false) }
    var followUp by remember { mutableStateOf(prefs.followUp) }
    var museMsgs by remember { mutableStateOf(prefs.showMuseInitiated) }
    var gadgetAlerts by remember { mutableStateOf(prefs.showGadgetAlerts) }
    var serverPill by remember { mutableStateOf(prefs.showServerPill) }
    var quietOn by remember { mutableStateOf(prefs.quietEnabled) }
    var quietStart by remember { mutableStateOf(prefs.quietStart) }
    var quietEnd by remember { mutableStateOf(prefs.quietEnd) }
    var quietMessage by remember { mutableStateOf<String?>(null) }
    var brief by remember { mutableStateOf(prefs.morningBrief) }
    var voiceChoice by remember { mutableStateOf(actions.voiceLevel()) }
    val voiceStatus by actions.voiceStatus().collectAsState()
    var speedResult by remember { mutableStateOf<String?>(null) }
    var wakeSens by remember { mutableStateOf(actions.wakeSensitivity()) }
    var frameDelay by remember { mutableStateOf(actions.frameDelayMin()) }
    var nightOff by remember { mutableStateOf(prefs.nightOffMin) }
    var hubHost by remember { mutableStateOf(actions.hubHost()) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    val pendingUpdate by GadgetState.update.collectAsState()
    val photoCount = remember { actions.framePhotos().size }
    val savedActions = remember { actions.quickActions().value }
    val qaLabels = remember { androidx.compose.runtime.mutableStateListOf(*Array(4) { savedActions.getOrNull(it)?.label.orEmpty() }) }
    val qaPrompts = remember { androidx.compose.runtime.mutableStateListOf(*Array(4) { savedActions.getOrNull(it)?.prompt.orEmpty() }) }
    var qaMessage by remember { mutableStateOf<String?>(null) }
    val uiScope = androidx.compose.runtime.rememberCoroutineScope()
    var lat by remember { mutableStateOf("%.4f".format(prefs.weatherLat)) }
    var lon by remember { mutableStateOf("%.4f".format(prefs.weatherLon)) }
    var weatherMessage by remember { mutableStateOf<String?>(null) }
    val bleOk = remember { actions.blePairingSupported() }
    val museName by GadgetState.museName.collectAsState()
    var nameField by remember { mutableStateOf(museName) }
    var laptopHost by remember { mutableStateOf(actions.laptopHost()) }
    var laptopToken by remember { mutableStateOf("") }
    var hasLaptopToken by remember { mutableStateOf(actions.hasLaptopToken()) }
    var laptopMessage by remember { mutableStateOf<String?>(null) }
    var wakeOn by remember { mutableStateOf(actions.wakeWordEnabled()) }
    var wakePhrase by remember { mutableStateOf(actions.wakePhrase()) }
    var wakeMessage by remember { mutableStateOf<String?>(null) }
    val wakeModel = remember { actions.wakeModelPresent() }

    Box(Modifier.fillMaxSize().background(Palette.night)) {
        Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp)) {
            Column(Modifier.weight(1.15f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Muse gadget setup", color = Palette.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Done") }
                }
                Body("This Echo Show is ${actions.bleName()} (${actions.nodeId()}).", Palette.muted)
                var pinMessage by remember { mutableStateOf<String?>(null) }
                TextButton(onClick = { pinMessage = actions.pinToHomeScreen() }) { Text("Add Muse Echo to the home screen") }
                pinMessage?.let { Body(it, Palette.muted) }

                if (paired && !showPairing) {
                    SectionTitle("Muse")
                    Body("Paired ✓  ${if (link == Link.CONNECTED) "Connected to $museName." else linkDetail.ifEmpty { "Connecting…" }}", Palette.teal)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = actions::reconnect) { Text("Reconnect") }
                        if (confirmUnpair) {
                            Button(onClick = { actions.unpair(); confirmUnpair = false }) { Text("Really unpair") }
                        } else {
                            OutlinedButton(onClick = { confirmUnpair = true }) { Text("Unpair") }
                        }
                        TextButton(onClick = { showPairing = true }) { Text("Token & pairing…") }
                    }
                } else {
                    SectionTitle("1 · SDK token")
                    if (hasToken) {
                        Body("Saved ✓  You can replace it below.", Palette.teal)
                    } else {
                        Body("Get one at gadgets.muse.ai → Account → SDK tokens, then paste it here. " +
                            "Or: adb push sdk_token /sdcard/Android/data/com.rusty.museecho/files/ and restart the app.")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = token, onValueChange = { token = it; tokenMessage = null },
                            placeholder = { Text("mgst_…") }, singleLine = true, modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            tokenMessage = actions.saveToken(token)
                            if (tokenMessage == null) {
                                hasToken = true
                                token = ""
                                tokenMessage = "Saved"
                            }
                        }, enabled = token.isNotBlank()) { Text("Save") }
                    }
                    tokenMessage?.let { Body(it, if (it == "Saved") Palette.teal else Palette.coral) }

                    SectionTitle("2 · Pair with the Muse app")
                    when {
                        !bleOk && !paired -> Body("This Echo's system doesn't allow apps to use Bluetooth LE, so pair " +
                            "from the laptop instead: run tools/pair-via-linux.sh in the muse-echo project, then follow " +
                            "the Muse app steps it prints. The pairing is copied here automatically.")
                        paired && ble != BlePairing.ADVERTISING && ble != BlePairing.CONNECTED ->
                            Body("Paired ✓  ${if (link == Link.CONNECTED) "Connected to $linkDetail." else linkDetail}", Palette.teal)
                        else -> Body("On your phone: Muse app → Settings → Devices → turn on Developer mode, " +
                            "then Add device (+) and pick ${actions.bleName()}. Muse warns it's a community " +
                            "device; continue. When it asks for Wi-Fi, pick the network shown (no password needed).")
                    }
                    if (bleDetail.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Body(bleDetail, when (ble) {
                            BlePairing.UNSUPPORTED, BlePairing.FAILED -> Palette.coral
                            BlePairing.DONE -> Palette.teal
                            else -> Palette.amber
                        })
                    }
                    if (ble == BlePairing.UNSUPPORTED && bleOk) {
                        Body("Fallback: pair from a Linux machine with Bluetooth and copy the pairing over " +
                            "(tools/pair-via-linux.sh in the project).", Palette.muted)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ble == BlePairing.ADVERTISING || ble == BlePairing.CONNECTED) {
                            OutlinedButton(onClick = actions::cancelPair) { Text("Stop pairing") }
                        } else if (bleOk) {
                            Button(onClick = actions::pair, enabled = hasToken) { Text(if (paired) "Pair again" else "Start pairing") }
                        }
                        if (paired) {
                            OutlinedButton(onClick = actions::reconnect) { Text("Reconnect") }
                            if (confirmUnpair) {
                                Button(onClick = { actions.unpair(); confirmUnpair = false }) { Text("Really unpair") }
                            } else {
                                OutlinedButton(onClick = { confirmUnpair = true }) { Text("Unpair") }
                            }
                        }
                    }

                }

                SectionTitle("Name on screen")
                Body("What the screen calls your Muse. Muse can also set this itself (display.set_profile).", Palette.muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = nameField, onValueChange = { nameField = it.take(32) }, singleLine = true,
                        modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { actions.setMuseName(nameField.trim()) },
                        enabled = nameField.isNotBlank() && nameField.trim() != museName) { Text("Save") }
                }

                SectionTitle("Laptop link")
                Body(if (hasLaptopToken) "Set up ✓  Muse can run your laptop shortcuts." else
                    "Lets Muse run the laptop shortcuts Sofa's Coding Mode uses. Paste the token from " +
                        "~/.config/sofa-laptop-companion/token on the laptop.", if (hasLaptopToken) Palette.teal else Palette.ink)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = laptopHost, onValueChange = { laptopHost = it }, singleLine = true,
                        label = { Text("Host:port") }, modifier = Modifier.weight(0.9f))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(value = laptopToken, onValueChange = { laptopToken = it }, singleLine = true,
                        label = { Text("Token") }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        actions.saveLaptop(laptopHost, laptopToken.ifBlank { null })
                        if (laptopToken.isNotBlank()) hasLaptopToken = true
                        laptopToken = ""
                        laptopMessage = "Saved"
                    }) { Text("Save") }
                }
                laptopMessage?.let { Body(it, Palette.teal) }

                SectionTitle("Quick actions")
                Body("Buttons on the home screen; each sends its request to $museName. $museName can also change them.", Palette.muted)
                for (i in 0 until 4) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        OutlinedTextField(value = qaLabels[i], onValueChange = { qaLabels[i] = it.take(18) }, singleLine = true,
                            label = { Text("Button") }, modifier = Modifier.width(130.dp))
                        Spacer(Modifier.width(6.dp))
                        OutlinedTextField(value = qaPrompts[i], onValueChange = { qaPrompts[i] = it.take(500) }, singleLine = true,
                            label = { Text("Request") }, modifier = Modifier.weight(1f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        actions.saveQuickActions((0 until 4).map { com.rusty.museecho.integrations.QuickAction(qaLabels[it], qaPrompts[it]) })
                        qaMessage = "Saved"
                    }) { Text("Save") }
                    TextButton(onClick = {
                        com.rusty.museecho.integrations.QuickActions.DEFAULTS.forEachIndexed { i, a -> qaLabels[i] = a.label; qaPrompts[i] = a.prompt }
                        qaMessage = "Defaults restored; Save to keep them"
                    }) { Text("Restore defaults") }
                }
                qaMessage?.let { Body(it, Palette.teal) }

                SectionTitle("Clock face")
                ClockFacePicker()

                SectionTitle("Photo frame")
                Body(if (photoCount == 0) "No photos yet. Drop pictures in \"Pictures/Echo Frame\" on the laptop (Syncthing " +
                    "brings them over within 15 minutes), or $museName can add some (frame.add)." else
                    "$photoCount photos. Starts after the Echo's been idle this long; a tap ends it. New pictures in the " +
                    "laptop's \"Pictures/Echo Frame\" folder arrive by themselves.", Palette.muted)
                Body("Slideshow clock", Palette.ink)
                FrameClockPicker(actions)
                var syncMsg by remember { mutableStateOf<String?>(null) }
                TextButton(onClick = {
                    syncMsg = "Checking the Latitude…"
                    uiScope.launch {
                        val n = actions.syncPhotosNow()
                        syncMsg = if (n == 0) "Up to date." else "Added $n photo${if (n == 1) "" else "s"}."
                    }
                }) { Text("Sync photos now") }
                syncMsg?.let { Body(it, Palette.muted) }
                ChoiceRow(
                    options = listOf(0 to "Off", 2 to "2 min", 5 to "5 min", 15 to "15 min", 30 to "30 min").map { (m, l) -> m.toString() to l },
                    selected = frameDelay.toString(),
                ) {
                    frameDelay = it.toInt()
                    actions.setFrameDelayMin(frameDelay)
                }

                SectionTitle("Voice quality")
                Body("Higher sounds more natural but is slower on the Echo: Low keeps up with speech, Medium " +
                    "waits a few seconds first, High is too slow for this chip. Each level downloads once.", Palette.muted)
                ChoiceRow(
                    options = listOf("SYSTEM" to "System") + com.rusty.museecho.gadget.NeuralVoice.Level.entries.map {
                        it.name to (it.label + if (actions.voiceInstalled(it)) "" else " · ${it.sizeMb} MB")
                    },
                    selected = voiceChoice,
                ) {
                    voiceChoice = it
                    speedResult = null
                    actions.setVoiceLevel(it)
                }
                val voiceLine = when (val st = voiceStatus) {
                    is com.rusty.museecho.gadget.NeuralVoice.Status.Downloading -> "Downloading ${st.level.label}… ${st.percent}%" to Palette.amber
                    is com.rusty.museecho.gadget.NeuralVoice.Status.Ready -> "${st.level.label} voice ready" to Palette.teal
                    is com.rusty.museecho.gadget.NeuralVoice.Status.Failed -> "${st.level.label}: ${st.why}" to Palette.coral
                    com.rusty.museecho.gadget.NeuralVoice.Status.Idle -> (if (voiceChoice == "SYSTEM") "Using the system voice" else "Loading…") to Palette.muted
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Body(voiceLine.first, voiceLine.second)
                    Spacer(Modifier.weight(1f))
                    if (voiceStatus is com.rusty.museecho.gadget.NeuralVoice.Status.Ready) {
                        TextButton(onClick = {
                            speedResult = "Testing silently…"
                            uiScope.launch { speedResult = actions.voiceSpeedTest() }
                        }) { Text("Test speed") }
                    }
                }
                speedResult?.let { Body(it, Palette.muted) }

                SectionTitle("Blob energy")
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val energy by GadgetState.blobEnergy.collectAsState()
                androidx.compose.material3.Slider(
                    value = energy,
                    onValueChange = { GadgetState.setBlobEnergy(it) },
                    onValueChangeFinished = { com.rusty.museecho.gadget.Prefs(ctx).blobEnergy = GadgetState.blobEnergy.value },
                    steps = 3,
                )
                Body(when {
                    energy < 0.2f -> "Calm: slow breaths, antics about once an hour."
                    energy < 0.45f -> "Mellow: an antic every half hour or so."
                    energy < 0.7f -> "Normal: an antic every 20 minutes or so."
                    energy < 0.9f -> "Lively: quicker breathing, antics every 10 minutes."
                    else -> "Hyper: bouncy, and up to something every few minutes."
                }, Palette.muted)
                var sounds by remember { mutableStateOf(com.rusty.museecho.gadget.Prefs(ctx).blobSounds) }
                ToggleRow("Blob sounds", "Tiny, quiet chirps when it hops, yawns or celebrates. Never during quiet hours or while talking.", sounds) {
                    sounds = it
                    com.rusty.museecho.gadget.Prefs(ctx).blobSounds = it
                }

                SectionTitle("Wake word sensitivity")
                ChoiceRow(
                    options = com.rusty.museecho.gadget.WakeWord.Sensitivity.entries.map { it.name to it.label },
                    selected = wakeSens.name,
                ) {
                    wakeSens = com.rusty.museecho.gadget.WakeWord.Sensitivity.valueOf(it)
                    actions.setWakeSensitivity(wakeSens)
                }
                Body(when (wakeSens) {
                    com.rusty.museecho.gadget.WakeWord.Sensitivity.LOW -> "Needs a clear, close \"${actions.wakePhrase()}\". Fewest false wake-ups."
                    com.rusty.museecho.gadget.WakeWord.Sensitivity.MEDIUM -> "Hears you across the room; still needs the whole phrase."
                    com.rusty.museecho.gadget.WakeWord.Sensitivity.HIGH -> "Hears quiet and distant speech, and the last word alone counts. Most false wake-ups."
                }, Palette.muted)

                SectionTitle("Wake word")
                ChoiceRow(
                    options = listOf("hey muse", "hey ${museName.lowercase()}").distinct().map { it to "\"$it\"" },
                    selected = wakePhrase.trim().lowercase(),
                ) {
                    wakePhrase = it
                    wakeMessage = actions.setWakeWord(wakeOn, it)
                }
                Body(if (wakeModel) "Say the phrase and talk; it sends when you stop. Recognised on the Echo, offline." else
                    "The offline speech model isn't on the Echo yet.", if (wakeModel) Palette.muted else Palette.coral)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = wakePhrase, onValueChange = { wakePhrase = it.take(40) }, singleLine = true,
                        label = { Text("Phrase") }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = wakeOn, enabled = wakeModel, onCheckedChange = {
                        wakeOn = it
                        wakeMessage = actions.setWakeWord(it, wakePhrase)
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        wakeMessage = "Testing silently…"
                        uiScope.launch { wakeMessage = actions.testWakePhrase(wakePhrase) }
                    }) { Text("Test phrase") }
                }
                if (wakeOn && wakePhrase.trim().lowercase() != actions.wakePhrase()) {
                    TextButton(onClick = { wakeMessage = actions.setWakeWord(true, wakePhrase) }) { Text("Use this phrase") }
                }
                wakeMessage?.let { Body(it, if (it.contains("✓")) Palette.teal else Palette.coral) }

                SectionTitle("Behaviour")
                ToggleRow("Speak Muse's replies", "Needs a text-to-speech engine (e.g. RHVoice from F-Droid)", speak) {
                    speak = it
                    prefs.speakReplies = it
                }
                ToggleRow("Come to the front", "When Muse shows something, jump over other apps, then step back after a minute idle", front) {
                    front = it
                    prefs.comeToFront = it
                }
                var autoBright by remember { mutableStateOf(prefs.autoBrightness) }
                ToggleRow("Auto brightness", "Follows the room's light: dim in the dark, bright in daylight", autoBright) {
                    autoBright = it
                    prefs.autoBrightness = it
                }
                val guestOn = rememberGuest()
                ToggleRow("Guest mode", "Hides the agenda, notes, history and reminders for 4 hours (the eye button up top does the same)", guestOn) {
                    actions.setGuestMode(if (it) 4 else 0)
                }
                var laptopNotes by remember { mutableStateOf(prefs.laptopNotifications) }
                ToggleRow("Laptop notifications", "Show the laptop's notifications as quiet chips (not in quiet hours or guest mode)", laptopNotes) {
                    laptopNotes = it
                    prefs.laptopNotifications = it
                }
                if (laptopNotes) {
                    var muted by remember { mutableStateOf(prefs.laptopMutedApps) }
                    val known = prefs.laptopKnownApps.sorted()
                    if (known.isEmpty()) Body("Apps appear here as the laptop sends notifications; switch off any you don't want.", Palette.muted)
                    known.forEach { app ->
                        ToggleRow("   $app", "", app !in muted) { on ->
                            muted = if (on) muted - app else muted + app
                            prefs.laptopMutedApps = muted
                        }
                    }
                }
                Body("Change all of this from the laptop too: open http://${actions.hubHost()}/settings", Palette.muted)
                var stayFront by remember { mutableStateOf(prefs.returnToFront) }
                ToggleRow("Stay in front", "If another app (or a stray tap) takes over the screen, come back after a minute", stayFront) {
                    stayFront = it
                    prefs.returnToFront = it
                }
                ToggleRow("Follow-up listening", "After $museName answers, listen a few seconds for your reply, no wake word needed", followUp) {
                    followUp = it
                    prefs.followUp = it
                }
                ToggleRow("Messages from $museName", "Show and speak things $museName sends on its own, like reminders", museMsgs) {
                    museMsgs = it
                    prefs.showMuseInitiated = it
                }
                ToggleRow("Server status pill", "Show the Latitude's health next to the connection chip", serverPill) {
                    serverPill = it
                    prefs.showServerPill = it
                    GadgetState.setServerPill(it)
                }
                ToggleRow("Gadget alerts", "Announce messages from your other gadgets (the server) and $museName's answer", gadgetAlerts) {
                    gadgetAlerts = it
                    prefs.showGadgetAlerts = it
                }

                ToggleRow("Morning brief", "The first \"${actions.wakePhrase()}\" after quiet hours gets weather, your day and server status (or tap the sun icon)", brief) {
                    brief = it
                    prefs.morningBrief = it
                }

                SectionTitle("Weather location")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = lat, onValueChange = { lat = it.take(10) }, singleLine = true,
                        label = { Text("Latitude") }, modifier = Modifier.width(130.dp))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(value = lon, onValueChange = { lon = it.take(10) }, singleLine = true,
                        label = { Text("Longitude") }, modifier = Modifier.width(130.dp))
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        val la = lat.trim().toDoubleOrNull()
                        val lo = lon.trim().toDoubleOrNull()
                        weatherMessage = if (la == null || lo == null || la !in -90.0..90.0 || lo !in -180.0..180.0) {
                            "Enter decimal coordinates, e.g. 30.3322 and -81.6557"
                        } else {
                            actions.saveWeatherLocation(la, lo)
                            "Saved"
                        }
                    }) { Text("Save") }
                }
                weatherMessage?.let { Body(it, if (it == "Saved") Palette.teal else Palette.coral) }

                SectionTitle("Updates")
                Body("New builds come from the Latitude's echo-hub; Android asks you to confirm each install. " +
                    "This is version ${com.rusty.museecho.BuildConfig.VERSION_NAME}.", Palette.muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = hubHost, onValueChange = { hubHost = it.take(60) }, singleLine = true,
                        label = { Text("echo-hub") }, modifier = Modifier.width(220.dp))
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        actions.setHubHost(hubHost)
                        updateMessage = "Checking…"
                        uiScope.launch { updateMessage = actions.checkForUpdate() }
                    }) { Text("Check now") }
                    if (pendingUpdate != null) {
                        Button(onClick = {
                            updateMessage = "Downloading…"
                            actions.installUpdate { updateMessage = it }
                        }) { Text("Install") }
                    }
                }
                updateMessage?.let { Body(it, Palette.muted) }

                SectionTitle("Backup")
                var backupMsg by remember { mutableStateOf<String?>(null) }
                var confirmRestore by remember { mutableStateOf(false) }
                val last = actions.lastBackupMs()
                Body(if (last == 0L) "Backs up settings, pairing, notes and the rest to the Latitude each night (around 3 a.m.). No backup yet."
                    else "Backed up to the Latitude each night. Last: " +
                        SimpleDateFormat("EEE MMM d, h:mm a", Locale.US).format(Date(last)), Palette.muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        backupMsg = "Backing up…"
                        uiScope.launch { backupMsg = actions.backupNow() ?: "Backed up." }
                    }) { Text("Back up now") }
                    TextButton(onClick = { confirmRestore = true }) { Text("Restore from backup…") }
                }
                if (confirmRestore) {
                    Body("Replace this Echo's settings, pairing and notes with the newest backup from the Latitude? The app restarts.", Palette.amber)
                    Row {
                        TextButton(onClick = {
                            confirmRestore = false
                            backupMsg = "Restoring…"
                            actions.restoreBackup { backupMsg = it }
                        }) { Text("Restore") }
                        TextButton(onClick = { confirmRestore = false }) { Text("Cancel") }
                    }
                }
                backupMsg?.let { Body(it, Palette.muted) }

                SectionTitle("Night screen")
                val nightScene by GadgetState.nightScene.collectAsState()
                ChoiceRow(options = NightScene.entries.map { it.name to it.label }, selected = nightScene) {
                    GadgetState.setNightScene(it)
                    prefs.nightScene = it
                }
                Body(NightScene.of(nightScene).blurb + " when the screen sleeps overnight. A tap wakes it.", Palette.muted)

                SectionTitle("Quiet hours")
                Body("No wake word, no speaking, a dim screen, soft timer beeps. Alarms still ring.", Palette.muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = quietStart, onValueChange = { quietStart = it.take(5) }, singleLine = true,
                        label = { Text("From") }, modifier = Modifier.width(100.dp))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(value = quietEnd, onValueChange = { quietEnd = it.take(5) }, singleLine = true,
                        label = { Text("To") }, modifier = Modifier.width(100.dp))
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        val s1 = com.rusty.museecho.gadget.QuietHours.parse(quietStart)
                        val e1 = com.rusty.museecho.gadget.QuietHours.parse(quietEnd)
                        if (s1 == null || e1 == null) {
                            quietMessage = "Use 24-hour times like 22:00"
                        } else {
                            prefs.quietStart = "%02d:%02d".format(s1.hour, s1.minute)
                            prefs.quietEnd = "%02d:%02d".format(e1.hour, e1.minute)
                            quietMessage = "Saved"
                            actions.refreshQuiet()
                        }
                    }) { Text("Save") }
                    Spacer(Modifier.weight(1f))
                    Switch(checked = quietOn, onCheckedChange = {
                        quietOn = it
                        prefs.quietEnabled = it
                        actions.refreshQuiet()
                    })
                }
                quietMessage?.let { Body(it, if (it == "Saved") Palette.teal else Palette.coral) }
                Body("During quiet hours, turn the screen off after it's been idle for:", Palette.muted)
                ChoiceRow(
                    options = listOf(0 to "Never (just dim)", 2 to "2 min", 5 to "5 min", 15 to "15 min").map { (m, l) -> m.toString() to l },
                    selected = nightOff.toString(),
                ) {
                    nightOff = it.toInt()
                    prefs.nightOffMin = nightOff
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(
                Modifier.weight(1f).fillMaxHeight().background(Palette.card, RoundedCornerShape(14.dp)).padding(10.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("Log", color = Palette.amber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                log.asReversed().forEach { Mono(it) }
            }
        }
    }
}

/** A row of mutually exclusive options, like a segmented control. */
@Composable
private fun ChoiceRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (key, label) ->
            val on = key == selected
            Text(
                label,
                color = if (on) Palette.night else Palette.ink,
                fontSize = 13.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .background(if (on) Palette.violet else Palette.card, RoundedCornerShape(50))
                    .clickable { onSelect(key) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Body(title)
            Text(subtitle, color = Palette.muted, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** The clock styles side by side, each a live miniature; tap one to use it. */
@Composable
private fun ClockFacePicker() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val current by GadgetState.clockFace.collectAsState()
    var now by remember { mutableStateOf(java.util.Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000L - System.currentTimeMillis() % 1000L)
            now = java.util.Date()
        }
    }
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        ClockFace.entries.forEach { face ->
            val selected = face.name == current
            Column(
                Modifier.width(150.dp)
                    .background(if (selected) Palette.violet.copy(alpha = 0.22f) else Palette.card, RoundedCornerShape(14.dp))
                    .border(if (selected) 2.dp else 0.dp, if (selected) Palette.violet else Color.Transparent, RoundedCornerShape(14.dp))
                    .clickable {
                        GadgetState.setClockFace(face.name)
                        com.rusty.museecho.gadget.Prefs(ctx).clockFace = face.name
                    }
                    .padding(10.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.CenterStart) {
                    ClockFaceView(face, now, scale = 0.42f)
                }
                Text(face.label, color = Palette.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(face.blurb, color = Palette.muted, fontSize = 11.sp, maxLines = 2)
            }
        }
    }
}

/** The slideshow clock styles as live miniatures over one of the frame's own photos; tap one to use it. */
@Composable
private fun FrameClockPicker(actions: UiActions) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val current by GadgetState.frameClock.collectAsState()
    val weather by GadgetState.weather.collectAsState()
    var now by remember { mutableStateOf(java.util.Date()) }
    var photo by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        photo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            actions.framePhotos().randomOrNull()?.let { f ->
                runCatching {
                    android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
                }.getOrNull()
            }
        }
        while (true) {
            kotlinx.coroutines.delay(1000L - System.currentTimeMillis() % 1000L)
            now = java.util.Date()
        }
    }
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        FrameClock.entries.forEach { style ->
            val selected = style.name == current
            Column(
                Modifier.width(205.dp)
                    .background(if (selected) Palette.violet.copy(alpha = 0.22f) else Palette.card, RoundedCornerShape(14.dp))
                    .border(if (selected) 2.dp else 0.dp, if (selected) Palette.violet else Color.Transparent, RoundedCornerShape(14.dp))
                    .clickable {
                        GadgetState.setFrameClock(style.name)
                        com.rusty.museecho.gadget.Prefs(ctx).frameClock = style.name
                    }
                    .padding(8.dp),
            ) {
                // A 2:1 miniature of the Echo's screen: the photo (or a dusky gradient) and the clock on it.
                Box(Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(8.dp))
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF5B7DB8), Color(0xFFE8A27A))))) {
                    photo?.let {
                        androidx.compose.foundation.Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    }
                    FrameClockOverlay(style, now, weather, scale = 0.2f)
                }
                Text(style.label, color = Palette.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Text(style.blurb, color = Palette.muted, fontSize = 11.sp, maxLines = 2)
            }
        }
    }
}

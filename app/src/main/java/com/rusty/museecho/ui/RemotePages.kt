package com.rusty.museecho.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rusty.museecho.gadget.GadgetState
import kotlinx.coroutines.launch

/** A round or rounded touch key with a press flash. */
@Composable
private fun Key(label: String, size: Dp = 52.dp, wide: Dp = size, color: Color = Palette.card, fontSize: Int = 20, onClick: () -> Unit) {
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(flash) { if (flash) { kotlinx.coroutines.delay(150); flash = false } }
    Box(
        Modifier.width(wide).height(size)
            .background(if (flash) Palette.violet.copy(alpha = 0.6f) else color, if (wide == size) CircleShape else RoundedCornerShape(18.dp))
            .clickable { flash = true; onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Palette.ink, fontSize = fontSize.sp, maxLines = 1) }
}

/** The Roku as a touch remote: d-pad, playback, volume, power and the installed apps. */
@Composable
internal fun TvRemotePage(actions: UiActions) {
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = actions.tvApps() }
    fun key(k: String) = scope.launch { if (!actions.tvKey(k)) status = "The TV didn't answer" else status = "" }

    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 64.dp, bottom = 84.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TV remote", color = Palette.amber, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            if (status.isNotEmpty()) Text("   $status", color = Palette.coral, fontSize = 14.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            // D-pad.
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Key("▲") { key("Up") }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Key("◀") { key("Left") }
                    Key("OK", color = Palette.violet.copy(alpha = 0.45f), fontSize = 18) { key("Select") }
                    Key("▶") { key("Right") }
                }
                Key("▼") { key("Down") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Key("↩ Back", wide = 82.dp, fontSize = 15) { key("Back") }
                    Key("⌂ Home", wide = 82.dp, fontSize = 15) { key("Home") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Key("⏪") { key("Rev") }
                    Key("⏯") { key("Play") }
                    Key("⏩") { key("Fwd") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Key("🔉") { key("VolumeDown") }
                    Key("🔇") { key("VolumeMute") }
                    Key("🔊") { key("VolumeUp") }
                }
                Key("Power", wide = 172.dp, size = 42.dp, color = Palette.coral.copy(alpha = 0.35f), fontSize = 15) { key("Power") }
            }
            // The TV's apps.
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Text("Apps", color = Palette.muted, fontSize = 13.sp)
                val list = apps
                when {
                    list == null -> Text("Finding the TV…", color = Palette.muted, fontSize = 14.sp)
                    list.isEmpty() -> Text("Couldn't reach the TV. It may be fully off; try Power.", color = Palette.coral, fontSize = 14.sp)
                    else -> FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        list.take(16).forEach { (id, name) ->
                            Text(name, color = Palette.ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.glass(RoundedCornerShape(50))
                                    .clickable { scope.launch { if (!actions.tvLaunch(id)) status = "Couldn't open $name" } }
                                    .padding(horizontal = 12.dp, vertical = 7.dp))
                        }
                    }
                }
            }
        }
    }
}

/** The laptop by touch: lock, sleep, keep awake, brightness, night light and its shortcuts. */
@Composable
internal fun LaptopPage(actions: UiActions) {
    val scope = rememberCoroutineScope()
    val st by GadgetState.laptopStatus.collectAsState()
    var shortcuts by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { shortcuts = actions.laptopShortcuts() }
    fun cmd(c: String, label: String) {
        actions.laptopMedia(c)
        note = label
    }
    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 64.dp, bottom = 84.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Laptop", color = Palette.amber, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            val s = st
            Text(if (s == null) "   not reachable" else "   " + listOfNotNull(s.batteryPercent?.let { "$it%" + if (s.charging) " ⚡" else "" },
                if (s.playing && s.track.isNotBlank()) "▶ ${s.track}" else null).joinToString("  ·  "),
                color = if (s == null) Palette.coral else Palette.muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (note.isNotEmpty()) Text(note, color = Palette.teal, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Key("🔒 Lock", wide = 128.dp, fontSize = 16) { cmd("LOCK", "Locked") }
                    Key("🌙 Sleep", wide = 128.dp, fontSize = 16) { cmd("SLEEP", "Going to sleep") }
                }
                val s = st
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val awake = s?.keepAwake == true
                    Key(if (awake) "☕ Awake: on" else "☕ Keep awake", wide = 128.dp, fontSize = 15,
                        color = if (awake) Palette.violet.copy(alpha = 0.45f) else Palette.card) {
                        cmd(if (awake) "KEEPAWAKE_OFF" else "KEEPAWAKE_ON", if (awake) "Keep-awake off" else "Keeping it awake")
                    }
                    val nl = s?.nightlight == true
                    Key(if (nl) "🌅 Night light: on" else "🌅 Night light", wide = 128.dp, fontSize = 14,
                        color = if (nl) Palette.amber.copy(alpha = 0.4f) else Palette.card) {
                        cmd(if (nl) "NIGHTLIGHT_OFF" else "NIGHTLIGHT_ON", if (nl) "Night light off" else "Night light on")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Key("☀−", wide = 70.dp, fontSize = 18) { cmd("BRIGHTNESS_DOWN", "Dimmer") }
                    Text(s?.brightness?.let { "$it%" } ?: "Brightness", color = Palette.muted, fontSize = 14.sp,
                        modifier = Modifier.width(102.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Key("☀+", wide = 70.dp, fontSize = 18) { cmd("BRIGHTNESS_UP", "Brighter") }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Text("Shortcuts", color = Palette.muted, fontSize = 13.sp)
                val list = shortcuts
                when {
                    list == null -> Text("Loading…", color = Palette.muted, fontSize = 14.sp)
                    list.isEmpty() -> Text("No shortcuts (or the laptop is away).", color = Palette.muted, fontSize = 14.sp)
                    else -> FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        list.forEach { (key, label) ->
                            Text(label, color = Palette.ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.glass(RoundedCornerShape(50))
                                    .clickable { scope.launch { note = actions.laptopShortcut(key) } }
                                    .padding(horizontal = 12.dp, vertical = 7.dp))
                        }
                    }
                }
            }
        }
    }
}

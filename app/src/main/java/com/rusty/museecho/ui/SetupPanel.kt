package com.rusty.museecho.ui

import androidx.compose.foundation.background
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

    Box(Modifier.fillMaxSize().background(Palette.night)) {
        Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp)) {
            Column(Modifier.weight(1.15f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Muse gadget setup", color = Palette.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Done") }
                }
                Body("This Echo Show is ${actions.bleName()} (${actions.nodeId()}).", Palette.muted)

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
                if (ble == BlePairing.UNSUPPORTED) {
                    Body("Fallback: pair from a Linux machine with Bluetooth and copy the pairing over " +
                        "(tools/pair-via-linux.sh in the project).", Palette.muted)
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ble == BlePairing.ADVERTISING || ble == BlePairing.CONNECTED) {
                        OutlinedButton(onClick = actions::cancelPair) { Text("Stop pairing") }
                    } else {
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

                SectionTitle("Behaviour")
                ToggleRow("Speak Muse's replies", "Needs a text-to-speech engine (e.g. RHVoice from F-Droid)", speak) {
                    speak = it
                    prefs.speakReplies = it
                }
                ToggleRow("Come to the front", "When Muse shows something, jump over the dashboard, then step back after a minute idle", front) {
                    front = it
                    prefs.comeToFront = it
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

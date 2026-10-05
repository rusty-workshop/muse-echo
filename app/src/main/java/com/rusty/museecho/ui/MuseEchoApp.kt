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
import androidx.compose.material.icons.filled.Keyboard
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
    fun wakeWordEnabled(): Boolean
    fun wakePhrase(): String
    fun wakeModelPresent(): Boolean
    fun setWakeWord(enabled: Boolean, phrase: String): String?
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

        Box(Modifier.fillMaxSize().background(Palette.night)) {
            Crossfade(targetState = screen, label = "screen") { s ->
                when (s) {
                    Screen.Idle -> IdleFace()
                    is Screen.Image -> ImageScreen(s, actions::dismissScreen)
                    is Screen.Text -> TextScreen(s, actions::dismissScreen)
                }
            }
            StatusChip(Modifier.align(Alignment.TopStart).padding(14.dp))
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                IconButton(onClick = { showKeyboard = true }) {
                    Icon(Icons.Filled.Keyboard, "Type to Muse", tint = Palette.muted)
                }
                IconButton(onClick = { showSetup = true }) {
                    Icon(Icons.Filled.Settings, "Setup", tint = Palette.muted)
                }
            }
            Captions(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 120.dp, bottom = 18.dp), actions)
            TalkButton(Modifier.align(Alignment.BottomEnd).padding(18.dp), actions)

            TimerChips(Modifier.align(Alignment.TopCenter).padding(top = 12.dp), actions)
            RingScreen(actions)
            if (showKeyboard) TypeSheet(onSend = { actions.sendText(it); showKeyboard = false }, onClose = { showKeyboard = false })
            if (showSetup) SetupPanel(actions, onClose = { showSetup = false })
        }
    }
}

@Composable
private fun IdleFace() {
    // The orb moves slowly, so 12 frames a second looks the same as 60 and
    // costs a fraction of the CPU; during quiet hours it holds still.
    val quiet by GadgetState.quiet.collectAsState()
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(quiet) {
        if (quiet) return@LaunchedEffect
        val start = System.currentTimeMillis() - (t * 1000).toLong()
        while (true) {
            t = (System.currentTimeMillis() - start) / 1000f
            delay(83)
        }
    }
    val breathe = 0.99f + 0.07f * kotlin.math.sin(t * 2 * Math.PI.toFloat() / 7.2f)
    val drift = (t / 14f) % 1f
    val talk by GadgetState.talk.collectAsState()
    val link by GadgetState.link.collectAsState()
    val name by GadgetState.museName.collectAsState()
    val now = rememberNow()

    Row(Modifier.fillMaxSize().padding(horizontal = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            val angle = drift * 2 * Math.PI
            val tint = when {
                talk == Talk.LISTENING -> Palette.coral
                talk == Talk.SENDING || talk == Talk.WAITING -> Palette.amber
                link == Link.CONNECTED -> Palette.violet
                else -> Palette.muted
            }
            Canvas(Modifier.fillMaxSize().scale(breathe)) {
                val c = center
                val r = size.minDimension / 2
                val shift = Offset((kotlin.math.cos(angle) * r * 0.18).toFloat(), (kotlin.math.sin(angle) * r * 0.18).toFloat())
                drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.35f), Color.Transparent), c, r), r, c)
                drawCircle(Brush.radialGradient(listOf(Palette.teal.copy(alpha = 0.55f), Color.Transparent), c + shift, r * 0.7f), r * 0.62f, c + shift)
                drawCircle(Brush.radialGradient(listOf(tint, tint.copy(alpha = 0.15f)), c - shift * 0.6f, r * 0.55f), r * 0.48f, c - shift * 0.6f)
            }
        }
        Spacer(Modifier.width(36.dp))
        Column {
            Text(SimpleDateFormat("h:mm", Locale.US).format(now), color = Palette.ink, fontSize = 84.sp, fontWeight = FontWeight.Light)
            Text(SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now), color = Palette.muted, fontSize = 20.sp)
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
    val (color, label) = when (link) {
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
    val visible = heard.isNotEmpty() || reply.isNotEmpty() || talk != Talk.IDLE
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        Column(
            Modifier.fillMaxWidth().background(Palette.card.copy(alpha = 0.92f), RoundedCornerShape(18.dp))
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
                        Column(Modifier.weight(1f).heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                            Text(com.rusty.museecho.gadget.Markdown.toAnnotated(reply),
                                color = if (talk == Talk.ERROR) Palette.coral else Palette.ink, fontSize = 20.sp, lineHeight = 27.sp)
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
    val enabled = link == Link.CONNECTED || listening
    Box(
        modifier.size(84.dp).scale(1f + level * 0.35f).clip(CircleShape)
            .background(if (listening) Palette.coral else if (enabled) Palette.violet else Palette.card)
            .clickable(enabled = enabled, onClick = actions::toggleTalk),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, if (listening) "Send" else "Talk",
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

/** Full-screen while a timer or alarm rings; any tap silences it. */
@Composable
private fun RingScreen(actions: UiActions) {
    val ringing by actions.ringing().collectAsState()
    if (ringing.isEmpty()) return
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

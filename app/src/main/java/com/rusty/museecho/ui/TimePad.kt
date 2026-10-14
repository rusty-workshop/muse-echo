package com.rusty.museecho.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Timer digits typed right to left, like a kitchen timer: "530" is 5 min 30 s. Returns ms, or 0. */
internal fun timerDigitsToMs(digits: String): Long {
    val d = digits.padStart(6, '0').takeLast(6)
    val h = d.substring(0, 2).toInt()
    val m = d.substring(2, 4).toInt()
    val s = d.substring(4, 6).toInt()
    return (h * 3600L + m * 60L + s) * 1000L
}

/** Alarm digits as a 12-hour time: "730" is 7:30, "1215" is 12:15. Null if it isn't a real time. */
internal fun alarmDigitsToTime(digits: String): Pair<Int, Int>? {
    if (digits.length < 3) return null
    val h = digits.dropLast(2).toInt()
    val m = digits.takeLast(2).toInt()
    return if (h in 1..12 && m in 0..59) h to m else null
}

private val TIMER_PRESETS = listOf(1, 3, 5, 10, 15, 20, 25, 30, 45, 60)
private val DAYS = listOf("MONDAY" to "M", "TUESDAY" to "T", "WEDNESDAY" to "W", "THURSDAY" to "T", "FRIDAY" to "F", "SATURDAY" to "S", "SUNDAY" to "S")

/** Tap the clock: set a timer or an alarm by touch, no talking needed. */
@Composable
internal fun TimePad(open: Boolean, actions: UiActions, onClose: () -> Unit) {
    AnimatedVisibility(open, enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onClose))
    }
    AnimatedVisibility(open, enter = fadeIn(tween(200)) + scaleIn(tween(220), initialScale = 0.94f),
        exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.97f)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PadCard(actions, onClose)
        }
    }
}

@Composable
private fun PadCard(actions: UiActions, onClose: () -> Unit) {
    var alarmMode by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf("") }
    var pm by remember { mutableStateOf(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) < 12) }
    var days by remember { mutableStateOf(setOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun startTimer(ms: Long) {
        error = actions.addTimer(ms)
        if (error == null) onClose()
    }

    Row(
        // The Echo's screen is about 800 x 400 dp: leave a margin of dimmed background to tap to close.
        Modifier.width(680.dp).height(352.dp).glass(RoundedCornerShape(26.dp))
            .clickable(enabled = false) {}.padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Left: mode, the time being typed, and presets / repeat days.
        Column(Modifier.weight(1f).fillMaxSize()) {
            Row(Modifier.background(Palette.night, RoundedCornerShape(50)).padding(4.dp)) {
                listOf(false to "⏱  Timer", true to "⏰  Alarm").forEach { (mode, label) ->
                    Text(label, color = if (alarmMode == mode) Palette.night else Palette.muted, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.background(if (alarmMode == mode) Palette.amber else Color.Transparent, RoundedCornerShape(50))
                            .clickable { if (alarmMode != mode) { alarmMode = mode; digits = ""; error = null } }
                            .padding(horizontal = 18.dp, vertical = 8.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            if (!alarmMode) {
                val d = digits.padStart(6, '0')
                Text("${d.substring(0, 2)}:${d.substring(2, 4)}:${d.substring(4, 6)}",
                    color = if (digits.isEmpty()) Palette.muted else Palette.ink, fontSize = 50.sp, fontWeight = FontWeight.Light)
                Text("hours : minutes : seconds", color = Palette.muted, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                Text("Quick start", color = Palette.muted, fontSize = 13.sp)
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TIMER_PRESETS.forEach { m ->
                        Text(if (m < 60) "$m min" else "1 hour", color = Palette.ink, fontSize = 15.sp,
                            modifier = Modifier.background(Palette.night, RoundedCornerShape(50)).clickable { startTimer(m * 60_000L) }
                                .padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
            } else {
                val t = alarmDigitsToTime(digits)
                Row(verticalAlignment = Alignment.Bottom) {
                    val shown = if (digits.isEmpty()) "-:--" else digits.padStart(3, '-').let { it.dropLast(2) + ":" + it.takeLast(2) }
                    Text(shown, color = if (t == null) Palette.muted else Palette.ink, fontSize = 56.sp, fontWeight = FontWeight.Light)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(false to "AM", true to "PM").forEach { (isPm, label) ->
                            Text(label, color = if (pm == isPm) Palette.night else Palette.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.background(if (pm == isPm) Palette.amber else Palette.night, RoundedCornerShape(8.dp))
                                    .clickable { pm = isPm }.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(if (days.isEmpty()) "Rings once (tap days to repeat)" else "Repeats on the days picked", color = Palette.muted, fontSize = 13.sp)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DAYS.forEach { (day, letter) ->
                        val on = day in days
                        Box(
                            Modifier.size(38.dp).background(if (on) Palette.violet else Palette.night, CircleShape)
                                .clickable { days = if (on) days - day else days + day },
                            contentAlignment = Alignment.Center,
                        ) { Text(letter, color = Palette.ink, fontSize = 15.sp) }
                    }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Weekdays" to DAYS.take(5).map { it.first }.toSet(), "Every day" to DAYS.map { it.first }.toSet()).forEach { (label, set) ->
                        Text(label, color = Palette.muted, fontSize = 13.sp,
                            modifier = Modifier.background(Palette.night, RoundedCornerShape(50)).clickable { days = set }
                                .padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
            error?.let { Text(it, color = Palette.coral, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        }
        // Right: the keypad and the go button.
        Column(Modifier.width(250.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val max = if (alarmMode) 4 else 6
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✕")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { k ->
                        Box(
                            Modifier.weight(1f).height(52.dp).background(Palette.night, RoundedCornerShape(14.dp)).clickable {
                                error = null
                                when (k) {
                                    "⌫" -> digits = digits.dropLast(1)
                                    "✕" -> onClose()
                                    else -> if (digits.length < max && !(digits.isEmpty() && k == "0")) digits += k
                                }
                            },
                            contentAlignment = Alignment.Center,
                        ) { Text(k, color = if (k == "✕") Palette.muted else Palette.ink, fontSize = 24.sp) }
                    }
                }
            }
            val ready = if (alarmMode) alarmDigitsToTime(digits) != null else timerDigitsToMs(digits) >= 1_000
            Box(
                Modifier.fillMaxWidth().height(52.dp).background(if (ready) Palette.violet else Palette.night, RoundedCornerShape(14.dp))
                    .clickable(enabled = ready) {
                        if (alarmMode) {
                            val (h, m) = alarmDigitsToTime(digits) ?: return@clickable
                            error = actions.addAlarm("%d:%02d %s".format(h, m, if (pm) "pm" else "am"), days.toList())
                            if (error == null) onClose()
                        } else startTimer(timerDigitsToMs(digits))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (alarmMode) "Set alarm" else "Start timer", color = if (ready) Palette.ink else Palette.muted,
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

package com.rusty.museecho.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rusty.museecho.gadget.DayWriting
import com.rusty.museecho.gadget.GadgetState

/** The blob's diary: one entry a night, written by Pixel; flip back through past days. */
@Composable
internal fun DiaryPage() {
    val entries by GadgetState.diary.collectAsState()
    // Index from the newest (0) backwards.
    var back by remember { mutableIntStateOf(0) }
    LaunchedEffect(entries.size) { back = 0 }
    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 64.dp, bottom = 84.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Blob's diary", color = Palette.amber, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (entries.size > 1) {
                Arrow("◀", enabled = back < entries.size - 1) { back++ }
                Spacer(Modifier.width(8.dp))
                Arrow("▶", enabled = back > 0) { back-- }
            }
        }
        val e = entries.getOrNull(entries.size - 1 - back)
        if (e == null) {
            Text("Nothing yet. After 9 pm, ${GadgetState.museName.value} writes the blob's day here.",
                color = Palette.muted, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp))
            return@Column
        }
        val date = runCatching {
            java.time.LocalDate.parse(e.date).format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d", java.util.Locale.US))
        }.getOrDefault(e.date)
        Text("Dear diary · $date", color = Palette.muted, fontSize = 14.sp, fontStyle = FontStyle.Italic,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        // Ends above the round mic button in the bottom-right corner.
        Box(Modifier.fillMaxWidth().weight(1f).padding(end = 60.dp).background(Palette.card.copy(alpha = 0.7f), RoundedCornerShape(18.dp))) {
            Text(e.text, color = Palette.ink, fontSize = 18.sp, lineHeight = 26.sp,
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp))
        }
    }
}

@Composable
private fun Arrow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).background(if (enabled) Palette.card else Palette.card.copy(alpha = 0.3f), RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) Palette.ink else Palette.muted.copy(alpha = 0.5f), fontSize = 18.sp) }
}

/** Today's word / fact / bit of history as a little sign under the clock; tap and the blob says the rest. */
@Composable
internal fun DailyThingSign() {
    val thing by GadgetState.dailyThing.collectAsState()
    val t = thing ?: return
    if (t.date != java.time.LocalDate.now().toString()) return
    val kind = when (t.kind) { "word" -> "Word of the day"; "history" -> "On this day"; else -> "Fact of the day" }
    Text("${DayWriting.thingIcon(t.kind)} $kind: ${t.title}", color = Palette.teal, fontSize = 15.sp, maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp).background(Palette.card.copy(alpha = 0.8f), RoundedCornerShape(50))
            .clickable { GadgetState.say(t.text, 25) }
            .padding(horizontal = 12.dp, vertical = 5.dp))
}

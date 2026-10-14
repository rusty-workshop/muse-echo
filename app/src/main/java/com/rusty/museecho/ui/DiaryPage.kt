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

/** The blob's diary (a tab on the Blob page): one entry a night, written by Pixel; flip back through past days. */
@Composable
internal fun DiaryContent(modifier: Modifier) {
    val entries by GadgetState.diary.collectAsState()
    // Index from the newest (0) backwards.
    var back by remember { mutableIntStateOf(0) }
    LaunchedEffect(entries.size) { back = 0 }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            if (entries.size > 1) {
                Arrow("◀", enabled = back < entries.size - 1) { back++ }
                Spacer(Modifier.width(8.dp))
                Arrow("▶", enabled = back > 0) { back-- }
            }
        }
        val e = entries.getOrNull(entries.size - 1 - back)
        if (e == null) {
            Text("Nothing yet. After 9 pm, ${GadgetState.museName.value} writes ${GadgetState.blobCalled()}'s day here.",
                color = Palette.muted, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp))
            return@Column
        }
        val date = runCatching {
            java.time.LocalDate.parse(e.date).format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d", java.util.Locale.US))
        }.getOrDefault(e.date)
        Text("Dear diary · $date", color = Palette.muted, fontSize = 14.sp, fontStyle = FontStyle.Italic,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        Box(Modifier.fillMaxWidth().weight(1f).glass(RoundedCornerShape(18.dp))) {
            Text(e.text, color = Palette.ink, fontSize = 16.sp, lineHeight = 23.sp,
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp))
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
    // Short on the chip (the icon says which kind it is); tap for the whole thing on a card.
    Text("${DayWriting.thingIcon(t.kind)} ${t.title}", color = Palette.teal, fontSize = 13.sp, maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.glass(RoundedCornerShape(50))
            .clickable {
                GadgetState.react(com.rusty.museecho.gadget.Reaction.HOP)
                GadgetState.show(com.rusty.museecho.gadget.Screen.Text("${DayWriting.thingIcon(t.kind)} $kind: ${t.title}", t.text))
            }
            .padding(horizontal = 11.dp, vertical = 5.dp))
}


/** A treasure hunt is on: a chip with the riddle (tap it to read it again). */
@Composable
internal fun TreasureSign() {
    val data by GadgetState.friends.collectAsState()
    val t = data.treasure?.takeIf { !it.found && it.date == java.time.LocalDate.now().toString() } ?: return
    val hider = data.friends.firstOrNull { it.id == t.friendId }?.shownName ?: "A friend"
    Text("🗺️ Treasure hunt!", color = Palette.night, fontSize = 13.sp, maxLines = 1,
        modifier = Modifier.background(Palette.amber, RoundedCornerShape(50))
            .clickable {
                GadgetState.show(com.rusty.museecho.gadget.Screen.Text("🗺️ $hider's treasure hunt",
                    t.riddle + "\n\nTap the right thing in ${GadgetState.blobCalled()}'s room to find it."))
            }
            .padding(horizontal = 11.dp, vertical = 5.dp))
}

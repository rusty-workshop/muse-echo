package com.rusty.museecho.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rusty.museecho.gadget.Friend
import com.rusty.museecho.gadget.FriendsBrain
import com.rusty.museecho.gadget.FriendsBrain.Where
import com.rusty.museecho.gadget.GadgetService
import com.rusty.museecho.gadget.GadgetState
import com.rusty.museecho.gadget.Screen

/**
 * A little street with a house for each of the blob's friends (and the blob's own, with its
 * mailbox). Windows are lit when a friend's home; tap a house to invite that friend over.
 */
@Composable
internal fun NeighbourhoodPage(actions: UiActions) {
    val data by GadgetState.friends.collectAsState()
    val visit by GadgetState.friend.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    var note by remember { mutableStateOf("") }
    val hour = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
    val visiting = visit?.friendIds.orEmpty().toSet()
    val unread = data.letters.filter { !it.read && !it.fromBlob }
    val blobName by GadgetState.blobName.collectAsState()
    val weather by GadgetState.weather.collectAsState()
    val today = java.time.LocalDate.now()
    // The street dresses up for the season.
    val decor = when {
        today.monthValue == 10 && today.dayOfMonth >= 20 -> StreetDecor.HALLOWEEN
        weather?.summary?.lowercase()?.contains("snow") == true || today.monthValue == 12 || (today.monthValue in 1..2 && (weather?.tempF ?: 99) <= 45) -> StreetDecor.WINTER
        today.monthValue in 6..8 -> StreetDecor.SUMMER
        else -> StreetDecor.NONE
    }

    Box(Modifier.fillMaxSize()) {
        // The sky for the time of day, faintly behind the street.
        Canvas(Modifier.fillMaxSize()) {
            val sky = when (hour) {
                in 7..16 -> listOf(Color(0xFF6FA8DC), Color(0xFFB4D7F0))
                in 17..19 -> listOf(Color(0xFFE07A5F), Color(0xFFF2CC8F))
                5, 6 -> listOf(Color(0xFF9A8FC8), Color(0xFFF2B5A0))
                else -> listOf(Color(0xFF0B1030), Color(0xFF1B2050))
            }
            drawRect(Brush.verticalGradient(sky.map { it.copy(alpha = 0.18f) }))
            if (hour >= 20 || hour < 6) for (i in 0 until 30) {
                drawCircle(Color.White.copy(alpha = 0.35f), 1.4f, Offset((i * 0.618f * 997f) % size.width, (i * 0.414f * 613f) % (size.height * 0.5f)))
            }
            // The road along the bottom of the houses.
            val road = size.height - 112.dp.toPx()
            drawRect(Color(0xFF2A2433).copy(alpha = 0.8f), Offset(0f, road), Size(size.width, 14.dp.toPx()))
            // Summer: bunting strung across the street.
            if (decor == StreetDecor.SUMMER) {
                val y = road - 150.dp.toPx()
                val flags = listOf(Color(0xFFFF7A8A), Color(0xFFFFC266), Color(0xFF45D3C7), Color(0xFF8B6CFF))
                val step = 28.dp.toPx()
                for (i in 0 until (size.width / step).toInt()) {
                    val x = i * step
                    val sag = kotlin.math.sin((x / size.width) * Math.PI.toFloat()) * 10.dp.toPx()
                    drawPath(Path().apply { moveTo(x, y + sag); lineTo(x + step * 0.8f, y + sag); lineTo(x + step * 0.4f, y + sag + 14.dp.toPx()); close() },
                        flags[i % flags.size].copy(alpha = 0.7f))
                }
            }
            for (x in 0 until (size.width / 40.dp.toPx()).toInt() + 1) {
                drawRect(Color(0xFFFFE08A).copy(alpha = 0.3f), Offset(x * 40.dp.toPx() + 8.dp.toPx(), road + 6.dp.toPx()), Size(18.dp.toPx(), 2.dp.toPx()))
            }
        }
        Column(Modifier.fillMaxSize().padding(top = 64.dp, start = 40.dp, end = 40.dp, bottom = 84.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Neighbourhood", color = Palette.amber, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                if (note.isNotEmpty()) Text("   $note", color = Palette.teal, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("Tap a house to invite that friend over.", color = Palette.muted, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Bottom) {
                // The blob's own house, with its mailbox: tap to read the newest unread letter.
                House(blobName.ifBlank { "The blob" }, Palette.violet.toArgb().toLong() and 0xFFFFFFFFL, 1.15f, Where.HOME, if (unread.isNotEmpty()) "📬 ${unread.size} new letter${if (unread.size == 1) "" else "s"}" else "home",
                    hour, mailbox = true, mailFlag = unread.isNotEmpty(), decor = decor) {
                    val l = unread.lastOrNull()
                    if (l == null) note = "No new letters" else {
                        val from = data.friends.firstOrNull { it.id == l.friendId }?.shownName ?: "a friend"
                        GadgetService.instance?.life?.friends?.markLetterRead(l.atMs)
                        GadgetState.show(Screen.Text("✉️ From $from · ${l.date}", l.text))
                    }
                }
                data.friends.forEach { f ->
                    val where = FriendsBrain.whereabouts(f, visiting, now, hour)
                    val bday = FriendsBrain.isBirthday(f, today)
                    val status = if (bday) "🎂 birthday!" else where.label + if (f.trophies > 0) " · 🏆${f.trophies}" else ""
                    House(f.shownName, f.color, f.p.scale, where, status, hour, decor = decor, balloons = bday) {
                        note = when (where) {
                            Where.VISITING -> "${f.shownName} is already over! 👯"
                            Where.AWAY -> "${f.shownName} is away on a trip ✈️"
                            Where.ASLEEP -> "Shh, ${f.shownName} is asleep 😴"
                            else -> GadgetService.instance?.life?.visit(null, null, f.name)?.let { r ->
                                if (r == "already visiting") "Someone's already over: try in a bit" else "Invited ${f.shownName}! 🚶"
                            } ?: "Not ready yet"
                        }
                    }
                }
            }
        }
    }
}

/** One house: walls in the owner's colour, a roof, a door and a window that tells you if they're in. */
@Composable
private fun House(name: String, color: Long, scale: Float, where: Where, status: String, hour: Int, mailbox: Boolean = false, mailFlag: Boolean = false,
                  decor: StreetDecor = StreetDecor.NONE, balloons: Boolean = false, onTap: () -> Unit) {
    Column(Modifier.width(104.dp).clickable(onClick = onTap), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.width(96.dp).height((70 + 30 * scale).dp)) {
            drawHouse(Color(color), where, hour, mailbox, mailFlag)
            drawDecor(decor, balloons)
        }
        Text(name, color = Color(color), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(status, color = Palette.muted, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}

private fun DrawScope.drawHouse(c: Color, where: Where, hour: Int, mailbox: Boolean, mailFlag: Boolean) {
    val w = size.width * 0.78f
    val wallH = size.height * 0.52f
    val left = (size.width - w) / 2
    val top = size.height - wallH
    val wall = lerp(c, Color(0xFF1B1526), 0.45f)
    val roof = lerp(c, Color(0xFF0E0B14), 0.2f)
    drawRoundRect(wall, Offset(left, top), Size(w, wallH), CornerRadius(4f))
    drawPath(Path().apply {
        moveTo(left - w * 0.08f, top + 2f); lineTo(size.width / 2, top - size.height * 0.36f); lineTo(left + w * 1.08f, top + 2f); close()
    }, roof)
    // Door: open when they've gone to visit the blob.
    val doorW = w * 0.24f
    val doorH = wallH * 0.55f
    val doorX = left + w * 0.62f
    drawRoundRect(Color(0xFF3A2A24), Offset(doorX, size.height - doorH), Size(doorW, doorH), CornerRadius(3f, 3f))
    if (where == Where.VISITING) drawRect(Color(0xFFFFE6A8).copy(alpha = 0.7f), Offset(doorX + doorW * 0.15f, size.height - doorH), Size(doorW * 0.7f, doorH))
    else drawCircle(Color(0xFFFFC266), doorW * 0.08f, Offset(doorX + doorW * 0.78f, size.height - doorH * 0.5f))
    // Window: lit and with a little face when they're home, dark when not.
    val win = Offset(left + w * 0.12f, top + wallH * 0.2f)
    val winS = Size(w * 0.36f, wallH * 0.38f)
    val night = hour >= 18 || hour < 7
    val lit = where == Where.HOME
    drawRoundRect(if (lit) Color(0xFFFFE08A).copy(alpha = if (night) 0.95f else 0.6f) else Color(0xFF151220), win, winS, CornerRadius(3f))
    if (lit) {
        val f = win + Offset(winS.width / 2, winS.height * 0.62f)
        drawCircle(c, winS.height * 0.32f, f)
        drawCircle(Color(0xFF0E0B14), winS.height * 0.05f, f + Offset(-winS.height * 0.1f, -winS.height * 0.04f))
        drawCircle(Color(0xFF0E0B14), winS.height * 0.05f, f + Offset(winS.height * 0.1f, -winS.height * 0.04f))
    }
    if (where == Where.ASLEEP) {
        drawCircle(Color.White.copy(alpha = 0.6f), 2.5f, win + Offset(winS.width + 6f, -4f))
        drawCircle(Color.White.copy(alpha = 0.4f), 1.8f, win + Offset(winS.width + 12f, -12f))
    }
    drawLine(Color(0xFF0E0B14).copy(alpha = 0.5f), win + Offset(winS.width / 2, 0f), win + Offset(winS.width / 2, winS.height), 2f)
    // Away on a trip: a little suitcase tag on the door.
    if (where == Where.AWAY) drawRoundRect(Color(0xFFFF7A8A), Offset(doorX - doorW * 0.6f, size.height - doorH * 0.4f), Size(doorW * 0.5f, doorH * 0.3f), CornerRadius(2f))
    // The blob's mailbox: the red flag's up when there's a new letter.
    if (!mailbox) return
    val mb = Offset(left - w * 0.06f, size.height - wallH * 0.45f)
    drawLine(Color(0xFF6E5A4A), mb + Offset(0f, wallH * 0.2f), Offset(mb.x, size.height), 3f)
    drawRoundRect(Color(0xFF8C9AA8), mb + Offset(-8f, 0f), Size(16f, 11f), CornerRadius(5f))
    if (mailFlag) drawRect(Color(0xFFFF4D5E), mb + Offset(6f, -8f), Size(3f, 9f))
}


internal enum class StreetDecor { NONE, HALLOWEEN, WINTER, SUMMER }

/** Seasonal touches on a house: a pumpkin by the door, snow on the roof; balloons on a birthday. */
private fun DrawScope.drawDecor(decor: StreetDecor, balloons: Boolean) {
    val w = size.width * 0.78f
    val wallH = size.height * 0.52f
    val left = (size.width - w) / 2
    val top = size.height - wallH
    when (decor) {
        StreetDecor.HALLOWEEN -> {
            val p = Offset(left + w * 0.5f, size.height - 6f)
            drawOval(Color(0xFFE88A3A), Offset(p.x - 8f, p.y - 9f), Size(16f, 11f))
            drawLine(Color(0xFF5FBF7F), p + Offset(0f, -9f), p + Offset(1.5f, -13f), 2f)
            drawCircle(Color(0xFFFFE08A).copy(alpha = 0.8f), 1.5f, p + Offset(-3f, -5f))
            drawCircle(Color(0xFFFFE08A).copy(alpha = 0.8f), 1.5f, p + Offset(3f, -5f))
        }
        StreetDecor.WINTER -> drawPath(Path().apply {
            moveTo(left - w * 0.08f, top + 2f); lineTo(size.width / 2, top - size.height * 0.36f); lineTo(left + w * 1.08f, top + 2f)
            lineTo(left + w * 0.95f, top - 2f); lineTo(size.width / 2, top - size.height * 0.30f); lineTo(left + w * 0.05f, top - 2f); close()
        }, Color.White.copy(alpha = 0.85f))
        else -> {}
    }
    if (balloons) {
        val anchor = Offset(left + w * 0.95f, top + 4f)
        listOf(Color(0xFFFF7A8A) to Offset(-6f, -38f), Color(0xFFFFC266) to Offset(6f, -46f), Color(0xFF45D3C7) to Offset(14f, -34f)).forEach { (c, o) ->
            drawLine(Color.White.copy(alpha = 0.5f), anchor, anchor + o + Offset(0f, 8f), 1f)
            drawOval(c, Offset(anchor.x + o.x - 6f, anchor.y + o.y - 8f), Size(12f, 16f))
        }
    }
}

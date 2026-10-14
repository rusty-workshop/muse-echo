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
    val weatherNow by GadgetState.weather.collectAsState()
    val sky = remember(weatherNow) { StreetWeather.of(weatherNow?.summary) }
    val month = java.time.LocalDate.now().monthValue
    // Rain, snow, falling leaves or blossom move ~10 times a second; otherwise twice a minute is plenty.
    val preview by GadgetState.festivalPreview.collectAsState()
    val hourNow = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val fest = preview?.takeIf { it.second > now }?.let { p -> FriendsBrain.Festival.entries.firstOrNull { it.name == p.first } }
        ?: FriendsBrain.festival(java.time.LocalDate.now(), hourNow)
    val moving = fest == FriendsBrain.Festival.PARADE || sky == StreetWeather.RAIN || sky == StreetWeather.STORM || sky == StreetWeather.SNOW || month in 3..5 || month in 9..11
    LaunchedEffect(moving) {
        while (true) {
            if (moving) awaitBeat(120) else kotlinx.coroutines.delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val rainedToday = remember(now / 600_000) {
        GadgetService.instance?.getSharedPreferences("museecho", android.content.Context.MODE_PRIVATE)
            ?.getString("garden_rain_day", "") == java.time.LocalDate.now().toString()
    }
    var note by remember { mutableStateOf("") }
    var inside by remember { mutableStateOf<String?>(null) }
    val hour = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
    val visiting = visit?.friendIds.orEmpty().toSet()
    val unread = data.letters.filter { !it.read && !it.fromBlob }
    val garden by GadgetState.garden.collectAsState()
    val marketOn = java.time.LocalDate.now().dayOfWeek == java.time.DayOfWeek.SATURDAY && hour in 10..15
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
            val street = sky
            val sky = when (hour) {
                in 7..16 -> if (street == StreetWeather.CLEAR) listOf(Color(0xFF6FA8DC), Color(0xFFB4D7F0)) else listOf(Color(0xFF7D8796), Color(0xFFA9B1BD))
                in 17..19 -> listOf(Color(0xFFE07A5F), Color(0xFFF2CC8F))
                5, 6 -> listOf(Color(0xFF9A8FC8), Color(0xFFF2B5A0))
                else -> listOf(Color(0xFF0B1030), Color(0xFF1B2050))
            }
            drawRect(Brush.verticalGradient(sky.map { it.copy(alpha = 0.18f) }))
            drawSeasonAndWeather(street, month, hour, now, rainedToday)
            when (fest) {
                FriendsBrain.Festival.FAIR -> drawHarvestFair()
                FriendsBrain.Festival.PARADE -> drawLightsParade(now, data.friends.map { Color(it.color) } + Palette.violet)
                null -> {}
            }
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
            Text(fest?.label ?: "Tap a house to look inside.", color = if (fest != null) Palette.amber else Palette.muted, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Bottom) {
                // The blob's own house, with its mailbox: tap to read the newest unread letter.
                val trip by GadgetState.trip.collectAsState()
                val tripTo = trip?.let { t -> data.friends.firstOrNull { it.id == t.friendId }?.shownName }
                House(blobName.ifBlank { "The blob" }, Palette.violet.toArgb().toLong() and 0xFFFFFFFFL, 1.15f, if (tripTo != null) Where.OUT else Where.HOME,
                    if (tripTo != null) "at $tripTo's" else if (unread.isNotEmpty()) "📬 ${unread.size} new letter${if (unread.size == 1) "" else "s"}" else "home",
                    hour, mailbox = true, mailFlag = unread.isNotEmpty(), decor = decor, garden = garden) {
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
                    val whereLabel = if (where == Where.VISITING) "visiting ${blobName.ifBlank { "the blob" }}" else where.label
                    val status = if (bday) "🎂 birthday!" else whereLabel + if (f.trophies > 0) " · 🏆${f.trophies}" else ""
                    House(f.shownName, f.color, f.p.scale, where, if (marketOn && where == Where.HOME) "selling ${FriendsBrain.marketGoods(f)}" else status,
                        hour, decor = decor, balloons = bday, stall = (marketOn || fest == FriendsBrain.Festival.FAIR) && where == Where.HOME,
                        upgrade = data.upgrades[f.id] ?: 0, personality = f.p) { inside = f.id }
                }
                // The park at the end of the street: a picnic there on sunny weekends.
                val picnic = visit?.activity == "picnic"
                Column(Modifier.width(150.dp).clickable { note = if (picnic) "Everyone's having a picnic! 🧺" else "A quiet day in the park 🌳" },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    val who = if (picnic) listOf(Palette.violet) + visit!!.friendIds.mapNotNull { id -> data.friends.firstOrNull { it.id == id } }.map { Color(it.color) } else emptyList()
                    Canvas(Modifier.width(146.dp).height(100.dp)) { drawPark(who, hour) }
                    Text("The park", color = Color(0xFF7FC88A), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (picnic) "🧺 picnic!" else "quiet", color = Palette.muted, fontSize = 11.sp)
                }
                // A new house going up (a week of building), until someone moves in.
                data.building?.takeIf { it.friendId.isEmpty() }?.let { b ->
                    val day = runCatching { java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(b.startDate), java.time.LocalDate.now()).toInt() }.getOrDefault(0)
                    Column(Modifier.width(104.dp).clickable { note = "Someone's moving in soon… 🏗️" }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Canvas(Modifier.width(96.dp).height(100.dp)) { drawBuildingSite(day.coerceIn(0, 7), now) }
                        Text("New house", color = Palette.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (day >= 7) "moving day!" else "building · day ${day + 1} of 7", color = Palette.muted, fontSize = 11.sp)
                    }
                }
            }
        }
        // Looking inside a friend's house.
        data.friends.firstOrNull { it.id == inside }?.let { f ->
            val where = FriendsBrain.whereabouts(f, visiting, now, hour)
            HouseInside(f, where, hour, actions, onInvite = {
                note = GadgetService.instance?.life?.visit(null, null, f.name)?.let { r ->
                    if (r == "already visiting") "Someone's already over: try in a bit" else "Invited ${f.shownName}! 🚶"
                } ?: "Not ready yet"
                inside = null
            }, onClose = { inside = null })
        }
    }
}

/** One house: walls in the owner's colour, a roof, a door and a window that tells you if they're in. */
@Composable
private fun House(name: String, color: Long, scale: Float, where: Where, status: String, hour: Int, mailbox: Boolean = false, mailFlag: Boolean = false,
                  decor: StreetDecor = StreetDecor.NONE, balloons: Boolean = false, garden: Int = -1, stall: Boolean = false,
                  upgrade: Int = 0, personality: com.rusty.museecho.gadget.Personality? = null, onTap: () -> Unit) {
    Column(Modifier.width(104.dp).clickable(onClick = onTap), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.width(96.dp).height((70 + 30 * scale).dp)) {
            drawHouse(Color(color), where, hour, mailbox, mailFlag)
            drawDecor(decor, balloons)
            if (garden >= 0) drawFrontGarden(garden)
            if (stall) drawMarketStall()
            if (upgrade > 0 && personality != null) drawUpgrade(personality, upgrade, Color(color))
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


/** Puff's front garden, at the same stage as its window box. */
private fun DrawScope.drawFrontGarden(stage: Int) {
    val y = size.height - 2f
    for (i in 0 until 4) {
        val x = size.width * (0.18f + i * 0.08f)
        when (stage) {
            0 -> drawCircle(Color(0xFF5A3A28), 2f, Offset(x, y))
            1 -> drawLine(Color(0xFF7FE0A8), Offset(x, y), Offset(x, y - 5f), 2f)
            else -> {
                drawLine(Color(0xFF5FBF7F), Offset(x, y), Offset(x, y - 10f), 2f)
                drawCircle(if (stage == 3) listOf(Color(0xFFFF7A9A), Color(0xFFFFC266), Color(0xFFC79BFF))[i % 3] else Color(0xFF9FD97F), 3f, Offset(x, y - 11f))
            }
        }
    }
}

/** Market day: a small striped stall in front of the house. */
private fun DrawScope.drawMarketStall() {
    val w = size.width * 0.5f
    val x = size.width * 0.02f
    val top = size.height - size.height * 0.32f
    for (i in 0 until 5) drawRect(if (i % 2 == 0) Color(0xFFFF7A8A) else Color.White, Offset(x + i * w / 5, top), Size(w / 5, 7f))
    drawRect(Color(0xFF8A5A3C), Offset(x, size.height - 12f), Size(w, 6f))
    for (sx in listOf(x + 2f, x + w - 4f)) drawLine(Color(0xFF8A5A3C), Offset(sx, top), Offset(sx, size.height - 6f), 2f)
}


/** Inside a friend's house: its own little room, and the friend if it's in. */
@Composable
private fun HouseInside(f: Friend, where: Where, hour: Int, actions: UiActions, onInvite: () -> Unit, onClose: () -> Unit) {
    val c = Color(f.color)
    // Above the quick-action buttons (they stay on screen along the bottom).
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClose).padding(top = 58.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.width(560.dp).height(262.dp).background(lerp(c, Color(0xFF14101C), 0.82f), RoundedCornerShape(20.dp))
            .clickable(enabled = false) {}.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${f.shownName}'s house", color = c, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (where == Where.VISITING) "visiting ${GadgetState.blobCalled()}" else where.label, color = Palette.muted, fontSize = 13.sp)
            }
            Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp)) {
                Canvas(Modifier.fillMaxSize()) { drawInterior(f, c, hour) }
                if (where == Where.HOME || where == Where.ASLEEP) Blob(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).height((70 * f.p.scale).dp).width((70 * f.p.scale).dp),
                    actions, mini = true, moodOverride = if (where == Where.ASLEEP) com.rusty.museecho.gadget.Mood.SLEEPY else com.rusty.museecho.gadget.Mood.HAPPY,
                    tintOverride = c, ownStyle = false)
                else Text(when (where) { Where.VISITING -> "Out visiting ${GadgetState.blobCalled()}!"; Where.AWAY -> "Away on a trip ✈️"; else -> "Nobody home right now" },
                    color = Palette.muted, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
                // Its pet stays home (unless they've gone visiting together).
                if (f.petKind.isNotBlank() && where != Where.VISITING && where != Where.AWAY)
                    Text(FriendsBrain.petEmoji(f.petKind) + if (f.petName.isNotBlank()) " ${f.petName}" else "", color = Palette.ink, fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (where == Where.HOME || where == Where.OUT) Text("Invite over 🚶", color = Palette.night, fontSize = 15.sp,
                    modifier = Modifier.background(c, RoundedCornerShape(50)).clickable(onClick = onInvite).padding(horizontal = 16.dp, vertical = 8.dp))
                Text("Close", color = Palette.ink, fontSize = 15.sp,
                    modifier = Modifier.glass(RoundedCornerShape(50)).clickable(onClick = onClose).padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

/** Each personality's room: Pip's trampoline, Moss's pebble shelf, Doze's huge bed, Glimmer's disco ball, Pebble's hammock. */
internal fun DrawScope.drawInterior(f: Friend, c: Color, hour: Int) {
    val w = size.width
    val h = size.height
    val floor = h * 0.86f
    drawRect(lerp(c, Color(0xFF1B1526), 0.7f), Offset(0f, 0f), Size(w, floor))
    drawRect(lerp(c, Color(0xFF0E0B14), 0.6f), Offset(0f, floor), Size(w, h - floor))
    // A window with the time of day.
    val sky = if (hour in 7..17) Color(0xFF8CC3E8) else if (hour in 18..19) Color(0xFFE8A07A) else Color(0xFF1B2050)
    drawRoundRect(sky.copy(alpha = 0.8f), Offset(w * 0.06f, h * 0.12f), Size(w * 0.14f, h * 0.3f), CornerRadius(6f))
    when (f.p) {
        com.rusty.museecho.gadget.Personality.BOUNCY -> {
            drawOval(Color(0xFF2E8C85), Offset(w * 0.62f, floor - h * 0.1f), Size(w * 0.26f, h * 0.08f))
            for (x in listOf(0.64f, 0.85f)) drawLine(Color(0xFF8A8A8A), Offset(w * x, floor - h * 0.06f), Offset(w * x, floor), 4f)
            listOf(Color(0xFFFF7A8A), Color(0xFFFFC266)).forEachIndexed { i, col -> drawRect(col.copy(alpha = 0.7f), Offset(w * (0.3f + i * 0.12f), h * 0.12f), Size(w * 0.08f, h * 0.22f)) }
        }
        com.rusty.museecho.gadget.Personality.SHY -> {
            drawRect(Color(0xFF7A5536), Offset(w * 0.66f, h * 0.3f), Size(w * 0.24f, 6f))
            for (i in 0 until 6) drawOval(listOf(Color(0xFF9A9A9A), Color(0xFFB8AFA0), Color(0xFF7F8A8F))[i % 3], Offset(w * (0.67f + i * 0.037f), h * 0.3f - 12f), Size(18f, 12f))
            drawOval(Color(0xFFC79BFF).copy(alpha = 0.6f), Offset(w * 0.12f, floor - h * 0.12f), Size(w * 0.2f, h * 0.12f))
        }
        com.rusty.museecho.gadget.Personality.SLEEPY -> {
            drawRoundRect(Color(0xFFE9E4F5), Offset(w * 0.58f, floor - h * 0.22f), Size(w * 0.36f, h * 0.22f), CornerRadius(14f))
            drawRoundRect(Color(0xFF6E7BCF), Offset(w * 0.58f, floor - h * 0.14f), Size(w * 0.36f, h * 0.14f), CornerRadius(10f))
            drawOval(Color.White, Offset(w * 0.6f, floor - h * 0.26f), Size(w * 0.1f, h * 0.08f))
        }
        com.rusty.museecho.gadget.Personality.SPARKLY -> {
            val ball = Offset(w * 0.5f, h * 0.16f)
            drawLine(Color.Gray, Offset(ball.x, 0f), ball, 2f)
            drawCircle(Color(0xFFD0D6E0), h * 0.07f, ball)
            for (i in 0 until 8) drawCircle(listOf(Color(0xFFFF9EC7), Color(0xFF9AB8FF), Color(0xFFFFE08A))[i % 3].copy(alpha = 0.6f), 4f,
                Offset(w * (0.2f + i * 0.09f), h * (0.35f + (i % 3) * 0.12f)))
            drawRoundRect(Color(0xFFBFE6F5).copy(alpha = 0.5f), Offset(w * 0.8f, h * 0.2f), Size(w * 0.1f, h * 0.45f), CornerRadius(8f))
        }
        com.rusty.museecho.gadget.Personality.TINY -> {
            val a = Offset(w * 0.62f, h * 0.38f); val b = Offset(w * 0.9f, h * 0.38f)
            drawLine(Color(0xFF7A5536), Offset(a.x, 0f), a, 2f); drawLine(Color(0xFF7A5536), Offset(b.x, 0f), b, 2f)
            drawArc(Color(0xFFFFC266), 0f, 180f, false, Offset(a.x, a.y - h * 0.1f), Size(b.x - a.x, h * 0.2f), style = androidx.compose.ui.graphics.drawscope.Stroke(6f))
            for (i in 0 until 5) drawLine(Color(0xFF7A5536), Offset(w * 0.3f, floor - i * h * 0.12f), Offset(w * 0.36f, floor - i * h * 0.12f), 3f)
            drawLine(Color(0xFF7A5536), Offset(w * 0.3f, floor), Offset(w * 0.3f, floor - h * 0.5f), 3f)
            drawLine(Color(0xFF7A5536), Offset(w * 0.36f, floor), Offset(w * 0.36f, floor - h * 0.5f), 3f)
        }
    }
    // Its trophies on a little shelf.
    for (i in 0 until f.trophies.coerceAtMost(5)) drawCircle(Color(0xFFFFC266), 6f, Offset(w * (0.25f + i * 0.04f), h * 0.08f))
}


/** The street's weather, from the forecast's words. */
internal enum class StreetWeather {
    CLEAR, CLOUDY, RAIN, STORM, SNOW, FOG;

    companion object {
        fun of(summary: String?): StreetWeather {
            val s = summary.orEmpty().lowercase()
            return when {
                "thunder" in s -> STORM
                "snow" in s -> SNOW
                "rain" in s || "drizzle" in s || "shower" in s -> RAIN
                "fog" in s || "mist" in s -> FOG
                "cloud" in s || "overcast" in s -> CLOUDY
                else -> CLEAR
            }
        }
    }
}

/**
 * Behind the houses: trees for the season (blossom in spring, green in summer with a little pool,
 * orange in autumn with leaves on the road, bare and snowy in winter with a snowman), and the real
 * weather on top (sun, clouds, rain and puddles, snow, fog, a storm's flash, a rainbow after rain).
 */
private fun DrawScope.drawSeasonAndWeather(w: StreetWeather, month: Int, hour: Int, now: Long, rainedToday: Boolean) {
    val road = size.height - 112.dp.toPx()
    val t = (now % 3_600_000L) / 1000f
    val day = hour in 7..18
    val season = when (month) { 3, 4, 5 -> 0; 6, 7, 8 -> 1; 9, 10, 11 -> 2; else -> 3 }
    // Trees along the back of the street.
    val treeXs = listOf(0.05f, 0.33f, 0.61f, 0.9f)
    treeXs.forEach { fx ->
        val x = size.width * fx
        val trunkTop = road - 120.dp.toPx()
        drawLine(Color(0xFF5A4030).copy(alpha = 0.6f), Offset(x, road), Offset(x, trunkTop), 6.dp.toPx())
        val crown = Offset(x, trunkTop - 14.dp.toPx())
        when (season) {
            0 -> { drawCircle(Color(0xFFFFB7D5).copy(alpha = 0.55f), 30.dp.toPx(), crown); drawCircle(Color(0xFFFFD6E8).copy(alpha = 0.5f), 20.dp.toPx(), crown + Offset(12.dp.toPx(), 8.dp.toPx())) }
            1 -> drawCircle(Color(0xFF4FA060).copy(alpha = 0.55f), 32.dp.toPx(), crown)
            2 -> { drawCircle(Color(0xFFE07A3A).copy(alpha = 0.55f), 30.dp.toPx(), crown); drawCircle(Color(0xFFD9A441).copy(alpha = 0.5f), 18.dp.toPx(), crown + Offset(-12.dp.toPx(), 10.dp.toPx())) }
            else -> for (k in -1..1) drawLine(Color(0xFF5A4030).copy(alpha = 0.6f), crown + Offset(0f, 20.dp.toPx()), crown + Offset(k * 18.dp.toPx(), -12.dp.toPx()), 3.dp.toPx())
        }
        // Falling petals (spring) or leaves (autumn).
        if (season == 0 || season == 2) for (k in 0 until 3) {
            val fall = ((t * 12f + k * 37f + fx * 100f) % 140f).dp.toPx()
            val px = crown.x + kotlin.math.sin(t + k) * 14.dp.toPx() + (k - 1) * 12.dp.toPx()
            drawOval((if (season == 0) Color(0xFFFFC7DD) else Color(0xFFE07A3A)).copy(alpha = 0.7f), Offset(px, crown.y + fall), Size(5.dp.toPx(), 3.dp.toPx()))
        }
    }
    // The ground for the season.
    when (season) {
        1 -> drawOval(Color(0xFF6FC3E8).copy(alpha = 0.55f), Offset(size.width * 0.72f, road + 18.dp.toPx()), Size(90.dp.toPx(), 22.dp.toPx()))
        2 -> for (k in 0 until 14) drawOval(listOf(Color(0xFFE07A3A), Color(0xFFD9A441), Color(0xFFB5523A))[k % 3].copy(alpha = 0.6f),
            Offset(size.width * ((k * 0.618f) % 1f), road + (4 + (k % 3) * 3).dp.toPx()), Size(6.dp.toPx(), 3.dp.toPx()))
        3 -> {
            drawRect(Color.White.copy(alpha = 0.35f), Offset(0f, road - 3.dp.toPx()), Size(size.width, 5.dp.toPx()))
            val sm = Offset(size.width * 0.97f, road - 14.dp.toPx())
            drawCircle(Color.White.copy(alpha = 0.9f), 12.dp.toPx(), sm)
            drawCircle(Color.White.copy(alpha = 0.9f), 8.dp.toPx(), sm + Offset(0f, -17.dp.toPx()))
            drawCircle(Color(0xFF1B1526), 1.5.dp.toPx(), sm + Offset(-3.dp.toPx(), -19.dp.toPx()))
            drawCircle(Color(0xFF1B1526), 1.5.dp.toPx(), sm + Offset(3.dp.toPx(), -19.dp.toPx()))
            drawLine(Color(0xFFFF9A3C), sm + Offset(0f, -16.dp.toPx()), sm + Offset(7.dp.toPx(), -15.dp.toPx()), 2.dp.toPx())
        }
    }
    // The weather.
    when (w) {
        StreetWeather.CLEAR -> if (day) drawCircle(Color(0xFFFFE08A).copy(alpha = 0.6f), 22.dp.toPx(), Offset(size.width * 0.86f, 70.dp.toPx()))
        StreetWeather.CLOUDY, StreetWeather.RAIN, StreetWeather.STORM -> for (k in 0 until 4) {
            val cx = ((size.width * (0.15f + k * 0.25f) + t * 6f) % (size.width + 120f)) - 60f
            val c = Color(if (w == StreetWeather.CLOUDY) 0xFFC9CED8 else 0xFF8A919E).copy(alpha = 0.45f)
            drawCircle(c, 22.dp.toPx(), Offset(cx, 60.dp.toPx())); drawCircle(c, 16.dp.toPx(), Offset(cx + 22.dp.toPx(), 66.dp.toPx()))
        }
        StreetWeather.FOG -> drawRect(Color.White.copy(alpha = 0.12f))
        else -> {}
    }
    if (w == StreetWeather.RAIN || w == StreetWeather.STORM) {
        for (k in 0 until 40) {
            val x = (k * 0.618f * 997f) % size.width
            val y = ((t * 420f + k * 53f) % (road + 20f))
            drawLine(Color(0xFFBFE6F5).copy(alpha = 0.45f), Offset(x, y), Offset(x - 3f, y + 10f), 1.5f)
        }
        // Puddles on the road.
        for (k in 0 until 4) drawOval(Color(0xFF6FA8DC).copy(alpha = 0.35f), Offset(size.width * (0.1f + k * 0.24f), road + 2.dp.toPx()), Size(40.dp.toPx(), 7.dp.toPx()))
        if (w == StreetWeather.STORM && (t % 7f) < 0.15f) drawRect(Color.White.copy(alpha = 0.35f))
    }
    if (w == StreetWeather.SNOW) for (k in 0 until 40) {
        val x = (k * 0.618f * 997f + kotlin.math.sin(t + k) * 10f) % size.width
        val y = ((t * 40f + k * 53f) % (road + 10f))
        drawCircle(Color.White.copy(alpha = 0.7f), 2f, Offset(x, y))
    }
    // A rainbow when it's rained today but stopped, in daylight.
    if (rainedToday && day && w != StreetWeather.RAIN && w != StreetWeather.STORM) {
        val colors = listOf(0xFFFF6B6B, 0xFFFFA94D, 0xFFFFE066, 0xFF69DB7C, 0xFF4DABF7, 0xFF9775FA)
        colors.forEachIndexed { k, c ->
            val r = (150 - k * 8).dp.toPx()
            drawArc(Color(c).copy(alpha = 0.35f), 180f, 180f, false, Offset(size.width * 0.5f - r, road - r * 0.9f), Size(r * 2, r * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(8.dp.toPx()))
        }
    }
}

/** A house going up over a week: the plot and foundations, a frame in scaffolding, walls, then the roof. */
private fun DrawScope.drawBuildingSite(day: Int, now: Long) {
    val w = size.width * 0.78f
    val wallH = size.height * 0.52f
    val left = (size.width - w) / 2
    val top = size.height - wallH
    val wood = Color(0xFFB08A5A)
    // Foundations.
    drawRect(Color(0xFF8A8A8A), Offset(left - 4f, size.height - 6f), Size(w + 8f, 6f))
    // Walls rising with the days.
    val built = ((day - 1).coerceAtLeast(0) / 4f).coerceAtMost(1f)
    if (day >= 1) {
        for (k in 0..4) drawLine(wood, Offset(left + w * k / 4, size.height), Offset(left + w * k / 4, top), 3f)
        drawLine(wood, Offset(left, top), Offset(left + w, top), 3f)
        drawRect(Color(0xFFC9B8A8), Offset(left, size.height - wallH * built), Size(w, wallH * built))
    }
    // The roof on the last days.
    if (day >= 5) drawPath(Path().apply {
        moveTo(left - w * 0.08f, top + 2f); lineTo(size.width / 2, top - size.height * 0.36f * (if (day >= 6) 1f else 0.5f)); lineTo(left + w * 1.08f, top + 2f); close()
    }, Color(0xFF7A5536))
    // Scaffolding until it's done, and a little crane swinging.
    if (day < 7) {
        for (k in 0..2) drawLine(Color(0xFFFFC266), Offset(left - 8f + k * (w + 16f) / 2, size.height), Offset(left - 8f + k * (w + 16f) / 2, top - 10f), 2f)
        drawLine(Color(0xFFFFC266), Offset(left - 8f, top + wallH * 0.4f), Offset(left + w + 8f, top + wallH * 0.4f), 2f)
        val swing = kotlin.math.sin(now / 1500f) * 6f
        drawLine(Color(0xFFFF9A3C), Offset(left + w + 4f, top - 30f), Offset(left + w * 0.3f, top - 30f), 3f)
        drawLine(Color(0xFFFF9A3C), Offset(left + w + 4f, top - 30f), Offset(left + w + 4f, size.height), 3f)
        drawLine(Color.White.copy(alpha = 0.6f), Offset(left + w * 0.45f, top - 30f), Offset(left + w * 0.45f + swing, top - 10f), 1f)
        drawRect(Color(0xFF8A8A8A), Offset(left + w * 0.45f + swing - 5f, top - 10f), Size(10f, 6f))
    }
}

/** The harvest fair: autumn bunting, hay bales, an apple barrel and pumpkins along the street. */
private fun DrawScope.drawHarvestFair() {
    val road = size.height - 112.dp.toPx()
    val y = road - 160.dp.toPx()
    val flags = listOf(Color(0xFFE07A3A), Color(0xFFD9A441), Color(0xFFB5523A), Color(0xFF7FA650))
    val step = 26.dp.toPx()
    for (i in 0 until (size.width / step).toInt()) {
        val x = i * step
        val sag = kotlin.math.sin((x / size.width) * Math.PI.toFloat()) * 12.dp.toPx()
        drawPath(Path().apply { moveTo(x, y + sag); lineTo(x + step * 0.8f, y + sag); lineTo(x + step * 0.4f, y + sag + 14.dp.toPx()); close() },
            flags[i % flags.size].copy(alpha = 0.75f))
    }
    for (k in 0 until 4) {
        val x = size.width * (0.08f + k * 0.26f)
        val b = Offset(x, road + 26.dp.toPx())
        drawRoundRect(Color(0xFFE3C36A).copy(alpha = 0.8f), Offset(b.x, b.y), Size(30.dp.toPx(), 14.dp.toPx()), CornerRadius(3.dp.toPx()))
        for (l in 1..2) drawLine(Color(0xFFB08A3A).copy(alpha = 0.7f), Offset(b.x, b.y + l * 4.5f.dp.toPx()), Offset(b.x + 30.dp.toPx(), b.y + l * 4.5f.dp.toPx()), 1.dp.toPx())
        drawOval(Color(0xFFE88A3A), Offset(b.x + 34.dp.toPx(), b.y + 3.dp.toPx()), Size(14.dp.toPx(), 11.dp.toPx()))
    }
    // The apple-bobbing barrel.
    val bar = Offset(size.width * 0.9f, road + 22.dp.toPx())
    drawRoundRect(Color(0xFF8A5A3C), bar, Size(26.dp.toPx(), 20.dp.toPx()), CornerRadius(4.dp.toPx()))
    drawOval(Color(0xFF6FA8DC), Offset(bar.x + 2.dp.toPx(), bar.y - 2.dp.toPx()), Size(22.dp.toPx(), 6.dp.toPx()))
    for (k in 0..2) drawCircle(Color(0xFFE04848), 3.dp.toPx(), Offset(bar.x + (6 + k * 7).dp.toPx(), bar.y))
}

/** The winter lights parade: strings of lights over the street and lanterns bobbing along the road in everyone's colours. */
private fun DrawScope.drawLightsParade(now: Long, colors: List<Color>) {
    val road = size.height - 112.dp.toPx()
    val t = (now % 3_600_000L) / 1000f
    val y = road - 165.dp.toPx()
    val step = 22.dp.toPx()
    drawLine(Color.White.copy(alpha = 0.2f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
    for (i in 0 until (size.width / step).toInt()) {
        val x = i * step + step / 2
        val sag = kotlin.math.sin((x / size.width) * Math.PI.toFloat()) * 12.dp.toPx()
        val on = ((t * 2).toInt() + i) % 3 != 0
        drawCircle(listOf(Color(0xFFFF4D5E), Color(0xFF7FE0A8), Color(0xFFFFE08A), Color(0xFF9AB8FF))[i % 4].copy(alpha = if (on) 0.9f else 0.3f),
            3.dp.toPx(), Offset(x, y + sag + 3.dp.toPx()))
    }
    // The lanterns, a line of them walking left to right along the road.
    val span = size.width + 200.dp.toPx()
    colors.forEachIndexed { k, c ->
        val x = ((t * 22.dp.toPx() + k * 70.dp.toPx()) % span) - 100.dp.toPx()
        val ly = road + 30.dp.toPx() + kotlin.math.sin(t * 2f + k) * 3.dp.toPx()
        drawLine(Color(0xFF8A7A6A), Offset(x, ly - 26.dp.toPx()), Offset(x, ly + 20.dp.toPx()), 1.5f.dp.toPx())
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.5f), Color.Transparent), Offset(x, ly - 30.dp.toPx()), 22.dp.toPx()), 22.dp.toPx(), Offset(x, ly - 30.dp.toPx()))
        drawRoundRect(c.copy(alpha = 0.9f), Offset(x - 7.dp.toPx(), ly - 38.dp.toPx()), Size(14.dp.toPx(), 16.dp.toPx()), CornerRadius(5.dp.toPx()))
        drawRect(Color(0xFFFFE9A8).copy(alpha = 0.8f), Offset(x - 3.dp.toPx(), ly - 33.dp.toPx()), Size(6.dp.toPx(), 6.dp.toPx()))
    }
}

/** A friend's house, grown as they've got closer to Puff (level 1, then 2). */
private fun DrawScope.drawUpgrade(p: com.rusty.museecho.gadget.Personality, level: Int, c: Color) {
    val w = size.width * 0.78f
    val wallH = size.height * 0.52f
    val left = (size.width - w) / 2
    val top = size.height - wallH
    val apex = Offset(size.width / 2, top - size.height * 0.36f)
    val wood = Color(0xFF8A5A3C)
    when (p) {
        com.rusty.museecho.gadget.Personality.BOUNCY -> {
            // A treehouse on a little tree beside the house (and a slide down).
            val tx = size.width * 0.97f
            drawLine(wood, Offset(tx, size.height), Offset(tx, top - 6f), 4f)
            drawCircle(Color(0xFF4FA060), 14f, Offset(tx, top - 14f))
            drawRect(lerp(c, wood, 0.4f), Offset(tx - 10f, top - 18f), Size(20f, 14f))
            drawPath(Path().apply { moveTo(tx - 12f, top - 4f); lineTo(tx - 4f, top - 18f); lineTo(tx + 12f, top - 4f); close() }, wood)
            if (level >= 2) drawLine(Color(0xFFFF7A8A), Offset(tx - 10f, top - 4f), Offset(tx - 26f, size.height - 2f), 3f)
        }
        com.rusty.museecho.gadget.Personality.SPARKLY -> {
            // A balcony under the roof, with a glitter ball.
            val by = top + wallH * 0.12f
            drawRect(Color(0xFFD0D6E0), Offset(left + w * 0.55f, by), Size(w * 0.5f, 3f))
            for (k in 0..3) drawLine(Color(0xFFD0D6E0), Offset(left + w * (0.58f + k * 0.13f), by), Offset(left + w * (0.58f + k * 0.13f), by - 8f), 1.5f)
            drawCircle(Color(0xFFE6ECF5), 5f, Offset(left + w * 0.85f, by - 16f))
            if (level >= 2) for (k in 0..4) drawCircle(listOf(Color(0xFFFF9EC7), Color(0xFF9AB8FF), Color(0xFFFFE08A))[k % 3], 1.8f,
                Offset(left + w * (0.6f + k * 0.1f), by - 22f - (k % 2) * 6f))
        }
        com.rusty.museecho.gadget.Personality.SLEEPY -> {
            // A porch out front with a hammock (and a star lamp).
            val px = left - w * 0.05f
            drawLine(wood, Offset(px, size.height), Offset(px, top + wallH * 0.3f), 2.5f)
            drawLine(wood, Offset(px, top + wallH * 0.3f), Offset(left + w * 0.5f, top + wallH * 0.3f), 2.5f)
            drawArc(Color(0xFF6E7BCF), 0f, 180f, false, Offset(px + 2f, top + wallH * 0.4f), Size(w * 0.4f, wallH * 0.3f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
            if (level >= 2) drawCircle(Color(0xFFFFE08A), 3f, Offset(px + 4f, top + wallH * 0.36f))
        }
        com.rusty.museecho.gadget.Personality.SHY -> {
            // Moss and flowers along the roof (and a little greenhouse on top).
            for (k in 0..4) drawCircle(Color(0xFF5FBF7F), 4f, Offset(left + w * (0.1f + k * 0.2f), top - 3f - (2 - kotlin.math.abs(k - 2)) * size.height * 0.07f))
            if (level >= 2) {
                drawRect(Color(0xFFBFE6F5).copy(alpha = 0.7f), Offset(apex.x - 9f, apex.y - 12f), Size(18f, 12f))
                drawLine(Color.White.copy(alpha = 0.7f), Offset(apex.x, apex.y - 12f), Offset(apex.x, apex.y), 1f)
            }
        }
        com.rusty.museecho.gadget.Personality.TINY -> {
            // A skinny lookout tower on the roof (with a flag).
            drawRect(lerp(c, Color(0xFF1B1526), 0.3f), Offset(apex.x - 3.5f, apex.y - 20f), Size(7f, 22f))
            drawPath(Path().apply { moveTo(apex.x - 6f, apex.y - 20f); lineTo(apex.x, apex.y - 28f); lineTo(apex.x + 6f, apex.y - 20f); close() }, wood)
            if (level >= 2) {
                drawLine(Color.White.copy(alpha = 0.7f), Offset(apex.x, apex.y - 28f), Offset(apex.x, apex.y - 40f), 1f)
                drawPath(Path().apply { moveTo(apex.x, apex.y - 40f); lineTo(apex.x + 9f, apex.y - 36f); lineTo(apex.x, apex.y - 32f); close() }, Color(0xFFFF7A8A))
            }
        }
    }
}

/** The park: grass, a tree, a pond and a bench; on picnic days, a checked blanket with everyone on it. */
private fun DrawScope.drawPark(who: List<Color>, hour: Int) {
    val ground = size.height - 4f
    drawRoundRect(Color(0xFF4FA060).copy(alpha = 0.55f), Offset(0f, ground - size.height * 0.28f), Size(size.width, size.height * 0.28f), CornerRadius(10f))
    val tree = Offset(size.width * 0.18f, ground - size.height * 0.28f)
    drawLine(Color(0xFF5A4030), tree, tree + Offset(0f, -size.height * 0.35f), 5f)
    drawCircle(Color(0xFF4FA060), size.height * 0.2f, tree + Offset(0f, -size.height * 0.45f))
    drawOval(Color(0xFF6FC3E8).copy(alpha = 0.7f), Offset(size.width * 0.62f, ground - size.height * 0.14f), Size(size.width * 0.32f, size.height * 0.09f))
    val bench = Offset(size.width * 0.36f, ground - size.height * 0.3f)
    drawRect(Color(0xFF8A5A3C), bench, Size(size.width * 0.18f, 3f))
    for (x in listOf(0.01f, 0.16f)) drawLine(Color(0xFF8A5A3C), bench + Offset(size.width * x, 0f), bench + Offset(size.width * x, 8f), 2f)
    if (who.isEmpty()) return
    val bl = Offset(size.width * 0.3f, ground - size.height * 0.16f)
    val bw = size.width * 0.45f
    val sq = bw / 8
    for (k in 0 until 8) for (r in 0..1) drawRect(if ((k + r) % 2 == 0) Color(0xFFE04848) else Color.White, bl + Offset(k * sq, r * sq * 0.5f), Size(sq, sq * 0.5f))
    who.take(6).forEachIndexed { i, col ->
        val p = bl + Offset(bw * (i + 0.5f) / who.size.coerceAtMost(6), -5f)
        drawCircle(col, 6f, p)
        drawCircle(Color(0xFF0E0B14), 0.9f, p + Offset(-2f, -1f))
        drawCircle(Color(0xFF0E0B14), 0.9f, p + Offset(2f, -1f))
    }
}

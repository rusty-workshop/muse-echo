package com.rusty.museecho.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rusty.museecho.gadget.Decor
import com.rusty.museecho.gadget.GadgetState
import com.rusty.museecho.gadget.Mood
import kotlin.math.sin

/**
 * The blob's room, drawn faintly behind it on the clock page: what it has collected so far,
 * lit for the time of day. [blob] is the blob's centre and [radius] its box's half-size.
 */
@Composable
internal fun BlobRoom(blob: Offset, radius: Float, modifier: Modifier = Modifier) {
    val items by GadgetState.room.collectAsState()
    val quiet by GadgetState.quiet.collectAsState()
    if (items.isEmpty() || blob == Offset.Unspecified) return
    // Once a minute is plenty for the sky and the lamp.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    val cal = remember(now) { java.util.Calendar.getInstance().apply { timeInMillis = now } }
    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val month = cal.get(java.util.Calendar.MONTH)
    Canvas(modifier) {
        val has = { d: Decor -> d.name in items }
        val u = radius / 110f // one "dp" of the blob's 220 dp box
        val dim = if (quiet) 0.45f else 1f
        val floor = blob.y + 92 * u
        val cx = blob.x
        val evening = hour >= 17 || hour < 7

        // The wall's foot and a soft floor.
        drawRect(Brush.verticalGradient(listOf(Palette.ink.copy(alpha = 0.035f * dim), Color.Transparent), floor, floor + 60 * u),
            Offset(0f, floor), Size(cx + 135 * u, 60 * u))
        drawLine(Palette.ink.copy(alpha = 0.08f * dim), Offset(0f, floor), Offset(cx + 135 * u, floor), 1.5f * u)

        if (has(Decor.WINDOW)) drawWindow(Offset(cx - 118 * u, blob.y - 140 * u), u, hour, dim)
        if (has(Decor.STAR_POSTER)) drawStarPoster(Offset(cx + 58 * u, blob.y - 150 * u), u, dim)
        if (has(Decor.MOVIE_POSTER)) drawMoviePoster(Offset(cx + 70 * u, blob.y - 92 * u), u, dim)
        if (has(Decor.PHOTO_FRAME)) {
            drawRect(Palette.amber.copy(alpha = 0.35f * dim), Offset(cx - 42 * u, blob.y - 156 * u), Size(30 * u, 24 * u), style = Stroke(2.5f * u))
            drawRect(Palette.teal.copy(alpha = 0.2f * dim), Offset(cx - 39 * u, blob.y - 153 * u), Size(24 * u, 18 * u))
        }
        if (has(Decor.FAIRY_LIGHTS)) drawFairyLights(blob.y - 168 * u, cx + 130 * u, u, now, dim)
        if (has(Decor.RUG)) drawOval(Palette.violet.copy(alpha = 0.16f * dim), Offset(cx - 88 * u, floor - 6 * u), Size(176 * u, 26 * u))
        if (has(Decor.BOOKSHELF)) drawShelf(Offset(cx - 160 * u, floor), u, dim, trophy = has(Decor.TROPHY), globe = has(Decor.SNOW_GLOBE) && month in listOf(11, 0, 1))
        if (has(Decor.PLANT)) drawPlant(Offset(cx - 118 * u, floor), u, dim)
        if (has(Decor.RECORD_PLAYER)) {
            drawRoundRect(Palette.card.copy(alpha = 0.9f * dim), Offset(cx - 98 * u, floor - 14 * u), Size(30 * u, 14 * u), CornerRadius(2 * u))
            drawOval(Color(0xFF151218).copy(alpha = 0.9f * dim), Offset(cx - 95 * u, floor - 18 * u), Size(24 * u, 7 * u))
        }
        if (has(Decor.PUMPKIN) && month == java.util.Calendar.OCTOBER) drawPumpkin(Offset(cx + 66 * u, floor), u, dim)
        if (has(Decor.UMBRELLA_STAND)) {
            val a = Offset(cx + 96 * u, floor)
            drawRect(Palette.muted.copy(alpha = 0.35f * dim), Offset(a.x - 6 * u, a.y - 18 * u), Size(12 * u, 18 * u))
            drawLine(Palette.teal.copy(alpha = 0.5f * dim), a + Offset(-2 * u, -18 * u), a + Offset(-4 * u, -42 * u), 3 * u, StrokeCap.Round)
            drawArc(Palette.teal.copy(alpha = 0.5f * dim), 180f, 120f, false, a + Offset(-8 * u, -48 * u), Size(10 * u, 10 * u), style = Stroke(2.5f * u))
        }
        if (has(Decor.LAMP)) drawLamp(Offset(cx + 120 * u, floor), u, lit = evening, dim)
    }
}

private fun DrawScope.drawWindow(at: Offset, u: Float, hour: Int, dim: Float) {
    val w = 58 * u
    val h = 52 * u
    val sky = when (hour) {
        in 7..16 -> listOf(Color(0xFF6FA8DC), Color(0xFFB4D7F0))
        in 17..19 -> listOf(Color(0xFFE07A5F), Color(0xFFF2CC8F))
        5, 6 -> listOf(Color(0xFF9A8FC8), Color(0xFFF2B5A0))
        else -> listOf(Color(0xFF0B1030), Color(0xFF1B2050))
    }
    drawRect(Brush.verticalGradient(sky.map { it.copy(alpha = 0.32f * dim) }, at.y, at.y + h), at, Size(w, h))
    if (hour >= 20 || hour < 5) {
        drawCircle(Color.White.copy(alpha = 0.4f * dim), 1.6f * u, at + Offset(14 * u, 12 * u))
        drawCircle(Color.White.copy(alpha = 0.3f * dim), 1.2f * u, at + Offset(40 * u, 22 * u))
        drawCircle(Color(0xFFF3EAC2).copy(alpha = 0.4f * dim), 6 * u, at + Offset(44 * u, 12 * u))
    } else if (hour in 7..16) {
        drawCircle(Color(0xFFFFE08A).copy(alpha = 0.4f * dim), 6 * u, at + Offset(42 * u, 14 * u))
    }
    val frame = Palette.ink.copy(alpha = 0.16f * dim)
    drawRect(frame, at, Size(w, h), style = Stroke(3 * u))
    drawLine(frame, at + Offset(w / 2, 0f), at + Offset(w / 2, h), 2 * u)
    drawLine(frame, at + Offset(0f, h / 2), at + Offset(w, h / 2), 2 * u)
}

private fun DrawScope.drawStarPoster(at: Offset, u: Float, dim: Float) {
    drawRect(Color(0xFF1B1F4A).copy(alpha = 0.55f * dim), at, Size(40 * u, 30 * u))
    listOf(8f to 8f, 22f to 6f, 32f to 16f, 14f to 20f, 28f to 24f).forEach { (x, y) ->
        drawCircle(Color(0xFFFFE08A).copy(alpha = 0.6f * dim), 1.5f * u, at + Offset(x * u, y * u))
    }
}

private fun DrawScope.drawMoviePoster(at: Offset, u: Float, dim: Float) {
    drawRect(Palette.coral.copy(alpha = 0.3f * dim), at, Size(30 * u, 42 * u))
    drawCircle(Palette.amber.copy(alpha = 0.5f * dim), 7 * u, at + Offset(15 * u, 16 * u))
    for (i in 0..2) drawLine(Palette.ink.copy(alpha = 0.3f * dim), at + Offset(5 * u, (30 + i * 4) * u), at + Offset(25 * u, (30 + i * 4) * u), 1.5f * u)
}

private fun DrawScope.drawFairyLights(y: Float, toX: Float, u: Float, now: Long, dim: Float) {
    val path = Path().apply {
        moveTo(6 * u, y)
        quadraticTo(toX / 4, y + 16 * u, toX / 2, y + 2 * u)
        quadraticTo(toX * 3 / 4, y + 16 * u, toX, y)
    }
    drawPath(path, Palette.ink.copy(alpha = 0.1f * dim), style = Stroke(1.2f * u))
    val colors = listOf(Color(0xFFFFC266), Color(0xFFFF7A8A), Color(0xFF45D3C7), Color(0xFF8B6CFF))
    val phase = (now / 60_000L).toInt()
    for (i in 0 until 9) {
        val f = (i + 0.5f) / 9
        val x = 6 * u + (toX - 6 * u) * f
        val half = if (f < 0.5f) f * 2 else (f - 0.5f) * 2
        val sag = 4 * half * (1 - half) * 14 * u
        drawCircle(colors[(i + phase) % colors.size].copy(alpha = 0.55f * dim), 2.4f * u, Offset(x, y + sag + 2 * u))
    }
}

private fun DrawScope.drawShelf(at: Offset, u: Float, dim: Float, trophy: Boolean, globe: Boolean) {
    val w = 34 * u
    val h = 86 * u
    val wood = Color(0xFF7A5536).copy(alpha = 0.4f * dim)
    drawRect(wood, Offset(at.x, at.y - h), Size(w, h), style = Stroke(2.5f * u))
    for (i in 1..2) drawLine(wood, Offset(at.x, at.y - h * i / 3), Offset(at.x + w, at.y - h * i / 3), 2.5f * u)
    val books = listOf(Palette.coral, Palette.teal, Palette.amber, Palette.violet)
    for (i in 0 until 4) drawRect(books[i].copy(alpha = 0.35f * dim), Offset(at.x + 4 * u + i * 6.5f * u, at.y - 24 * u), Size(5 * u, 21 * u))
    if (trophy) {
        val t = Offset(at.x + w / 2, at.y - h / 3 - 3 * u)
        drawRect(Color(0xFFFFC266).copy(alpha = 0.55f * dim), Offset(t.x - 4 * u, t.y - 4 * u), Size(8 * u, 4 * u))
        drawArc(Color(0xFFFFC266).copy(alpha = 0.55f * dim), 0f, 180f, true, Offset(t.x - 7 * u, t.y - 18 * u), Size(14 * u, 14 * u))
    } else for (i in 0 until 3) drawRect(books[(i + 2) % 4].copy(alpha = 0.3f * dim), Offset(at.x + 6 * u + i * 8 * u, at.y - h / 3 - 19 * u), Size(6 * u, 17 * u))
    if (globe) {
        val g = Offset(at.x + w / 2, at.y - h - 9 * u)
        drawCircle(Color(0xFFBFE6F5).copy(alpha = 0.35f * dim), 8 * u, g)
        drawRect(Color(0xFF7A5536).copy(alpha = 0.5f * dim), Offset(g.x - 7 * u, g.y + 6 * u), Size(14 * u, 4 * u))
    }
}

private fun DrawScope.drawPlant(at: Offset, u: Float, dim: Float) {
    drawPath(Path().apply {
        moveTo(at.x - 9 * u, at.y - 18 * u); lineTo(at.x + 9 * u, at.y - 18 * u); lineTo(at.x + 6 * u, at.y); lineTo(at.x - 6 * u, at.y); close()
    }, Color(0xFFC0714A).copy(alpha = 0.5f * dim))
    val leaf = Color(0xFF5FBF7F).copy(alpha = 0.5f * dim)
    listOf(-35f, -10f, 15f, 40f).forEachIndexed { i, ang ->
        val rad = Math.toRadians((ang - 90).toDouble())
        val len = (26 + (i % 2) * 8) * u
        val tip = Offset(at.x + (kotlin.math.cos(rad) * len).toFloat(), at.y - 18 * u + (kotlin.math.sin(rad) * len).toFloat())
        drawLine(leaf, Offset(at.x, at.y - 18 * u), tip, 5 * u, StrokeCap.Round)
    }
}

private fun DrawScope.drawPumpkin(at: Offset, u: Float, dim: Float) {
    val c = Color(0xFFE88A3A).copy(alpha = 0.5f * dim)
    drawOval(c, Offset(at.x - 11 * u, at.y - 15 * u), Size(22 * u, 15 * u))
    drawOval(Color(0xFFC96F25).copy(alpha = 0.4f * dim), Offset(at.x - 5 * u, at.y - 15 * u), Size(10 * u, 15 * u))
    drawLine(Color(0xFF5FBF7F).copy(alpha = 0.5f * dim), at + Offset(0f, -15 * u), at + Offset(2 * u, -20 * u), 2.5f * u, StrokeCap.Round)
}

private fun DrawScope.drawLamp(at: Offset, u: Float, lit: Boolean, dim: Float) {
    val top = at.y - 100 * u
    if (lit) drawCircle(Brush.radialGradient(listOf(Color(0xFFFFC266).copy(alpha = 0.18f), Color.Transparent), Offset(at.x, top + 10 * u), 70 * u),
        70 * u, Offset(at.x, top + 10 * u))
    drawLine(Palette.muted.copy(alpha = 0.35f * dim), Offset(at.x, at.y), Offset(at.x, top + 14 * u), 2.5f * u)
    drawLine(Palette.muted.copy(alpha = 0.35f * dim), Offset(at.x - 10 * u, at.y), Offset(at.x + 10 * u, at.y), 3 * u, StrokeCap.Round)
    drawPath(Path().apply {
        moveTo(at.x - 8 * u, top); lineTo(at.x + 8 * u, top); lineTo(at.x + 15 * u, top + 16 * u); lineTo(at.x - 15 * u, top + 16 * u); close()
    }, (if (lit) Color(0xFFFFD58A) else Palette.muted).copy(alpha = (if (lit) 0.6f else 0.3f) * dim))
}

/**
 * The visiting friend: walks in from the left, sits beside the blob, chats in its own bubble,
 * runs about for tag or dozes, then walks off. [blob] is the main blob's centre.
 */
@Composable
internal fun FriendLayer(blob: Offset, size: Dp, actions: UiActions) {
    val visit by GadgetState.friend.collectAsState()
    val say by GadgetState.friendSay.collectAsState()
    val v = visit
    var t by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(v) {
        val start = System.currentTimeMillis()
        while (v != null) {
            kotlinx.coroutines.delay(50)
            t = (System.currentTimeMillis() - start) / 1000f
        }
    }
    val here = v != null && System.currentTimeMillis() < (v?.untilMs ?: 0)
    val enter by animateFloatAsState(if (here) 1f else 0f, tween(1600), label = "friend")
    if (enter <= 0.001f || blob == Offset.Unspecified) return
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sizePx = with(density) { size.toPx() }
    // Sits just right of and below the blob; walks in from off the left edge.
    val homeX = blob.x + sizePx * 1.05f
    val homeY = blob.y + sizePx * 0.25f
    val tag = v?.activity == "tag"
    val runX = if (tag && t > 2f) sin(t * 1.7f) * sizePx * 1.1f else 0f
    val runY = if (tag && t > 2f) -kotlin.math.abs(sin(t * 5f)) * sizePx * 0.25f else 0f
    val x = -sizePx + (homeX + sizePx) * enter + runX
    val y = homeY + runY
    val nap = v?.activity == "nap" && t > 3f
    Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((x - sizePx / 2).toInt(), (y - sizePx / 2).toInt()) }) {
        Blob(Modifier.size(size), actions, mini = true,
            gaze = Offset(if (tag) kotlin.math.cos(t * 1.7f) else -0.8f, 0f),
            moodOverride = if (nap) Mood.SLEEPY else Mood.HAPPY,
            tintOverride = Color(v?.color ?: 0xFFFF9EC7))
    }
    say?.let { text ->
        Column(Modifier.offset { androidx.compose.ui.unit.IntOffset((x - sizePx * 0.2f).toInt(), (y - sizePx * 1.05f).toInt()) }) {
            Text(text, color = Palette.night, fontSize = 14.sp, maxLines = 2,
                modifier = Modifier.widthIn(max = 220.dp).background(Color(v?.color ?: 0xFFFF9EC7).copy(alpha = 0.92f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp))
        }
    }
}

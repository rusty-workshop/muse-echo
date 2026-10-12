package com.rusty.museecho.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.drawscope.clipRect
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
    val weatherNow by GadgetState.weather.collectAsState()
    if (items.isEmpty() || blob == Offset.Unspecified) return
    val sky = remember(weatherNow) { WindowWeather.of(weatherNow?.summary) }
    val month0 = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH)
    val autumn = month0 in 8..10
    // Rain, snow, a storm or falling leaves move ~10 times a second; otherwise once a minute
    // is plenty for the sky and the lamp.
    val friendsData by GadgetState.friends.collectAsState()
    val pet = friendsData.pet
    val garden by GadgetState.garden.collectAsState()
    // A goldfish swims all the time; otherwise only weather and leaves need frames.
    val moving = ((sky != WindowWeather.NONE || autumn) && Decor.WINDOW.name in items || pet?.kind == "goldfish") && !quiet
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(moving) {
        while (true) {
            kotlinx.coroutines.delay(if (moving) 100 else 60_000)
            now = System.currentTimeMillis()
        }
    }
    val cal = remember(now / 60_000) { java.util.Calendar.getInstance().apply { timeInMillis = now } }
    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val month = cal.get(java.util.Calendar.MONTH)
    val t = (now % 3_600_000L) / 1000f
    // Things a friend moved slide over (and back).
    val shifts by GadgetState.roomShift.collectAsState()
    val moved = MOVABLE.associateWith { d ->
        androidx.compose.animation.core.animateFloatAsState(shifts[d.name] ?: 0f, androidx.compose.animation.core.tween(1400), label = d.name).value
    }
    val postcards by GadgetState.friends.collectAsState()
    Canvas(modifier) {
        val has = { d: Decor -> d.name in items }
        val u = radius / 110f // one "dp" of the blob's 220 dp box
        val dim = if (quiet) 0.45f else 1f
        val floor = blob.y + 92 * u
        val cx = blob.x
        val evening = hour >= 17 || hour < 7

        // The wall's foot and a soft pool of floor, fading out at the ends (no hard edges).
        val floorW = 170 * u
        drawOval(Brush.radialGradient(listOf(Palette.ink.copy(alpha = 0.04f * dim), Color.Transparent), Offset(cx, floor + 8 * u), floorW),
            Offset(cx - floorW, floor - 10 * u), Size(floorW * 2, 46 * u))
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, Palette.ink.copy(alpha = 0.07f * dim), Color.Transparent), cx - floorW, cx + floorW),
            Offset(cx - floorW, floor), Offset(cx + floorW, floor), 1.5f * u)

        if (has(Decor.WINDOW)) drawWindow(Offset(cx - 122 * u, blob.y - 112 * u), u * 0.85f, hour, dim, sky, autumn, t)
        if (has(Decor.STAR_POSTER)) drawStarPoster(Offset(cx + 62 * u, blob.y - 118 * u), u, dim)
        if (has(Decor.MOVIE_POSTER)) drawMoviePoster(Offset(cx + 74 * u, blob.y - 76 * u), u, dim)
        if (has(Decor.PHOTO_FRAME)) {
            drawRect(Palette.amber.copy(alpha = 0.35f * dim), Offset(cx - 40 * u, blob.y - 122 * u), Size(26 * u, 20 * u), style = Stroke(2.5f * u))
            drawRect(Palette.teal.copy(alpha = 0.2f * dim), Offset(cx - 37 * u, blob.y - 119 * u), Size(20 * u, 14 * u))
        }
        if (has(Decor.FAIRY_LIGHTS)) drawFairyLights(blob.y - 130 * u, cx + 130 * u, u, now, dim)
        if (has(Decor.RUG)) drawOval(Palette.violet.copy(alpha = 0.16f * dim), Offset(cx - 88 * u, floor - 6 * u), Size(176 * u, 26 * u))
        if (has(Decor.BOOKSHELF)) drawShelf(Offset(cx - 160 * u, floor), u, dim, trophy = has(Decor.TROPHY), globe = has(Decor.SNOW_GLOBE) && month in listOf(11, 0, 1))
        val sx = { d: Decor -> (moved[d] ?: 0f) * u }
        if (has(Decor.PLANT)) drawPlant(Offset(cx - 118 * u + sx(Decor.PLANT), floor), u, dim, flowers = month in 2..4)
        if (has(Decor.RARE_PLANT)) drawRarePlant(Offset(cx - 142 * u + sx(Decor.RARE_PLANT), floor), u, dim, t)
        if (has(Decor.LAVA_LAMP)) drawLavaLamp(Offset(cx + 78 * u + sx(Decor.LAVA_LAMP), floor), u, dim, now)
        if (has(Decor.FRIENDSHIP_PHOTO)) {
            val a = Offset(cx + 20 * u, blob.y - 124 * u)
            drawRect(Color.White.copy(alpha = 0.4f * dim), a, Size(26 * u, 22 * u))
            drawCircle(Palette.teal.copy(alpha = 0.6f * dim), 4.5f * u, a + Offset(9 * u, 11 * u))
            drawCircle(Color(0xFFFF9EC7).copy(alpha = 0.6f * dim), 4 * u, a + Offset(17 * u, 12 * u))
        }
        if (has(Decor.POSTCARDS)) {
            val n = postcards.postcards.size.coerceIn(1, 4)
            for (i in 0 until n) {
                val a = Offset(cx + 112 * u + i * 9 * u, blob.y - 150 * u + (i % 2) * 5 * u)
                drawRect(listOf(Color(0xFFFFE08A), Color(0xFFBFE6F5), Color(0xFFFFC7DD), Color(0xFFD7F5C4))[i].copy(alpha = 0.45f * dim), a, Size(16 * u, 11 * u))
            }
        }
        // The seasons: a little leaf pile in autumn, a tiny tree in December.
        if (month in 8..10) drawLeafPile(Offset(cx - 92 * u, floor), u, dim)
        if (month == java.util.Calendar.DECEMBER) drawTinyTree(Offset(cx + 90 * u, floor), u, dim, now)
        if (has(Decor.RECORD_PLAYER)) {
            val x = sx(Decor.RECORD_PLAYER)
            drawRoundRect(Palette.card.copy(alpha = 0.9f * dim), Offset(cx - 98 * u + x, floor - 14 * u), Size(30 * u, 14 * u), CornerRadius(2 * u))
            drawOval(Color(0xFF151218).copy(alpha = 0.9f * dim), Offset(cx - 95 * u + x, floor - 18 * u), Size(24 * u, 7 * u))
        }
        if (has(Decor.PUMPKIN) && month == java.util.Calendar.OCTOBER) drawPumpkin(Offset(cx + 66 * u, floor), u, dim)
        if (has(Decor.UMBRELLA_STAND)) {
            val a = Offset(cx + 96 * u + sx(Decor.UMBRELLA_STAND), floor)
            drawRect(Palette.muted.copy(alpha = 0.35f * dim), Offset(a.x - 6 * u, a.y - 18 * u), Size(12 * u, 18 * u))
            drawLine(Palette.teal.copy(alpha = 0.5f * dim), a + Offset(-2 * u, -18 * u), a + Offset(-4 * u, -42 * u), 3 * u, StrokeCap.Round)
            drawArc(Palette.teal.copy(alpha = 0.5f * dim), 180f, 120f, false, a + Offset(-8 * u, -48 * u), Size(10 * u, 10 * u), style = Stroke(2.5f * u))
        }
        if (has(Decor.LAMP)) drawLamp(Offset(cx + 120 * u + sx(Decor.LAMP), floor), u, lit = evening, dim)
        // From market day (and gifts).
        if (has(Decor.BUNTING)) for (i in 0 until 6) {
            val x = cx - 60 * u + i * 22 * u
            drawPath(Path().apply { moveTo(x, blob.y - 160 * u); lineTo(x + 16 * u, blob.y - 160 * u); lineTo(x + 8 * u, blob.y - 148 * u); close() },
                listOf(Color(0xFFFF7A8A), Color(0xFFFFC266), Color(0xFF45D3C7))[i % 3].copy(alpha = 0.5f * dim))
        }
        if (has(Decor.WALL_CLOCK)) {
            val c = Offset(cx + 30 * u, blob.y - 140 * u)
            drawCircle(Color(0xFFF2EEFA).copy(alpha = 0.45f * dim), 10 * u, c)
            val a = (now / 60_000L % 60) / 60f * 6.283f
            drawLine(Palette.night.copy(alpha = 0.7f * dim), c, c + Offset(kotlin.math.sin(a) * 7 * u, -kotlin.math.cos(a) * 7 * u), 1.5f * u)
            drawLine(Palette.night.copy(alpha = 0.7f * dim), c, c + Offset(0f, -5 * u), 2f * u)
        }
        if (has(Decor.WIND_CHIME)) {
            val top = Offset(cx - 70 * u, blob.y - 112 * u)
            for (i in 0 until 4) {
                val sway = sin(t * 1.5f + i) * 2 * u
                drawLine(Color(0xFFBFE6F5).copy(alpha = 0.55f * dim), top + Offset(i * 4 * u, 0f), top + Offset(i * 4 * u + sway, (14 + i * 3) * u), 1.5f * u)
            }
        }
        if (has(Decor.CUSHION)) drawOval(Color(0xFFC79BFF).copy(alpha = 0.45f * dim), Offset(cx - 145 * u, floor - 10 * u), Size(30 * u, 12 * u))
        if (has(Decor.TEAPOT) && has(Decor.BOOKSHELF)) {
            val a = Offset(cx - 143 * u, floor - 88 * u)
            drawOval(Color(0xFFFF9EC7).copy(alpha = 0.55f * dim), Offset(a.x - 8 * u, a.y - 8 * u), Size(16 * u, 10 * u))
            drawLine(Color(0xFFFF9EC7).copy(alpha = 0.55f * dim), a + Offset(7 * u, -4 * u), a + Offset(12 * u, -8 * u), 2 * u)
        }
        // The window-box garden, under the window.
        if (has(Decor.WINDOW)) drawGarden(Offset(cx - 124 * u, blob.y - 68 * u), u, garden, dim, t)
        // The pet, on the rug.
        pet?.let { drawPet(it, Offset(cx - 60 * u, floor + 2 * u), u, dim, now, t) }
    }
}

/** What the room's window shows of the real weather. */
internal enum class WindowWeather {
    NONE, RAIN, STORM, SNOW, FOG;

    companion object {
        fun of(summary: String?): WindowWeather {
            val s = summary.orEmpty().lowercase()
            return when {
                "thunder" in s -> STORM
                "snow" in s -> SNOW
                "rain" in s || "drizzle" in s || "shower" in s -> RAIN
                "fog" in s || "mist" in s -> FOG
                else -> NONE
            }
        }
    }
}

private fun DrawScope.drawWindow(at: Offset, u: Float, hour: Int, dim: Float, sky: WindowWeather, autumn: Boolean, t: Float) {
    val w = 58 * u
    val h = 52 * u
    drawWindowSky(at, u, hour, dim, sky)
    // Outside: the weather, and leaves drifting past in autumn, kept inside the glass.
    clipRect(at.x, at.y, at.x + w, at.y + h) {
        when (sky) {
            WindowWeather.RAIN, WindowWeather.STORM -> for (i in 0 until 9) {
                val x = at.x + ((i * 0.618f * 97f) % 1f) * w
                val y = at.y + ((t * (60f + i * 7f) * u / 10f + i * 23f * u) % (h + 10 * u)) - 5 * u
                drawLine(Color(0xFFBFE6F5).copy(alpha = 0.45f * dim), Offset(x, y), Offset(x - 1.5f * u, y + 6 * u), 1.2f * u)
            }
            WindowWeather.SNOW -> for (i in 0 until 10) {
                val x = at.x + ((i * 0.618f * 89f) % 1f) * w + sin(t * 0.9f + i) * 3 * u
                val y = at.y + ((t * (12f + i % 3 * 4f) * u + i * 19f * u) % (h + 6 * u)) - 3 * u
                drawCircle(Color.White.copy(alpha = 0.6f * dim), 1.4f * u, Offset(x, y))
            }
            WindowWeather.FOG -> drawRect(Color.White.copy(alpha = 0.16f * dim), at, Size(w, h))
            WindowWeather.NONE -> {}
        }
        if (autumn && sky != WindowWeather.SNOW) for (i in 0 until 3) {
            val fall = (t * (7f + i * 3f) * u + i * 31f * u) % (h + 12 * u) - 6 * u
            val x = at.x + w * (0.2f + 0.3f * i) + sin(t * 1.3f + i * 2f) * 6 * u
            drawOval(listOf(Color(0xFFE07A3A), Color(0xFFD9A441), Color(0xFFB5523A))[i].copy(alpha = 0.6f * dim),
                Offset(x, at.y + fall), Size(4 * u, 2.5f * u))
        }
    }
    // A storm now and then lights the whole window up.
    if (sky == WindowWeather.STORM && (t % 9f) < 0.15f) drawRect(Color.White.copy(alpha = 0.5f * dim), at, Size(w, h))
    // Rain on the glass: a few drops that sit and slowly slide.
    if (sky == WindowWeather.RAIN || sky == WindowWeather.STORM) for (i in 0 until 4) {
        val x = at.x + w * (0.15f + 0.22f * i)
        val y = at.y + ((t * 2f * u + i * 13f * u) % h)
        drawCircle(Color(0xFFBFE6F5).copy(alpha = 0.35f * dim), 1.6f * u, Offset(x, y))
    }
    val frame = Palette.ink.copy(alpha = 0.16f * dim)
    drawRect(frame, at, Size(w, h), style = Stroke(3 * u))
    drawLine(frame, at + Offset(w / 2, 0f), at + Offset(w / 2, h), 2 * u)
    drawLine(frame, at + Offset(0f, h / 2), at + Offset(w, h / 2), 2 * u)
}

private fun DrawScope.drawWindowSky(at: Offset, u: Float, hour: Int, dim: Float, sky: WindowWeather) {
    val w = 58 * u
    val h = 52 * u
    val colors = when (hour) {
        in 7..16 -> if (sky == WindowWeather.NONE) listOf(Color(0xFF6FA8DC), Color(0xFFB4D7F0)) else listOf(Color(0xFF7D8796), Color(0xFFA9B1BD))
        in 17..19 -> listOf(Color(0xFFE07A5F), Color(0xFFF2CC8F))
        5, 6 -> listOf(Color(0xFF9A8FC8), Color(0xFFF2B5A0))
        else -> listOf(Color(0xFF0B1030), Color(0xFF1B2050))
    }
    drawRect(Brush.verticalGradient(colors.map { it.copy(alpha = 0.32f * dim) }, at.y, at.y + h), at, Size(w, h))
    if ((hour >= 20 || hour < 5) && sky == WindowWeather.NONE) {
        drawCircle(Color.White.copy(alpha = 0.4f * dim), 1.6f * u, at + Offset(14 * u, 12 * u))
        drawCircle(Color.White.copy(alpha = 0.3f * dim), 1.2f * u, at + Offset(40 * u, 22 * u))
        drawCircle(Color(0xFFF3EAC2).copy(alpha = 0.4f * dim), 6 * u, at + Offset(44 * u, 12 * u))
    } else if (hour in 7..16) {
        // The sun only when the weather lets it through.
        if (sky == WindowWeather.NONE) drawCircle(Color(0xFFFFE08A).copy(alpha = 0.4f * dim), 6 * u, at + Offset(42 * u, 14 * u))
        else drawRect(Color(0xFF8A94A6).copy(alpha = 0.25f * dim), at, Size(w, h))
    }
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

/** Room items a friend may move about. */
internal val MOVABLE = listOf(Decor.PLANT, Decor.LAMP, Decor.RECORD_PLAYER, Decor.UMBRELLA_STAND, Decor.RARE_PLANT, Decor.LAVA_LAMP)

/**
 * Where a room item stands (its x, and the height of its top above the floor), in room units
 * from the blob's centre: for hide and seek.
 */
internal fun decorSpot(d: Decor): Pair<Float, Float> = when (d) {
    Decor.PLANT -> -118f to 45f
    Decor.RARE_PLANT -> -142f to 50f
    Decor.LAMP -> 120f to 100f
    Decor.BOOKSHELF -> -143f to 86f
    Decor.LAVA_LAMP -> 78f to 36f
    else -> 60f to 10f
}

private fun DrawScope.drawRarePlant(at: Offset, u: Float, dim: Float, t: Float) {
    drawRect(Color(0xFF6E5A8A).copy(alpha = 0.5f * dim), Offset(at.x - 7 * u, at.y - 14 * u), Size(14 * u, 14 * u))
    val glow = 0.45f + 0.2f * sin(t * 1.2f)
    for (i in 0 until 3) {
        val tip = Offset(at.x + (i - 1) * 9 * u, at.y - (34 + (i % 2) * 10) * u)
        drawLine(Color(0xFF45D3C7).copy(alpha = 0.5f * dim), Offset(at.x, at.y - 14 * u), tip, 3 * u, StrokeCap.Round)
        drawCircle(Color(0xFF9FFFF0).copy(alpha = glow * dim), 3.5f * u, tip)
    }
}

private fun DrawScope.drawLavaLamp(at: Offset, u: Float, dim: Float, now: Long) {
    drawPath(Path().apply {
        moveTo(at.x - 5 * u, at.y - 36 * u); lineTo(at.x + 5 * u, at.y - 36 * u); lineTo(at.x + 8 * u, at.y - 6 * u); lineTo(at.x - 8 * u, at.y - 6 * u); close()
    }, Color(0xFFFF7A8A).copy(alpha = 0.3f * dim))
    val p = (now % 20_000L) / 20_000f
    drawCircle(Color(0xFFFFC266).copy(alpha = 0.6f * dim), 3 * u, Offset(at.x, at.y - 10 * u - 22 * u * kotlin.math.abs(sin(p * 3.14159f))))
    drawCircle(Color(0xFFFFC266).copy(alpha = 0.5f * dim), 2.2f * u, Offset(at.x + 1.5f * u, at.y - 30 * u + 16 * u * kotlin.math.abs(sin(p * 3.14159f + 1f))))
    drawRect(Palette.muted.copy(alpha = 0.4f * dim), Offset(at.x - 8 * u, at.y - 6 * u), Size(16 * u, 6 * u))
    drawRect(Palette.muted.copy(alpha = 0.4f * dim), Offset(at.x - 4 * u, at.y - 40 * u), Size(8 * u, 4 * u))
}

private fun DrawScope.drawLeafPile(at: Offset, u: Float, dim: Float) {
    val c = listOf(Color(0xFFE07A3A), Color(0xFFD9A441), Color(0xFFB5523A))
    for (i in 0 until 6) drawOval(c[i % 3].copy(alpha = 0.45f * dim), Offset(at.x - 10 * u + i * 4 * u, at.y - 4 * u - (i % 2) * 2 * u), Size(6 * u, 3.5f * u))
}

private fun DrawScope.drawTinyTree(at: Offset, u: Float, dim: Float, now: Long) {
    val green = Color(0xFF3E8E5E).copy(alpha = 0.6f * dim)
    for (i in 0 until 3) {
        val w = (22 - i * 6) * u
        val y = at.y - 8 * u - i * 10 * u
        drawPath(Path().apply { moveTo(at.x - w / 2, y); lineTo(at.x + w / 2, y); lineTo(at.x, y - 14 * u); close() }, green)
    }
    drawRect(Color(0xFF7A5536).copy(alpha = 0.6f * dim), Offset(at.x - 2 * u, at.y - 8 * u), Size(4 * u, 8 * u))
    val blink = (now / 800L) % 2 == 0L
    listOf(-6f to -12f, 5f to -16f, -3f to -24f, 4f to -30f).forEachIndexed { i, (x, y) ->
        drawCircle((if ((i % 2 == 0) == blink) Color(0xFFFFC266) else Color(0xFFFF7A8A)).copy(alpha = 0.8f * dim), 1.5f * u, at + Offset(x * u, y * u))
    }
    drawCircle(Color(0xFFFFE08A).copy(alpha = 0.8f * dim), 2.2f * u, at + Offset(0f, -46 * u))
}

private fun DrawScope.drawPlant(at: Offset, u: Float, dim: Float, flowers: Boolean = false) {
    drawPath(Path().apply {
        moveTo(at.x - 9 * u, at.y - 18 * u); lineTo(at.x + 9 * u, at.y - 18 * u); lineTo(at.x + 6 * u, at.y); lineTo(at.x - 6 * u, at.y); close()
    }, Color(0xFFC0714A).copy(alpha = 0.5f * dim))
    val leaf = Color(0xFF5FBF7F).copy(alpha = 0.5f * dim)
    listOf(-35f, -10f, 15f, 40f).forEachIndexed { i, ang ->
        val rad = Math.toRadians((ang - 90).toDouble())
        val len = (26 + (i % 2) * 8) * u
        val tip = Offset(at.x + (kotlin.math.cos(rad) * len).toFloat(), at.y - 18 * u + (kotlin.math.sin(rad) * len).toFloat())
        drawLine(leaf, Offset(at.x, at.y - 18 * u), tip, 5 * u, StrokeCap.Round)
        // Spring: little flowers on the tips.
        if (flowers && i % 2 == 0) drawCircle(Color(0xFFFF9EC7).copy(alpha = 0.7f * dim), 3.2f * u, tip)
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


/** Where the pet sits, in room units from the blob's centre (x) and the floor (y), for tapping it. */
internal val PET_SPOT = Offset(-60f, 2f)

private fun DrawScope.drawGarden(at: Offset, u: Float, stage: Int, dim: Float, t: Float) {
    val w = 54 * u
    drawRoundRect(Color(0xFF8A5A3C).copy(alpha = 0.6f * dim), at, Size(w, 7 * u), CornerRadius(2 * u))
    for (i in 0 until 5) {
        val x = at.x + 6 * u + i * 10.5f * u
        val sway = sin(t * 0.8f + i) * 0.8f * u
        when (stage) {
            0 -> drawCircle(Color(0xFF5A3A28).copy(alpha = 0.6f * dim), 1.3f * u, Offset(x, at.y + 1.5f * u))
            1 -> drawLine(Color(0xFF7FE0A8).copy(alpha = 0.7f * dim), Offset(x, at.y), Offset(x + sway, at.y - 4 * u), 1.4f * u)
            else -> {
                val tip = Offset(x + sway, at.y - 10 * u)
                drawLine(Color(0xFF5FBF7F).copy(alpha = 0.7f * dim), Offset(x, at.y), tip, 1.6f * u)
                drawOval(Color(0xFF5FBF7F).copy(alpha = 0.6f * dim), Offset(x - 3 * u, at.y - 6 * u), Size(3 * u, 2 * u))
                if (stage == 2) drawCircle(Color(0xFF9FD97F).copy(alpha = 0.7f * dim), 1.4f * u, tip)
                else drawCircle(listOf(Color(0xFFFF7A9A), Color(0xFFFFC266), Color(0xFFC79BFF))[i % 3].copy(alpha = 0.8f * dim), 2.6f * u, tip)
            }
        }
    }
}

private fun DrawScope.drawPet(pet: com.rusty.museecho.gadget.Pet, at: Offset, u: Float, dim: Float, now: Long, t: Float) {
    val g = pet.growth(now)
    val poked = (now - GadgetState.petPokedAt).coerceAtLeast(0) < 2_500
    val wiggle = if (poked) sin(t * 18f) * 2 * u else 0f
    when (pet.kind) {
        "snail" -> {
            // Creeps back and forth along the rug over the hours.
            val x = at.x + sin(now / 900_000f) * 18 * u + wiggle
            val y = at.y - 3 * u
            drawOval(Color(0xFFC9B79C).copy(alpha = 0.8f * dim), Offset(x - 9 * u * g, y - 2 * u * g), Size(16 * u * g, 4 * u * g))
            drawCircle(Color(0xFFC0714A).copy(alpha = 0.8f * dim), 5.5f * u * g, Offset(x, y - 6 * u * g))
            drawCircle(Color(0xFF8A5A3C).copy(alpha = 0.8f * dim), 2.5f * u * g, Offset(x + 0.5f * u, y - 6 * u * g), style = androidx.compose.ui.graphics.drawscope.Stroke(1f * u))
            val peek = if (poked) 3 * u else 0f
            drawLine(Color(0xFFC9B79C).copy(alpha = 0.8f * dim), Offset(x + 6 * u * g, y - 2 * u), Offset(x + 8 * u * g, y - 7 * u * g - peek), 1f * u)
        }
        "sprout" -> {
            drawRoundRect(Color(0xFFC0714A).copy(alpha = 0.7f * dim), Offset(at.x - 6 * u, at.y - 9 * u), Size(12 * u, 9 * u), CornerRadius(2 * u))
            val top = Offset(at.x + wiggle, at.y - 9 * u - 10 * u * g)
            drawLine(Color(0xFF5FBF7F).copy(alpha = 0.8f * dim), Offset(at.x, at.y - 9 * u), top, 1.6f * u)
            for (s in listOf(-1f, 1f)) drawOval(Color(0xFF7FE0A8).copy(alpha = 0.8f * dim),
                Offset(top.x + (if (s < 0) -8 * u * g else 0f), top.y - 2 * u), Size(8 * u * g, 4 * u * g))
            // A tiny face.
            drawCircle(Palette.night.copy(alpha = 0.8f * dim), 0.8f * u, Offset(at.x - 2 * u, at.y - 5 * u))
            drawCircle(Palette.night.copy(alpha = 0.8f * dim), 0.8f * u, Offset(at.x + 2 * u, at.y - 5 * u))
        }
        else -> {
            // A goldfish in a little round bowl.
            val c = Offset(at.x, at.y - 10 * u)
            drawCircle(Color(0xFFBFE6F5).copy(alpha = 0.25f * dim), 10 * u, c)
            drawCircle(Color(0xFFBFE6F5).copy(alpha = 0.4f * dim), 10 * u, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1f * u))
            val swim = sin(t * (if (poked) 4f else 0.9f)) * 4 * u
            val dir = if (kotlin.math.cos(t * (if (poked) 4f else 0.9f)) > 0) 1f else -1f
            val f = c + Offset(swim, sin(t * 1.7f) * 1.5f * u)
            drawOval(Color(0xFFFF9A3C).copy(alpha = 0.9f * dim), Offset(f.x - 3.5f * u * g, f.y - 2 * u * g), Size(7 * u * g, 4 * u * g))
            drawPath(Path().apply {
                moveTo(f.x - dir * 3 * u * g, f.y); lineTo(f.x - dir * 6 * u * g, f.y - 2.5f * u * g); lineTo(f.x - dir * 6 * u * g, f.y + 2.5f * u * g); close()
            }, Color(0xFFFF9A3C).copy(alpha = 0.9f * dim))
        }
    }
}

/** An invisible tap target over the pet (drawn on top of the page so it gets the tap). */
@Composable
internal fun PetTapTarget(blob: Offset, half: Float) {
    if (blob == Offset.Unspecified || half <= 0f) return
    val data by GadgetState.friends.collectAsState()
    if (data.pet == null) return
    val u = half / 110f
    val at = Offset(blob.x + PET_SPOT.x * u, blob.y + 92 * u + PET_SPOT.y * u)
    val size = 26 * u
    Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((at.x - size / 2).toInt(), (at.y - size).toInt()) }
        .size(with(androidx.compose.ui.platform.LocalDensity.current) { size.toDp() })
        .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {
            com.rusty.museecho.gadget.GadgetService.instance?.life?.friends?.petTapped()
        })
}


/** During a treasure hunt: tap targets over the things in Puff's room it could be hidden behind. */
@Composable
internal fun TreasureTapLayer(blob: Offset, half: Float) {
    val data by GadgetState.friends.collectAsState()
    val items by GadgetState.room.collectAsState()
    val t = data.treasure?.takeIf { !it.found && it.date == java.time.LocalDate.now().toString() } ?: return
    if (blob == Offset.Unspecified || half <= 0f) return
    val u = half / 110f
    val density = androidx.compose.ui.platform.LocalDensity.current
    com.rusty.museecho.gadget.FriendsBrain.HIDING_SPOTS.filterKeys { it.name in items }.forEach { (decor, spot) ->
        val (x, up) = spot
        val at = Offset(blob.x + x * u, blob.y + 92 * u - up * u)
        val sz = 34 * u
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((at.x - sz / 2).toInt(), (at.y - sz / 2).toInt()) }
            .size(with(density) { sz.toDp() })
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {
                com.rusty.museecho.gadget.GadgetService.instance?.life?.friends?.guess(decor.name)
            })
    }
}

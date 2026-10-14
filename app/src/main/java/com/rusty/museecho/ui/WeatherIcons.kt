package com.rusty.museecho.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What a weather icon shows. */
internal enum class Sky { SUN, MOON, PARTLY, PARTLY_NIGHT, CLOUD, FOG, DRIZZLE, RAIN, SNOW, STORM }

/** From an Open-Meteo (WMO) weather code. */
internal fun skyOf(code: Int, night: Boolean = false): Sky = when (code) {
    0 -> if (night) Sky.MOON else Sky.SUN
    1, 2 -> if (night) Sky.PARTLY_NIGHT else Sky.PARTLY
    3 -> Sky.CLOUD
    45, 48 -> Sky.FOG
    51, 53, 55, 56, 57 -> Sky.DRIZZLE
    61, 63, 65, 66, 67, 80, 81, 82 -> Sky.RAIN
    71, 73, 75, 77, 85, 86 -> Sky.SNOW
    95, 96, 99 -> Sky.STORM
    else -> Sky.CLOUD
}

/** From the words in the current weather ("Partly cloudy", "Light rain"...). */
internal fun skyOf(summary: String, night: Boolean = false): Sky {
    val s = summary.lowercase()
    return when {
        "thunder" in s -> Sky.STORM
        "snow" in s -> Sky.SNOW
        "drizzle" in s -> Sky.DRIZZLE
        "rain" in s || "shower" in s -> Sky.RAIN
        "fog" in s || "mist" in s -> Sky.FOG
        "overcast" in s -> Sky.CLOUD
        "partly" in s || "mostly" in s || "cloud" in s -> if (night) Sky.PARTLY_NIGHT else Sky.PARTLY
        else -> if (night) Sky.MOON else Sky.SUN
    }
}

private val SunCol = Color(0xFFFFC94D)
private val MoonCol = Color(0xFFE8E2C8)
private val CloudCol = Color(0xFFDDE3EC)
private val DarkCloud = Color(0xFF9AA4B2)
private val RainCol = Color(0xFF6FB8F0)

/**
 * A little animated weather drawing in a [s]-sized box centred at [c]: sun rays turning slowly,
 * clouds drifting, rain and snow falling, lightning now and then. [t] is seconds.
 */
internal fun DrawScope.drawSky(sky: Sky, c: Offset, s: Float, t: Float) {
    val r = s / 2
    fun sun(at: Offset, rad: Float) {
        val spin = t * 0.35f
        for (k in 0 until 8) {
            val a = spin + k * (PI.toFloat() / 4)
            val pulse = 1f + 0.08f * sin(t * 2f + k)
            drawLine(SunCol, at + Offset(cos(a), sin(a)) * rad * 1.3f, at + Offset(cos(a), sin(a)) * rad * 1.65f * pulse, rad * 0.16f, StrokeCap.Round)
        }
        drawCircle(SunCol, rad, at)
    }
    fun moon(at: Offset, rad: Float, bg: Color = Palette.night) {
        drawCircle(MoonCol, rad, at)
        drawCircle(bg, rad * 0.85f, at + Offset(rad * 0.45f, -rad * 0.3f))
        // A twinkle beside it.
        val tw = 0.4f + 0.6f * kotlin.math.abs(sin(t * 1.7f))
        drawCircle(Color.White.copy(alpha = tw), rad * 0.1f, at + Offset(rad * 1.2f, rad * 0.8f))
    }
    fun cloud(at: Offset, w: Float, col: Color) {
        val drift = sin(t * 0.6f) * w * 0.06f
        val o = at + Offset(drift, 0f)
        drawCircle(col, w * 0.28f, o + Offset(-w * 0.22f, w * 0.05f))
        drawCircle(col, w * 0.36f, o + Offset(w * 0.05f, -w * 0.08f))
        drawCircle(col, w * 0.25f, o + Offset(w * 0.32f, w * 0.08f))
        drawRoundRect(col, o + Offset(-w * 0.45f, w * 0.05f), Size(w * 0.95f, w * 0.28f), androidx.compose.ui.geometry.CornerRadius(w * 0.14f))
    }
    fun drops(from: Offset, w: Float, n: Int, speed: Float, snow: Boolean) {
        for (k in 0 until n) {
            val ph = ((t * speed + k * 0.37f) % 1f)
            val x = from.x - w * 0.3f + w * 0.6f * k / (n - 1).coerceAtLeast(1) + (if (snow) sin(t * 2f + k) * w * 0.05f else 0f)
            val y = from.y + ph * r * 0.75f
            val a = 1f - ph
            if (snow) drawCircle(Color.White.copy(alpha = a), r * 0.08f, Offset(x, y))
            else drawLine(RainCol.copy(alpha = a), Offset(x, y), Offset(x - r * 0.06f, y + r * 0.2f), r * 0.07f, StrokeCap.Round)
        }
    }
    when (sky) {
        Sky.SUN -> sun(c, r * 0.42f)
        Sky.MOON -> moon(c, r * 0.5f)
        Sky.PARTLY -> { sun(c + Offset(-r * 0.25f, -r * 0.25f), r * 0.3f); cloud(c + Offset(r * 0.1f, r * 0.2f), s * 0.7f, CloudCol) }
        Sky.PARTLY_NIGHT -> { moon(c + Offset(-r * 0.25f, -r * 0.3f), r * 0.32f); cloud(c + Offset(r * 0.1f, r * 0.2f), s * 0.7f, CloudCol) }
        Sky.CLOUD -> { cloud(c + Offset(-r * 0.2f, -r * 0.15f), s * 0.55f, DarkCloud); cloud(c + Offset(r * 0.12f, r * 0.12f), s * 0.75f, CloudCol) }
        Sky.FOG -> for (k in 0 until 3) {
            val y = c.y - r * 0.35f + k * r * 0.35f
            val shift = sin(t * 0.8f + k) * r * 0.15f
            drawLine(CloudCol.copy(alpha = 0.8f - k * 0.15f), Offset(c.x - r * 0.75f + shift, y), Offset(c.x + r * 0.75f + shift, y), r * 0.14f, StrokeCap.Round)
        }
        Sky.DRIZZLE -> { cloud(c + Offset(0f, -r * 0.25f), s * 0.75f, CloudCol); drops(c + Offset(0f, r * 0.1f), s * 0.6f, 3, 0.9f, false) }
        Sky.RAIN -> { cloud(c + Offset(0f, -r * 0.25f), s * 0.75f, DarkCloud); drops(c + Offset(0f, r * 0.1f), s * 0.6f, 4, 1.4f, false) }
        Sky.SNOW -> { cloud(c + Offset(0f, -r * 0.25f), s * 0.75f, CloudCol); drops(c + Offset(0f, r * 0.1f), s * 0.6f, 4, 0.5f, true) }
        Sky.STORM -> {
            cloud(c + Offset(0f, -r * 0.25f), s * 0.75f, DarkCloud)
            // A flash every few seconds.
            if ((t % 3.2f) < 0.35f) {
                val bolt = Path().apply {
                    moveTo(c.x + r * 0.05f, c.y); lineTo(c.x - r * 0.15f, c.y + r * 0.4f); lineTo(c.x + r * 0.05f, c.y + r * 0.4f)
                    lineTo(c.x - r * 0.1f, c.y + r * 0.8f)
                }
                drawPath(bolt, SunCol, style = Stroke(r * 0.1f, cap = StrokeCap.Round))
            } else drops(c + Offset(0f, r * 0.1f), s * 0.6f, 3, 1.4f, false)
        }
    }
}

/**
 * A seconds clock on the shared animation beat (8 fps is plenty for small icons). Read it inside a
 * draw lambda so only the drawing repeats, not the composition.
 */
@Composable
internal fun rememberIconClock(): androidx.compose.runtime.State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            awaitBeat(120)
            t.floatValue = (System.currentTimeMillis() - start) / 1000f
        }
    }
    return t
}

/** One animated weather icon. */
@Composable
internal fun WeatherIcon(sky: Sky, size: Dp, modifier: Modifier = Modifier) {
    val t = rememberIconClock()
    Canvas(modifier.size(size)) { drawSky(sky, Offset(this.size.width / 2, this.size.height / 2), this.size.minDimension, t.value) }
}

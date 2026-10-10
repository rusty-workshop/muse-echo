package com.rusty.museecho.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import java.util.Date
import kotlin.math.cos
import kotlin.math.sin

/** The idle screen's clock styles (Setup > Clock face). */
enum class ClockFace(val label: String, val blurb: String) {
    CLASSIC("Classic", "Big, light numerals"),
    ANALOG("Analog", "A dial with a ticking second hand"),
    WORDS("Words", "\"It's almost quarter past six\""),
    STACKED("Stacked", "Hours over minutes, in two colours"),
    FLIP("Flip", "Split-flap cards that roll over"),
    RINGS("Rings", "Hour, minute and second rings"),
    TERMINAL("Terminal", "A prompt with a blinking cursor"),
    ;

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: CLASSIC
    }
}

/**
 * Draws [face] for [now]. [scale] shrinks everything together (1 = the home screen,
 * small values for the setup previews); [compact] is the home screen with a pinned
 * note beside it.
 */
@Composable
fun ClockFaceView(face: ClockFace, now: Date, modifier: Modifier = Modifier, scale: Float = 1f, compact: Boolean = false) {
    val s = if (compact) scale * 0.86f else scale
    when (face) {
        ClockFace.CLASSIC -> ClassicFace(now, modifier, s)
        ClockFace.ANALOG -> AnalogFace(now, modifier, s)
        ClockFace.WORDS -> WordsFace(now, modifier, s)
        ClockFace.STACKED -> StackedFace(now, modifier, s)
        ClockFace.FLIP -> FlipFace(now, modifier, s)
        ClockFace.RINGS -> RingsFace(now, modifier, s)
        ClockFace.TERMINAL -> TerminalFace(now, modifier, s)
    }
}

private fun Date.cal(): Calendar = Calendar.getInstance().apply { time = this@cal }
private fun Date.hour12(): Int = cal().get(Calendar.HOUR).let { if (it == 0) 12 else it }
private fun Date.minute(): Int = cal().get(Calendar.MINUTE)
private fun Date.second(): Int = cal().get(Calendar.SECOND)
private fun Date.pm(): Boolean = cal().get(Calendar.AM_PM) == Calendar.PM

// -- Classic -----------------------------------------------------------------------------

@Composable
private fun ClassicFace(now: Date, modifier: Modifier, s: Float) {
    Text("${now.hour12()}:%02d".format(now.minute()), modifier, color = Palette.ink, fontSize = (84 * s).sp, fontWeight = FontWeight.Light)
}

// -- Analog ------------------------------------------------------------------------------

@Composable
private fun AnalogFace(now: Date, modifier: Modifier, s: Float) {
    // Angles only ever increase (seconds since the epoch), so the hands never spin backwards at :00.
    val totalSec = now.time / 1000
    val secAngle by animateFloatAsState((totalSec % 86_400 * 6).toFloat(),
        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow), label = "sec")
    val minAngle = ((totalSec % 3_600) / 10f)
    val hourAngle = ((now.cal().get(Calendar.HOUR) * 3600 + now.minute() * 60 + now.second()) / 120f)
    Canvas(modifier.size((150 * s).dp)) {
        val r = size.minDimension / 2
        val c = center
        drawCircle(Palette.card.copy(alpha = 0.55f), r, c)
        drawCircle(Palette.violet.copy(alpha = 0.25f), r, c, style = Stroke(width = r * 0.03f))
        for (i in 0 until 60) {
            val major = i % 5 == 0
            val quarter = i % 15 == 0
            val a = Math.toRadians(i * 6.0 - 90).toFloat()
            val outer = r * 0.92f
            val inner = r * if (quarter) 0.74f else if (major) 0.8f else 0.87f
            drawLine(if (major) Palette.ink.copy(alpha = if (quarter) 0.9f else 0.6f) else Palette.muted.copy(alpha = 0.35f),
                c + Offset(cos(a) * inner, sin(a) * inner), c + Offset(cos(a) * outer, sin(a) * outer),
                r * if (quarter) 0.04f else if (major) 0.025f else 0.012f, StrokeCap.Round)
        }
        fun hand(angle: Float, length: Float, width: Float, color: Color, tail: Float = 0.12f) = rotate(angle, c) {
            drawLine(color, c + Offset(0f, r * tail), c + Offset(0f, -r * length), width, StrokeCap.Round)
        }
        hand(hourAngle, 0.5f, r * 0.075f, Palette.ink)
        hand(minAngle, 0.76f, r * 0.05f, Palette.ink.copy(alpha = 0.92f))
        hand(secAngle, 0.84f, r * 0.018f, Palette.coral, tail = 0.2f)
        drawCircle(Palette.coral, r * 0.05f, c)
        drawCircle(Palette.night, r * 0.02f, c)
    }
}

// -- Words -------------------------------------------------------------------------------

private val HOUR_WORDS = listOf("twelve", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven")

/** "It's almost quarter past six": the time to the nearest five minutes, with a hint of which side. */
internal fun timeInWords(hour24: Int, minute: Int): Pair<String, List<String>> {
    val rounded = ((minute + 2) / 5) * 5
    val nudge = when (minute - rounded) {
        -2, -1 -> "almost "
        1, 2 -> "just after "
        else -> ""
    }
    var hour = hour24 % 12
    val phrase = when (rounded) {
        0, 60 -> "o'clock"
        5 -> "five past"; 10 -> "ten past"; 15 -> "quarter past"; 20 -> "twenty past"; 25 -> "twenty-five past"
        30 -> "half past"
        35 -> "twenty-five to"; 40 -> "twenty to"; 45 -> "quarter to"; 50 -> "ten to"; 55 -> "five to"
        else -> ""
    }
    if (rounded >= 35) hour = (hour + 1) % 12
    val name = HOUR_WORDS[hour]
    val text = if (rounded == 0 || rounded == 60) "It's $nudge$name o'clock" else "It's $nudge$phrase $name"
    return text to (phrase.split(' ').filter { it != "o'clock" } + name)
}

@Composable
private fun WordsFace(now: Date, modifier: Modifier, s: Float) {
    val (text, keys) = timeInWords(now.cal().get(Calendar.HOUR_OF_DAY), now.minute())
    val styled = buildAnnotatedString {
        text.split(' ').forEachIndexed { i, word ->
            if (i > 0) append(' ')
            if (word in keys) withStyle(SpanStyle(color = Palette.ink, fontWeight = FontWeight.Normal)) { append(word) }
            else withStyle(SpanStyle(color = Palette.muted.copy(alpha = 0.75f))) { append(word) }
        }
    }
    Column(modifier) {
        Text(styled, fontSize = (40 * s).sp, lineHeight = (46 * s).sp, fontWeight = FontWeight.Light)
        Text("${now.hour12()}:%02d".format(now.minute()), color = Palette.amber.copy(alpha = 0.8f), fontSize = (16 * s).sp,
            modifier = Modifier.padding(top = (4 * s).dp))
    }
}

// -- Stacked -----------------------------------------------------------------------------

@Composable
private fun StackedFace(now: Date, modifier: Modifier, s: Float) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("%02d".format(now.hour12()), color = Palette.ink, fontSize = (70 * s).sp, lineHeight = (64 * s).sp, fontWeight = FontWeight.Thin)
            Text("%02d".format(now.minute()), color = Palette.violet.copy(alpha = 0.95f).let { androidx.compose.ui.graphics.lerp(it, Palette.ink, 0.25f) },
                fontSize = (70 * s).sp, lineHeight = (64 * s).sp, fontWeight = FontWeight.Light)
        }
        Column(Modifier.padding(start = (10 * s).dp), verticalArrangement = Arrangement.spacedBy((4 * s).dp)) {
            Text(if (now.pm()) "PM" else "AM", color = Palette.amber, fontSize = (14 * s).sp, fontWeight = FontWeight.SemiBold)
            // A thin bar filling up through the minute.
            Box(Modifier.width((3 * s).dp).height((60 * s).dp).background(Palette.card, RoundedCornerShape(50))) {
                Box(Modifier.width((3 * s).dp).height((60 * s * now.second() / 59f).dp).background(Palette.teal, RoundedCornerShape(50)))
            }
        }
    }
}

// -- Flip --------------------------------------------------------------------------------

@Composable
private fun FlipCard(digit: Char, s: Float) {
    Box(
        Modifier.width((48 * s).dp).height((72 * s).dp)
            .background(androidx.compose.ui.graphics.lerp(Palette.card, Palette.ink, 0.08f), RoundedCornerShape((8 * s).dp)),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = digit,
            transitionSpec = {
                (slideInVertically(tween(380)) { -it / 2 } + fadeIn(tween(380))) togetherWith
                    (slideOutVertically(tween(380)) { it / 2 } + fadeOut(tween(300)))
            },
            label = "flip",
        ) { d ->
            Text(d.toString(), color = Palette.ink, fontSize = (54 * s).sp, fontWeight = FontWeight.Medium)
        }
        // The split across the middle of each card.
        Box(Modifier.fillMaxWidth().height((2 * s).dp).background(Palette.night.copy(alpha = 0.8f)))
    }
}

@Composable
private fun FlipFace(now: Date, modifier: Modifier, s: Float) {
    val hh = "%2d".format(now.hour12())
    val mm = "%02d".format(now.minute())
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((5 * s).dp)) {
        if (hh[0] != ' ') FlipCard(hh[0], s)
        FlipCard(hh[1], s)
        // The colon blinks with the seconds.
        Text(":", color = Palette.muted.copy(alpha = if (now.second() % 2 == 0) 0.9f else 0.25f), fontSize = (46 * s).sp)
        FlipCard(mm[0], s)
        FlipCard(mm[1], s)
    }
}

// -- Rings -------------------------------------------------------------------------------

@Composable
private fun RingsFace(now: Date, modifier: Modifier, s: Float) {
    val secFrac by animateFloatAsState(now.second() / 60f, tween(if (now.second() == 0) 0 else 900), label = "secRing")
    val minFrac = (now.minute() + now.second() / 60f) / 60f
    val hourFrac = ((now.cal().get(Calendar.HOUR) + now.minute() / 60f) / 12f)
    Box(modifier.size((150 * s).dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size((150 * s).dp)) {
            val stroke = size.minDimension * 0.06f
            fun ring(inset: Float, frac: Float, color: Color) {
                val tl = Offset(inset, inset)
                val sz = Size(size.width - inset * 2, size.height - inset * 2)
                drawArc(color.copy(alpha = 0.16f), 0f, 360f, false, tl, sz, style = Stroke(stroke))
                drawArc(color, -90f, 360f * frac, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            ring(stroke / 2, hourFrac, Palette.violet)
            ring(stroke * 1.9f, minFrac, Palette.teal)
            ring(stroke * 3.3f, secFrac, Palette.coral)
        }
        Text("${now.hour12()}:%02d".format(now.minute()), color = Palette.ink, fontSize = (26 * s).sp, fontWeight = FontWeight.Light)
    }
}

// -- Terminal ----------------------------------------------------------------------------

@Composable
private fun TerminalFace(now: Date, modifier: Modifier, s: Float) {
    val green = Color(0xFF7CF2A2)
    val mono = FontFamily.Monospace
    Column(modifier) {
        Text("pixel@echo:~$ date +%R", color = Palette.muted.copy(alpha = 0.8f), fontFamily = mono, fontSize = (14 * s).sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("%02d:%02d".format(now.cal().get(Calendar.HOUR_OF_DAY), now.minute()), color = green, fontFamily = mono,
                fontSize = (66 * s).sp, fontWeight = FontWeight.Medium)
            // Cursor blinks once a second.
            Box(Modifier.padding(start = (6 * s).dp, bottom = (14 * s).dp).width((20 * s).dp).height((44 * s).dp)
                .background(green.copy(alpha = if (now.second() % 2 == 0) 0.85f else 0f)))
        }
    }
}

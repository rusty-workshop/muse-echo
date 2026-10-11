package com.rusty.museecho.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rusty.museecho.integrations.WeatherNow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How the clock sits over the photo-frame slideshow (Setup > Photo frame). */
enum class FrameClock(val label: String, val blurb: String) {
    CORNER("Corner card", "Time and date in a dark card, bottom left"),
    POSTER("Poster", "A big, thin time across the middle"),
    BANNER("Banner", "A soft band along the bottom, like a caption"),
    GLASS("Glass", "Time, date and weather on frosted glass, bottom right"),
    MINIMAL("Minimal", "Just a small time in the top corner"),
    ANALOG("Analog", "A little dial in the corner"),
    FLIP("Flip", "Split-flap cards in the corner"),
    WORDS("Words", "\"It's half past six\" over the photo"),
    NONE("Photos only", "No clock at all"),
    ;

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: CORNER
    }
}

/** A soft dark glow behind white text, so it reads over bright skies and snow too. */
private fun shadowed(size: TextUnit, weight: FontWeight = FontWeight.Light) = TextStyle(
    color = Color.White, fontSize = size, fontWeight = weight,
    shadow = Shadow(Color.Black.copy(alpha = 0.65f), Offset(0f, 2f), blurRadius = 10f),
)

private val timeFmt get() = SimpleDateFormat("h:mm", Locale.US)
private val dateFmt get() = SimpleDateFormat("EEEE, MMMM d", Locale.US)

private fun weatherLine(w: WeatherNow?) = w?.let { "${it.tempF}° ${it.summary}" }

/**
 * The slideshow clock in [style], laid over the whole photo. [scale] shrinks it for
 * the setup previews.
 */
@Composable
fun FrameClockOverlay(style: FrameClock, now: Date, weather: WeatherNow?, modifier: Modifier = Modifier, scale: Float = 1f) {
    val s = scale
    Box(modifier.fillMaxSize()) {
        when (style) {
            FrameClock.CORNER -> Corner(now, s)
            FrameClock.POSTER -> Poster(now, weather, s)
            FrameClock.BANNER -> Banner(now, weather, s)
            FrameClock.GLASS -> Glass(now, weather, s)
            FrameClock.MINIMAL -> Minimal(now, s)
            FrameClock.ANALOG -> Card(Alignment.BottomStart, s, CircleShape, pad = 6f) { ClockFaceView(ClockFace.ANALOG, now, scale = 0.62f * s) }
            FrameClock.FLIP -> Card(Alignment.BottomStart, s) {
                Column {
                    ClockFaceView(ClockFace.FLIP, now, scale = 0.62f * s)
                    Text(dateFmt.format(now), color = Color.White.copy(alpha = 0.85f), fontSize = (13 * s).sp, modifier = Modifier.padding(top = (4 * s).dp))
                }
            }
            FrameClock.WORDS -> Words(now, s)
            FrameClock.NONE -> {}
        }
    }
}

@Composable
private fun BoxScope.Card(
    align: Alignment, s: Float, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape((14 * s).dp), pad: Float = 12f,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.align(align).padding((20 * s).dp).background(Color.Black.copy(alpha = 0.38f), shape).padding((pad * s).dp),
    ) { content() }
}

@Composable
private fun BoxScope.Corner(now: Date, s: Float) {
    Column(
        Modifier.align(Alignment.BottomStart).padding((20 * s).dp)
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape((14 * s).dp))
            .padding(horizontal = (14 * s).dp, vertical = (8 * s).dp),
    ) {
        Text(timeFmt.format(now), color = Color.White, fontSize = (44 * s).sp, fontWeight = FontWeight.Light)
        Text(dateFmt.format(now), color = Color.White.copy(alpha = 0.85f), fontSize = (15 * s).sp)
    }
}

@Composable
private fun BoxScope.Poster(now: Date, weather: WeatherNow?, s: Float) {
    // A gentle darkening in the middle band keeps the big numerals readable.
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to Color.Transparent, 0.35f to Color.Black.copy(alpha = 0.25f), 0.7f to Color.Black.copy(alpha = 0.25f), 1f to Color.Transparent)))
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(timeFmt.format(now), style = shadowed((124 * s).sp, FontWeight.Thin))
        Text(listOfNotNull(dateFmt.format(now), weatherLine(weather)).joinToString("  ·  "),
            style = shadowed((18 * s).sp, FontWeight.Normal), modifier = Modifier.padding(top = (2 * s).dp))
    }
}

@Composable
private fun BoxScope.Banner(now: Date, weather: WeatherNow?, s: Float) {
    Row(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
            .padding(start = (24 * s).dp, end = (24 * s).dp, top = (40 * s).dp, bottom = (16 * s).dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(timeFmt.format(now), style = shadowed((56 * s).sp))
        Column(horizontalAlignment = Alignment.End) {
            Text(dateFmt.format(now), style = shadowed((17 * s).sp, FontWeight.Normal))
            weatherLine(weather)?.let { Text(it, style = shadowed((15 * s).sp, FontWeight.Normal).copy(color = Color.White.copy(alpha = 0.85f))) }
        }
    }
}

@Composable
private fun BoxScope.Glass(now: Date, weather: WeatherNow?, s: Float) {
    Column(
        Modifier.align(Alignment.BottomEnd).padding((20 * s).dp)
            .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape((20 * s).dp))
            .background(Color.Black.copy(alpha = 0.22f), RoundedCornerShape((20 * s).dp))
            .padding(horizontal = (18 * s).dp, vertical = (12 * s).dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(timeFmt.format(now), color = Color.White, fontSize = (48 * s).sp, fontWeight = FontWeight.Light)
        Text(dateFmt.format(now), color = Color.White.copy(alpha = 0.9f), fontSize = (14 * s).sp)
        weatherLine(weather)?.let { Text(it, color = Palette.amber, fontSize = (14 * s).sp, modifier = Modifier.padding(top = (2 * s).dp)) }
    }
}

@Composable
private fun BoxScope.Minimal(now: Date, s: Float) {
    Text(timeFmt.format(now), style = shadowed((28 * s).sp, FontWeight.Normal),
        modifier = Modifier.align(Alignment.TopEnd).padding(top = (18 * s).dp, end = (24 * s).dp))
}

@Composable
private fun BoxScope.Words(now: Date, s: Float) {
    val cal = java.util.Calendar.getInstance().apply { time = now }
    val (text, _) = timeInWords(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
        .padding(start = (28 * s).dp, end = (28 * s).dp, top = (36 * s).dp, bottom = (20 * s).dp)) {
        Text(text, style = shadowed((40 * s).sp))
    }
}

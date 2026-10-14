package com.rusty.museecho.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rusty.museecho.gadget.GadgetState
import com.rusty.museecho.integrations.Forecast
import com.rusty.museecho.integrations.Weather
import kotlinx.coroutines.delay

/** Hour-by-hour temperature and rain for the next day, and the week ahead. */
@Composable
internal fun ForecastPage(actions: UiActions) {
    val fc by GadgetState.forecast.collectAsState()
    // Fresh when the page opens, then every half hour while it stays up.
    LaunchedEffect(Unit) {
        while (true) {
            val stale = fc?.let { System.currentTimeMillis() - it.atMs > 20 * 60_000L } ?: true
            if (stale) actions.forecast()?.let { GadgetState.setForecast(it) }
            delay(30 * 60_000L)
        }
    }
    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 64.dp, bottom = 84.dp)) {
        Text("Forecast", color = Palette.amber, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        val f = fc
        if (f == null) {
            Text("Getting the forecast…", color = Palette.muted, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
            return@Column
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            HourlyChart(f, Modifier.weight(1.45f).fillMaxHeight())
            // Ends above the round mic button in the bottom-right corner.
            WeekList(f, Modifier.weight(1f).fillMaxHeight().padding(bottom = 24.dp))
        }
    }
}

/**
 * The next 48 hours as a strip you can swipe: an animated icon for each hour, the temperature as a
 * smooth curve, rain chance as bars, nights shaded, and sunrise and sunset marked.
 */
@Composable
private fun HourlyChart(f: Forecast, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val hours = f.hours.take(49)
    val clock = rememberIconClock()
    val zone = java.time.ZoneId.systemDefault()
    // Sunrise and sunset (epoch ms) over the hours shown.
    val sunMarks = remember(hours.firstOrNull()?.startMs) {
        val p = com.rusty.museecho.gadget.Prefs(ctx)
        if (hours.isEmpty()) emptyList() else {
            val first = java.time.Instant.ofEpochMilli(hours.first().startMs).atZone(zone).toLocalDate()
            (0..2).flatMap { d ->
                val date = first.plusDays(d.toLong())
                com.rusty.museecho.gadget.LifeMath.sunTimes(p.weatherLat, p.weatherLon, date, zone)?.let { (rise, set) ->
                    listOf(true to date.atStartOfDay(zone).plusMinutes(rise.toLong()), false to date.atStartOfDay(zone).plusMinutes(set.toLong()))
                }.orEmpty()
            }.map { (up, at) -> Triple(up, at.toInstant().toEpochMilli(), at.toLocalTime()) }
        }
    }
    val tempStyle = TextStyle(color = Palette.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val small = TextStyle(color = Palette.muted, fontSize = 12.sp)
    // Laid out once per forecast, not every frame.
    val temps = remember(hours, Palette.theme) { hours.map { tm.measure("${it.tempF}°", tempStyle) } }
    val labels = remember(hours, Palette.theme) {
        hours.mapIndexed { i, h -> tm.measure(if (i == 0) "Now" else when (h.hour) { 0 -> "12a"; 12 -> "12p"; in 1..11 -> "${h.hour}a"; else -> "${h.hour - 12}p" }, small) }
    }
    val rains = remember(hours, Palette.theme) { hours.map { tm.measure("${it.rainPct}%", TextStyle(color = Palette.teal, fontSize = 11.sp)) } }
    val sunLabels = remember(sunMarks, Palette.theme) {
        sunMarks.map { (up, _, time) ->
            tm.measure((if (up) "↑ " else "↓ ") + time.format(java.time.format.DateTimeFormatter.ofPattern("h:mm")), TextStyle(color = Palette.amber, fontSize = 11.sp))
        }
    }
    if (hours.size < 2) return
    val step = 46.dp
    Box(modifier.horizontalScroll(rememberScrollState())) {
        Canvas(Modifier.width(step * (hours.size - 1) + 24.dp).fillMaxHeight()) {
            val t = clock.value
            val stepPx = step.toPx()
            val pad = 12.dp.toPx()
            val labelH = 18.dp.toPx()
            val iconH = 30.dp.toPx()
            val rainH = size.height * 0.18f
            val top = iconH + labelH + 6.dp.toPx()
            val chartBottom = size.height - rainH - labelH - 6.dp.toPx()
            val mid = (hours.minOf { it.tempF } + hours.maxOf { it.tempF }) / 2
            val half = maxOf(10, (hours.maxOf { it.tempF } - hours.minOf { it.tempF }) / 2 + 2)
            val lo = mid - half
            val hi = mid + half
            fun x(i: Int) = pad + i * stepPx
            fun xAt(ms: Long) = pad + (ms - hours.first().startMs) / 3_600_000f * stepPx
            fun y(temp: Int) = chartBottom - (temp - lo).toFloat() / (hi - lo).coerceAtLeast(1) * (chartBottom - top)
            // Night shaded, using the real sunset and sunrise when known.
            hours.forEachIndexed { i, h ->
                val night = sunMarks.isEmpty() && (h.hour < 6 || h.hour >= 20) ||
                    sunMarks.isNotEmpty() && sunMarks.lastOrNull { it.second <= h.startMs + 1_800_000L }?.first == false
                if (night) drawRect(Color.White.copy(alpha = 0.035f), Offset(x(i) - stepPx / 2, 0f), Size(stepPx, size.height - labelH))
            }
            // Sunrise and sunset: a faint line and the time.
            sunMarks.forEachIndexed { k, (_, ms, _) ->
                val sx = xAt(ms)
                if (sx < 0f || sx > size.width) return@forEachIndexed
                drawLine(Palette.amber.copy(alpha = 0.35f), Offset(sx, iconH), Offset(sx, chartBottom), 1.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                val l = sunLabels[k]
                drawText(l, topLeft = Offset((sx - l.size.width / 2).coerceIn(0f, size.width - l.size.width), chartBottom + 2.dp.toPx()))
            }
            // An animated icon over each hour.
            hours.forEachIndexed { i, h ->
                val night = sunMarks.lastOrNull { it.second <= h.startMs + 1_800_000L }?.first == false || (sunMarks.isEmpty() && (h.hour < 6 || h.hour >= 20))
                drawSky(skyOf(h.code, night), Offset(x(i), iconH / 2 + 2.dp.toPx()), iconH * 0.85f, t + i * 0.3f)
            }
            val curve = Path().apply {
                moveTo(x(0), y(hours[0].tempF))
                for (i in 1 until hours.size) {
                    val px = x(i - 1); val py = y(hours[i - 1].tempF)
                    val cx = x(i); val cy = y(hours[i].tempF)
                    cubicTo(px + stepPx / 2, py, cx - stepPx / 2, cy, cx, cy)
                }
            }
            val fill = Path().apply {
                addPath(curve)
                lineTo(x(hours.size - 1), chartBottom)
                lineTo(x(0), chartBottom)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(Palette.amber.copy(alpha = 0.28f), Color.Transparent), top, chartBottom))
            drawPath(curve, Palette.amber, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            hours.forEachIndexed { i, h ->
                val px = x(i); val py = y(h.tempF)
                if (i % 2 == 0) {
                    drawCircle(Palette.amber, 3.5.dp.toPx(), Offset(px, py))
                    val tt = temps[i]
                    drawText(tt, topLeft = Offset(px - tt.size.width / 2, py - tt.size.height - 6.dp.toPx()))
                    val l = labels[i]
                    drawText(l, topLeft = Offset(px - l.size.width / 2, size.height - labelH))
                }
            }
            val barW = stepPx * 0.55f
            val rainTop = size.height - labelH - rainH - 2.dp.toPx()
            hours.forEachIndexed { i, h ->
                if (h.rainPct <= 0) return@forEachIndexed
                val bh = rainH * h.rainPct / 100f
                drawRoundRect(Palette.teal.copy(alpha = 0.35f + 0.5f * h.rainPct / 100f),
                    Offset(x(i) - barW / 2, rainTop + rainH - bh), Size(barW, bh), CornerRadius(2.dp.toPx()))
                if (i % 2 == 0 && h.rainPct >= 20) {
                    val r = rains[i]
                    drawText(r, topLeft = Offset(x(i) - r.size.width / 2, rainTop + rainH - bh - r.size.height))
                }
            }
        }
    }
}

@Composable
private fun WeekList(f: Forecast, modifier: Modifier) {
    val days = f.days.take(7)
    if (days.isEmpty()) return
    val weekLo = days.minOf { it.lowF }
    val weekHi = days.maxOf { it.highF }
    Column(modifier, verticalArrangement = Arrangement.SpaceBetween) {
        days.forEachIndexed { i, d ->
            val name = if (i == 0) "Today" else runCatching {
                java.time.LocalDate.parse(d.date).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US)
            }.getOrDefault(d.date)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = if (i == 0) Palette.ink else Palette.muted, fontSize = 15.sp, modifier = Modifier.width(50.dp))
                WeatherIcon(skyOf(d.code), 24.dp, Modifier.width(30.dp))
                Text(if (d.rainPct >= 20) "${d.rainPct}%" else "", color = Palette.teal, fontSize = 12.sp, modifier = Modifier.width(36.dp))
                Text("${d.lowF}°", color = Palette.muted, fontSize = 14.sp, modifier = Modifier.width(34.dp))
                // Where today's range sits within the week's.
                Box(Modifier.weight(1f).height(6.dp).glass(RoundedCornerShape(50))) {
                    val span = (weekHi - weekLo).coerceAtLeast(1).toFloat()
                    val start = (d.lowF - weekLo) / span
                    val end = (d.highF - weekLo) / span
                    Row(Modifier.fillMaxWidth()) {
                        if (start > 0f) Spacer(Modifier.weight(start))
                        Box(Modifier.weight((end - start).coerceAtLeast(0.04f)).height(6.dp)
                            .background(Brush.horizontalGradient(listOf(Palette.teal, Palette.amber)), RoundedCornerShape(50)))
                        if (end < 1f) Spacer(Modifier.weight(1f - end))
                    }
                }
                Text("${d.highF}°", color = Palette.ink, fontSize = 14.sp, modifier = Modifier.width(40.dp).padding(start = 8.dp))
            }
        }
    }
}

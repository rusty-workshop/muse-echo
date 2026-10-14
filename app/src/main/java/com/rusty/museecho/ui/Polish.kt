package com.rusty.museecho.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.rusty.museecho.gadget.GadgetState
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// -- Moving digits -------------------------------------------------------------------------

/** Text whose characters roll into place when they change (only the changed ones move). */
@Composable
internal fun RollingText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    Row(modifier) {
        text.forEachIndexed { i, ch ->
            // Keyed by position from the right, so 9:59 -> 10:00 rolls the right digits.
            androidx.compose.runtime.key(text.length - i) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        (slideInVertically(tween(420)) { it / 2 } + fadeIn(tween(420))) togetherWith
                            (slideOutVertically(tween(420)) { -it / 2 } + fadeOut(tween(300)))
                    },
                    label = "roll",
                ) { c -> Text(c.toString(), style = style) }
            }
        }
    }
}

// -- Hourly sparkle ------------------------------------------------------------------------

/**
 * A shimmer of little stars across the clock on the hour (a bigger one at noon and midnight).
 * Wraps the clock face; [hour] is the current 0..23 hour.
 */
@Composable
internal fun HourSparkle(hour: Int, content: @Composable () -> Unit) {
    val p = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    var big by remember { mutableStateOf(false) }
    LaunchedEffect(hour) {
        if (first) { first = false; return@LaunchedEffect }
        big = hour == 0 || hour == 12
        p.snapTo(0f)
        p.animateTo(1f, tween(if (big) 3_200 else 2_200, easing = LinearEasing))
    }
    Box(Modifier.drawWithContent {
        drawContent()
        val v = p.value
        if (v >= 1f) return@drawWithContent
        val n = if (big) 22 else 12
        for (i in 0 until n) {
            // Each star sweeps left to right along a wavy line, twinkling as it goes.
            val start = i / n.toFloat() * 0.5f
            val local = ((v - start) / 0.5f).coerceIn(0f, 1f)
            if (local <= 0f || local >= 1f) continue
            val x = size.width * (local * 1.1f - 0.05f)
            val y = size.height * (0.5f + 0.42f * sin(i * 1.7f + local * 6f))
            val a = sin(local * PI.toFloat())
            drawStar(Offset(x, y), (if (big) 7f else 5f).dp.toPx() * (0.6f + 0.4f * a), Color(0xFFFFF1B8).copy(alpha = a))
        }
    }) { content() }
}

private fun DrawScope.drawStar(c: Offset, r: Float, color: Color) {
    val path = Path().apply {
        for (k in 0 until 8) {
            val ang = k * PI.toFloat() / 4 - PI.toFloat() / 2
            val rr = if (k % 2 == 0) r else r * 0.3f
            val pt = c + Offset(cos(ang) * rr, sin(ang) * rr)
            if (k == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
        }
        close()
    }
    drawPath(path, color)
}

// -- Typed-out replies ---------------------------------------------------------------------

/**
 * Reveals [full] word by word (catching up faster the further behind it is, so long replies don't
 * drag), with the newest few words faded in at the edge. Restarts when a different reply begins.
 */
@Composable
internal fun rememberReveal(full: AnnotatedString, fade: Color): AnnotatedString {
    val text = full.text
    // Keyed by how the reply starts: a new reply starts again; the same one growing as it streams keeps going.
    var shown by remember(text.take(12)) { mutableStateOf(0) }
    LaunchedEffect(text) {
        while (shown < text.length) {
            val behind = text.length - shown
            var next = shown + maxOf(1, behind / 14)
            // To the end of the word.
            while (next < text.length && !text[next].isWhitespace()) next++
            shown = next.coerceAtMost(text.length)
            delay(45)
        }
    }
    val n = shown.coerceAtMost(full.length)
    if (n >= full.length) return full
    val edge = (n - 14).coerceAtLeast(0).let { e -> text.lastIndexOf(' ', e).let { if (it < 0) 0 else it } }
    return buildAnnotatedString {
        append(full.subSequence(0, edge))
        withStyle(SpanStyle(color = fade)) { append(full.subSequence(edge, n)) }
    }
}

// -- Edge glow -----------------------------------------------------------------------------

/** What the screen edges are lit for: amber heads-up, red urgent, teal good news. */
internal enum class Glow(val color: Color) { HEADS_UP(Color(0xFFFFC266)), URGENT(Color(0xFFFF5A6A)), GOOD(Color(0xFF45D3C7)) }

/** A soft coloured light creeping in from the screen edges, breathing slowly, instead of loud banners. */
@Composable
internal fun EdgeGlow() {
    val alerts by GadgetState.alerts.collectAsState()
    val closed by GadgetState.dismissedAlerts.collectAsState()
    val internet by GadgetState.internet.collectAsState()
    val server by GadgetState.server.collectAsState()
    val asleep by GadgetState.asleep.collectAsState()
    // Good news for a few seconds: the server's back.
    var good by remember { mutableStateOf(false) }
    var wasDown by remember { mutableStateOf(false) }
    LaunchedEffect(server?.overall) {
        val down = server?.overall == "down"
        if (wasDown && !down && server?.overall == "up") { good = true; delay(8_000); good = false }
        wasDown = down
    }
    val open = alerts.filter { it.id !in closed }
    val glow = when {
        open.any { it.severity.equals("Severe", true) || it.severity.equals("Extreme", true) } || server?.overall == "down" -> Glow.URGENT
        open.isNotEmpty() || internet == false -> Glow.HEADS_UP
        good -> Glow.GOOD
        else -> null
    }
    val shown by androidx.compose.animation.core.animateFloatAsState(if (glow != null && !asleep) 1f else 0f, tween(1500), label = "edge")
    var lastColor by remember { mutableStateOf(Glow.HEADS_UP.color) }
    glow?.let { lastColor = it.color }
    if (shown <= 0.01f) return
    val breathe by rememberInfiniteTransition(label = "edgeBreath").animateFloat(0.55f, 1f,
        infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "edgeA")
    val c = lastColor
    Canvas(Modifier.fillMaxSize()) {
        val a = 0.32f * shown * breathe
        val w = 46.dp.toPx()
        drawRect(Brush.horizontalGradient(listOf(c.copy(alpha = a), Color.Transparent), 0f, w), Offset.Zero, Size(w, size.height))
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, c.copy(alpha = a)), size.width - w, size.width), Offset(size.width - w, 0f), Size(w, size.height))
        drawRect(Brush.verticalGradient(listOf(c.copy(alpha = a), Color.Transparent), 0f, w), Offset.Zero, Size(size.width, w))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, c.copy(alpha = a)), size.height - w, size.height), Offset(0f, size.height - w), Size(size.width, w))
    }
}

// -- Shimmer placeholders ------------------------------------------------------------------

/** A glass placeholder with a band of light sweeping across it, while something loads. */
internal fun Modifier.shimmer(): Modifier = composed {
    val x by rememberInfiniteTransition(label = "shimmer").animateFloat(-1f, 2f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "shimmerX")
    this.glass(RoundedCornerShape(10.dp)).drawWithContent {
        drawContent()
        val band = size.width * 0.35f
        val cx = size.width * x
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent), cx - band, cx + band))
    }
}

/** A few shimmering lines standing in for content that's on its way. */
@Composable
internal fun ShimmerLines(lines: Int = 4, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(lines) { i ->
            Box(Modifier.fillMaxWidth(if (i % 3 == 2) 0.55f else if (i % 2 == 1) 0.8f else 0.95f).height(18.dp).shimmer())
        }
    }
}

// -- Empty-state drawings ------------------------------------------------------------------

internal enum class EmptyArt { LAZY_DAY, HAPPY_SERVER, NOTHING_FOUND }

/** A small drawing for a page with nothing on it, with a line of text under it. */
@Composable
internal fun EmptyState(art: EmptyArt, text: String, modifier: Modifier = Modifier) {
    val clock = rememberIconClock()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(width = 180.dp, height = 110.dp)) {
            val t = clock.value
            when (art) {
                EmptyArt.LAZY_DAY -> {
                    // A hammock between two trees, swinging gently, under a lazy sun.
                    val ground = size.height * 0.9f
                    for (x in listOf(size.width * 0.15f, size.width * 0.85f)) {
                        drawLine(Color(0xFF7A5536), Offset(x, ground), Offset(x, size.height * 0.35f), 6.dp.toPx())
                        drawCircle(Color(0xFF4FA060).copy(alpha = 0.8f), 22.dp.toPx(), Offset(x, size.height * 0.28f))
                    }
                    val sway = sin(t * 1.2f) * 4.dp.toPx()
                    val hammock = Path().apply {
                        moveTo(size.width * 0.15f, size.height * 0.5f)
                        quadraticTo(size.width * 0.5f + sway, size.height * 0.85f, size.width * 0.85f, size.height * 0.5f)
                    }
                    drawPath(hammock, Palette.amber.copy(alpha = 0.8f), style = Stroke(5.dp.toPx()))
                    sun(Offset(size.width * 0.5f, size.height * 0.18f), 12.dp.toPx(), t)
                    drawLine(Color(0xFF4FA060).copy(alpha = 0.5f), Offset(0f, ground), Offset(size.width, ground), 2.dp.toPx())
                }
                EmptyArt.HAPPY_SERVER -> {
                    // A little server box with a smile and blinking lights.
                    val w = 70.dp.toPx(); val h = 84.dp.toPx()
                    val tl = Offset(size.width / 2 - w / 2, size.height - h - 4.dp.toPx())
                    drawRoundRect(Color(0xFF2E2A38), tl, Size(w, h), androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                    for (k in 0..2) {
                        val y = tl.y + 12.dp.toPx() + k * 14.dp.toPx()
                        drawLine(Color(0xFF4A4458), Offset(tl.x + 10.dp.toPx(), y), Offset(tl.x + w - 22.dp.toPx(), y), 3.dp.toPx())
                        val on = ((t * 3).toInt() + k) % 3 != 0
                        drawCircle(Color(0xFF5FE08A).copy(alpha = if (on) 1f else 0.3f), 3.dp.toPx(), Offset(tl.x + w - 12.dp.toPx(), y))
                    }
                    val face = Offset(size.width / 2, tl.y + h - 22.dp.toPx())
                    for (s in listOf(-1f, 1f)) drawCircle(Palette.ink, 2.5f.dp.toPx(), face + Offset(s * 9.dp.toPx(), -4.dp.toPx()))
                    drawArc(Palette.ink, 20f, 140f, false, face + Offset(-8.dp.toPx(), -4.dp.toPx()), Size(16.dp.toPx(), 10.dp.toPx()), style = Stroke(2.dp.toPx()))
                }
                EmptyArt.NOTHING_FOUND -> {
                    // A magnifying glass looking around, with a question mark.
                    val c = Offset(size.width / 2 + sin(t * 1.3f) * 14.dp.toPx(), size.height * 0.45f)
                    drawCircle(Palette.muted, 22.dp.toPx(), c, style = Stroke(5.dp.toPx()))
                    drawLine(Palette.muted, c + Offset(16.dp.toPx(), 16.dp.toPx()), c + Offset(34.dp.toPx(), 34.dp.toPx()), 7.dp.toPx())
                    drawCircle(Palette.teal.copy(alpha = 0.15f), 18.dp.toPx(), c)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(text, color = Palette.muted, fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

private fun DrawScope.sun(c: Offset, r: Float, t: Float) {
    for (k in 0 until 8) {
        val a = t * 0.3f + k * PI.toFloat() / 4
        drawLine(Color(0xFFFFC94D), c + Offset(cos(a), sin(a)) * r * 1.3f, c + Offset(cos(a), sin(a)) * r * 1.7f, r * 0.18f)
    }
    drawCircle(Color(0xFFFFC94D), r, c)
}

// -- Page dots, with badges, and where the pager is (for the background parallax) -----------

/** The home pager's position (page + fraction), for things that move with swipes. */
internal object PagerScroll {
    var pos by mutableFloatStateOf(0f)
}

/**
 * A dot per page along the bottom, the current one wider; a page that needs attention gets a
 * coloured badge on its dot ([badges]: page id to colour).
 */
@Composable
internal fun PageDots(pages: List<Int>, current: Int, badges: Map<Int, Color>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        pages.forEachIndexed { i, id ->
            val on = i == current
            val w by androidx.compose.animation.core.animateDpAsState(if (on) 16.dp else 6.dp, tween(300), label = "dot$i")
            Box {
                Box(Modifier.width(w).height(6.dp).background(if (on) Palette.ink.copy(alpha = 0.85f) else Palette.muted.copy(alpha = 0.45f), RoundedCornerShape(50)))
                badges[id]?.let { c ->
                    Box(Modifier.align(Alignment.TopEnd).padding(start = 0.dp).size(7.dp).background(c, CircleShape)
                        .let { m -> m })
                }
            }
        }
    }
}

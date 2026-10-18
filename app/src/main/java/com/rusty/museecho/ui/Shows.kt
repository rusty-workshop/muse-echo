package com.rusty.museecho.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rusty.museecho.gadget.GadgetService
import com.rusty.museecho.gadget.GadgetState
import kotlin.math.abs
import kotlin.math.sin

private const val TRUCK_MS = 7_000L

/**
 * The mail truck bringing a letter from Rusty: it drives in, stops by Puff, and drives off. Same
 * space as the room ([blob] centre, [half] size).
 */
@Composable
internal fun ShowLayer(blob: Offset, half: Float) {
    if (blob == Offset.Unspecified || half <= 0f) return
    val clock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val truck = System.currentTimeMillis() - GadgetState.mailTruckAt < TRUCK_MS
            if (truck) awaitBeat(40) else kotlinx.coroutines.delay(1_000)
            clock.longValue = System.currentTimeMillis()
        }
    }
    val u = half / 110f
    Canvas(Modifier.fillMaxSize()) {
        val now = clock.longValue
        val since = now - GadgetState.mailTruckAt
        if (since in 0 until TRUCK_MS) drawMailTruck(blob, u, since / 1000f, this.size.width)
    }
}

/** A little red mail van: in from the right, a stop beside Puff (with a toot), out to the left. */
private fun DrawScope.drawMailTruck(blob: Offset, u: Float, s: Float, width: Float) {
    val stopX = blob.x + 120 * u
    val x = when {
        s < 2.2f -> width + 60 * u + (stopX - width - 60 * u) * easeOut(s / 2.2f)
        s < 4.4f -> stopX
        else -> stopX + (-120 * u - stopX) * easeIn((s - 4.4f) / 2.6f)
    }
    val floor = blob.y + 92 * u
    val bounce = if (s in 2.2f..4.4f) 0f else abs(sin(s * 14f)) * 1.5f * u
    val y = floor - 30 * u - bounce
    val red = Color(0xFFE5484D)
    // Body and cab.
    drawRoundRect(red, Offset(x - 30 * u, y), Size(44 * u, 24 * u), CornerRadius(4 * u))
    drawRoundRect(red, Offset(x + 12 * u, y + 7 * u), Size(16 * u, 17 * u), CornerRadius(4 * u))
    drawRoundRect(Color(0xFFBFE6F5), Offset(x + 16 * u, y + 9 * u), Size(9 * u, 7 * u), CornerRadius(2 * u))
    drawRect(Color.White, Offset(x - 26 * u, y + 8 * u), Size(30 * u, 6 * u))
    // A heart on the side (it's a letter from Rusty).
    drawCircle(Color(0xFFFFB3C7), 2.6f * u, Offset(x - 13 * u, y + 11 * u))
    drawCircle(Color(0xFFFFB3C7), 2.6f * u, Offset(x - 9 * u, y + 11 * u))
    for (wx in listOf(x - 20 * u, x + 18 * u)) {
        drawCircle(Color(0xFF2A2A35), 5 * u, Offset(wx, floor - 6 * u))
        drawCircle(Color(0xFF9AA4B2), 2 * u, Offset(wx, floor - 6 * u))
    }
    // Toot toot, while it's stopped.
    if (s in 2.4f..4.2f && (s * 3).toInt() % 2 == 0) {
        drawCircle(Color.White.copy(alpha = 0.6f), 3 * u, Offset(x + 34 * u, y + 2 * u))
        drawCircle(Color.White.copy(alpha = 0.4f), 2 * u, Offset(x + 40 * u, y - 3 * u))
    }
}

private fun easeOut(p: Float) = 1f - (1f - p.coerceIn(0f, 1f)).let { it * it }
private fun easeIn(p: Float) = p.coerceIn(0f, 1f).let { it * it }

/** Trivia night: Pixel's question, four answers to tap, the score, and Puff's reactions around it. */
@Composable
internal fun TriviaOverlay() {
    val g = GadgetState.trivia.collectAsState().value ?: return
    val fl = GadgetService.instance?.life?.fun_
    // On the right, leaving Puff in view on the left to cheer and groan.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.fillMaxWidth(0.64f).fillMaxHeight().background(Palette.night.copy(alpha = 0.9f), RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))
            .clickable(enabled = false) {}.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("🧠 Trivia night · ${g.theme}", color = Palette.amber, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            when {
                g.failed -> {
                    Text("Pixel's question book got stuck 😅 Try again in a bit?", color = Palette.ink, fontSize = 20.sp, textAlign = TextAlign.Center)
                }
                g.loading -> Text("Pixel is writing the questions… ✏️", color = Palette.ink, fontSize = 20.sp)
                g.done -> {
                    val n = g.questions.size
                    Text(if (g.score == n) "🏆 $n out of $n!" else "${g.score} out of $n", color = Palette.ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    val stats = GadgetState.friends.collectAsState().value.trivia
                    Text("Best streak: ${stats.bestStreak} in a row · games played: ${stats.played}", color = Palette.muted, fontSize = 14.sp)
                }
                else -> {
                    val q = g.questions[g.at]
                    Text("Question ${g.at + 1} of ${g.questions.size} · score ${g.score}", color = Palette.muted, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(q.q, color = Palette.ink, fontSize = 22.sp, textAlign = TextAlign.Center, lineHeight = 28.sp)
                    Spacer(Modifier.height(14.dp))
                    q.options.chunked(2).forEachIndexed { row, pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                            pair.forEachIndexed { k, opt ->
                                val i = row * 2 + k
                                val shown = g.picked >= 0
                                val bg = when {
                                    shown && i == q.answer -> Color(0xFF2E9E5B)
                                    shown && i == g.picked -> Color(0xFFE5484D)
                                    else -> Palette.card
                                }
                                Text(opt, color = Palette.ink, fontSize = 18.sp, textAlign = TextAlign.Center, maxLines = 2,
                                    modifier = Modifier.width(250.dp).background(bg, RoundedCornerShape(14.dp))
                                        .border(1.dp, Palette.muted.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                                        .clickable(enabled = !shown) { fl?.answerTrivia(i) }.padding(vertical = 14.dp, horizontal = 10.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(if (g.done || g.failed) "Close" else "Stop playing", color = Palette.muted, fontSize = 15.sp,
                modifier = Modifier.clickable { fl?.closeTrivia() }.padding(10.dp))
        }
    }
}

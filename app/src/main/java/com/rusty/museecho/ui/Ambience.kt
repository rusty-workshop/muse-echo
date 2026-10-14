package com.rusty.museecho.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rusty.museecho.gadget.GadgetState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * Very faint things drifting behind everything: dust motes catching the light by day, fireflies
 * in the evening, snowflakes in winter. Off while asleep, and with its switch (Setup > Looks).
 */
@Composable
internal fun AmbientLayer() {
    val looks by GadgetState.looks.collectAsState()
    val asleep by GadgetState.asleep.collectAsState()
    if (!looks.ambience || asleep) return
    val clock = rememberIconClock()
    val cal = java.util.Calendar.getInstance()
    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val month = cal.get(java.util.Calendar.MONTH) + 1
    val kind = when {
        month == 12 || month <= 2 -> 2 // snow
        hour >= 18 || hour < 6 -> 1 // fireflies
        else -> 0 // dust motes
    }
    Canvas(Modifier.fillMaxSize()) {
        val t = clock.value
        for (i in 0 until 18) {
            // Each one has its own slow path across the screen.
            val seed = i * 0.618034f
            val speed = 0.012f + (i % 5) * 0.004f
            when (kind) {
                2 -> {
                    val y = ((t * speed * 2.2f + seed) % 1f) * size.height
                    val x = ((seed * 997f) % size.width) + sin(t * 0.6f + i) * 24f
                    drawCircle(Color.White.copy(alpha = 0.16f), 2.2f + (i % 3), Offset(x, y))
                }
                1 -> {
                    val x = ((seed * 997f + t * speed * 300f) % size.width)
                    val y = size.height * (0.3f + 0.6f * ((seed * 613f) % 1f)) + sin(t * 0.7f + i) * 30f
                    val glow = (0.5f + 0.5f * sin(t * (0.9f + (i % 4) * 0.3f) + i)).coerceAtLeast(0f)
                    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE08A).copy(alpha = 0.35f * glow), Color.Transparent), Offset(x, y), 10f), 10f, Offset(x, y))
                    drawCircle(Color(0xFFFFF1B8).copy(alpha = 0.55f * glow), 1.8f, Offset(x, y))
                }
                else -> {
                    val x = ((seed * 997f + t * speed * 120f) % size.width)
                    val y = ((seed * 613f) % 1f) * size.height + sin(t * 0.4f + i) * 18f
                    drawCircle(Color.White.copy(alpha = 0.07f + 0.05f * sin(t * 0.5f + i)), 1.6f + (i % 2), Offset(x, y))
                }
            }
        }
    }
}

/** Shown once per start of the app (not when the screen just turns around). */
private var splashDone = false

/** A short start-up animation instead of a blank screen: a glow swelling into a little blob, and the name. */
@Composable
internal fun StartupSplash() {
    var show by remember { mutableStateOf(!splashDone) }
    val grow = remember { Animatable(0f) }
    val words = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (!show) return@LaunchedEffect
        launch { grow.animateTo(1f, tween(900)) }
        delay(450)
        words.animateTo(1f, tween(500))
        delay(500)
        splashDone = true
        show = false
    }
    AnimatedVisibility(show, exit = fadeOut(tween(500))) {
        Box(Modifier.fillMaxSize().background(Palette.night), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(140.dp)) {
                    val g = grow.value
                    val c = Offset(size.width / 2, size.height / 2)
                    val r = size.minDimension / 2 * (0.3f + 0.7f * g)
                    drawCircle(Brush.radialGradient(listOf(Palette.violet.copy(alpha = 0.45f * g), Color.Transparent), c, r * 1.2f), r * 1.2f, c)
                    drawCircle(Brush.radialGradient(listOf(Palette.teal.copy(alpha = 0.5f * g), Color.Transparent), c, r * 0.7f), r * 0.7f, c)
                    drawCircle(Palette.violet.copy(alpha = g), r * 0.45f, c)
                    // Its eyes open at the end.
                    if (g > 0.85f) {
                        val e = (g - 0.85f) / 0.15f
                        for (s in listOf(-1f, 1f)) drawOval(Color(0xFF231C30).copy(alpha = 0.88f),
                            Offset(c.x + s * r * 0.17f - r * 0.035f, c.y - r * 0.12f), androidx.compose.ui.geometry.Size(r * 0.07f, r * 0.13f * e))
                    }
                }
                Text("Muse Echo", color = Palette.ink, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.graphicsLayer { alpha = words.value; translationY = (1f - words.value) * 12f })
            }
        }
    }
}

package com.rusty.museecho.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Frosted glass: a see-through tint of the theme's card colour, a faint sheen from the top and a
 * thin bright rim, so cards sit on the background instead of covering it. (A real backdrop blur
 * needs Android 12; this keeps the look without making the Echo's GPU blur every frame.)
 */
internal fun Modifier.glass(shape: Shape, strength: Float = 1f): Modifier = this
    .background(Palette.card.copy(alpha = 0.42f * strength + 0.08f), shape)
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f * strength), Color.White.copy(alpha = 0.02f * strength))), shape)
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.24f * strength), Color.White.copy(alpha = 0.05f * strength))), shape)

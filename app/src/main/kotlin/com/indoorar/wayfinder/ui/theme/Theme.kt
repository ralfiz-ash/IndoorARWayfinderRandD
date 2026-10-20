package com.indoorar.wayfinder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF1E5BD8)

@Composable
fun WayfinderTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF8FB0FF), secondary = Color(0xFF7BD8A6))
    } else {
        lightColorScheme(primary = Blue, secondary = Color(0xFF1C8A55))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

object StatusColors {
    val Good = Color(0xFF2E9E5B)
    val Warn = Color(0xFFE69A1C)
    val Bad = Color(0xFFD64545)
    val Neutral = Color(0xFF6B7280)
}

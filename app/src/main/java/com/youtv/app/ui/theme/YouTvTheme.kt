package com.youtv.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TvColors = darkColorScheme(
    primary = Color(0xFF61D6C5),
    secondary = Color(0xFF90CAF9),
    background = Color.Black,
    surface = Color(0xE6192027),
    onSurface = Color.White,
)

@Composable
fun YouTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TvColors, content = content)
}

package com.shivansh.rollcall.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val Scheme = darkColorScheme(
    primary = Magenta,
    onPrimary = Ink,
    background = Ink,
    onBackground = TextPrimary,
    surface = InkElevated,
    onSurface = TextPrimary,
)

@Composable
fun RollCallTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = RollCallType, content = content)
}

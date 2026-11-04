package com.csc.translate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF111111)
val Mute = Color(0xFF9CA3AF)
val FocusDim = Color(0xFF8A9099)
val Hint = Color(0xFFB0B4BA)
val Pane = Color(0xFFF3F4F6)
val Chip = Color(0xFFF2F3F5)
val Danger = Color(0xFFFF3B30)
val Hairline = Color(0xFFE6E6E6)
val AccentBlue = Color(0xFF4C8DFF)
val AccentBlueFill = Color(0xFFEEF4FF)

private val Scheme = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Pane,
    onSurfaceVariant = Mute,
    error = Danger,
)

@Composable
fun CscTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = MaterialTheme.typography.copy(
            titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink),
            titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Ink),
            bodyLarge = TextStyle(fontSize = 16.sp, color = Ink, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, color = Ink, lineHeight = 20.sp),
            labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink),
            labelSmall = TextStyle(fontSize = 12.sp, color = Mute),
        ),
        content = content,
    )
}

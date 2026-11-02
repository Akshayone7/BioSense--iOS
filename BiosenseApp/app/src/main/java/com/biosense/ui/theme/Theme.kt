package com.biosense.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF0B0F14)
private val Surface = Color(0xFF121822)
private val OnBg = Color(0xFFE7EDF5)
private val Primary = Color(0xFF2563EB)
private val Hr = Color(0xFFFF6B7A)
private val Spo2 = Color(0xFF4FC3F7)
private val Hrv = Color(0xFF6EE7A8)

val AccentHr = Hr
val AccentSpo2 = Spo2
val AccentHrv = Hrv

private val DarkColors = darkColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    background = Bg,
    onBackground = OnBg,
    surface = Surface,
    onSurface = OnBg,
    surfaceVariant = Color(0xFF1F2733),
    onSurfaceVariant = Color(0xFF9AA6B8),
)

private val LightColors = lightColorScheme(primary = Primary)

@Composable
fun BiosenseTheme(useDark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (useDark) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
            bodyMedium = TextStyle(fontSize = 14.sp),
        ),
        content = content
    )
}

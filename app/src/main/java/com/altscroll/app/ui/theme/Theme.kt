package com.altscroll.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val Mint = Color(0xFF5EE6C5)
private val Periwinkle = Color(0xFF7AA2FF)
private val Ink = Color(0xFF0B1020)
private val InkRaised = Color(0xFF151B2E)

private val DarkScheme = darkColorScheme(
    primary = Mint,
    onPrimary = Ink,
    secondary = Periwinkle,
    onSecondary = Ink,
    background = Ink,
    onBackground = Color(0xFFE9ECF5),
    surface = InkRaised,
    onSurface = Color(0xFFE9ECF5),
    surfaceVariant = Color(0xFF232B42),
    onSurfaceVariant = Color(0xFFAEB6CC),
    error = Color(0xFFFF8A80),
    outline = Color(0xFF3A4360),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0E9E80),
    onPrimary = Color.White,
    secondary = Color(0xFF3A66D6),
    background = Color(0xFFF7F8FC),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE6E9F2),
    onSurfaceVariant = Color(0xFF515A72),
    outline = Color(0xFFC6CBD9),
)

private val AppTypography = Typography(
    headlineMedium = Typography().headlineMedium.copy(
        fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp
    ),
    titleLarge = Typography().titleLarge.copy(
        fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 29.sp
    ),
    bodyLarge = Typography().bodyLarge.copy(fontSize = 17.sp, lineHeight = 26.sp),
)

@Composable
fun AltScrollTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val view = LocalContext.current as? Activity

    SideEffect {
        view?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}

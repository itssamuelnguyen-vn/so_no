package com.samuelcruz.sono.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.samuelcruz.sono.R
import com.samuelcruz.sono.core.Prefs

/** Be Vietnam Pro: an open-source typeface drawn for Vietnamese (licenses/BeVietnamPro-OFL.txt). */
val BeVietnamPro = FontFamily(
    Font(R.font.be_vietnam_pro_regular, FontWeight.Normal),
    Font(R.font.be_vietnam_pro_medium, FontWeight.Medium),
    Font(R.font.be_vietnam_pro_semibold, FontWeight.SemiBold),
    Font(R.font.be_vietnam_pro_bold, FontWeight.Bold),
    Font(R.font.be_vietnam_pro_extrabold, FontWeight.ExtraBold),
)

private fun TextStyle.be(weight: FontWeight? = null) =
    copy(fontFamily = BeVietnamPro, fontWeight = weight ?: fontWeight, fontFeatureSettings = "tnum")

private val typography = Typography().run {
    copy(
        displayLarge = displayLarge.be(FontWeight.ExtraBold),
        displayMedium = displayMedium.be(FontWeight.ExtraBold),
        displaySmall = displaySmall.be(FontWeight.ExtraBold),
        headlineLarge = headlineLarge.be(FontWeight.Bold),
        headlineMedium = headlineMedium.be(FontWeight.Bold),
        headlineSmall = headlineSmall.be(FontWeight.Bold),
        titleLarge = titleLarge.be(FontWeight.Bold),
        titleMedium = titleMedium.be(FontWeight.SemiBold),
        titleSmall = titleSmall.be(FontWeight.SemiBold),
        bodyLarge = bodyLarge.be(),
        bodyMedium = bodyMedium.be(),
        bodySmall = bodySmall.be(),
        labelLarge = labelLarge.be(FontWeight.SemiBold),
        labelMedium = labelMedium.be(FontWeight.SemiBold),
        labelSmall = labelSmall.be(FontWeight.Medium),
    )
}

/** Brand: deep teal (trust, money kept safe). */
val Brand = Color(0xFF0F766E)

private val Light = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = Color(0xFFB45309),
    secondaryContainer = Color(0xFFFEF3C7),
    onSecondaryContainer = Color(0xFF451A03),
    background = Color(0xFFF4F7F6),
    onBackground = Color(0xFF14201E),
    surface = Color(0xFFF4F7F6),
    onSurface = Color(0xFF14201E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFECF2F0),
    surfaceContainerHighest = Color(0xFFE2EAE8),
    onSurfaceVariant = Color(0xFF5F6F6C),
    outline = Color(0xFFBFCDCA),
    outlineVariant = Color(0xFFE1E8E6),
    error = Color(0xFFDC2626),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF134E4A),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFFFBBF24),
    secondaryContainer = Color(0xFF4A3410),
    onSecondaryContainer = Color(0xFFFEF3C7),
    background = Color(0xFF0B1211),
    onBackground = Color(0xFFE6EFED),
    surface = Color(0xFF0B1211),
    onSurface = Color(0xFFE6EFED),
    surfaceContainerLowest = Color(0xFF080D0C),
    surfaceContainerLow = Color(0xFF131C1B),
    surfaceContainer = Color(0xFF16201F),
    surfaceContainerHigh = Color(0xFF1D2928),
    surfaceContainerHighest = Color(0xFF253331),
    onSurfaceVariant = Color(0xFF9DB0AC),
    outline = Color(0xFF41524F),
    outlineVariant = Color(0xFF243230),
    error = Color(0xFFF87171),
)

/** Colours Material has no slot for: money coming back to me, money I owe, late, and the header gradient. */
@Immutable
data class MoneyColors(val collect: Color, val pay: Color, val late: Color, val hero: Brush) {
    fun of(direction: Int): Color = if (direction == com.samuelcruz.sono.core.Direction.LENT) collect else pay
}

private val heroBrush = Brush.linearGradient(listOf(Color(0xFF0D9488), Color(0xFF0F766E), Color(0xFF115E59)))
private val LightMoney = MoneyColors(Color(0xFF059669), Color(0xFFEA580C), Color(0xFFDC2626), heroBrush)
private val DarkMoney = MoneyColors(Color(0xFF34D399), Color(0xFFFB923C), Color(0xFFF87171), heroBrush)

val LocalMoneyColors = staticCompositionLocalOf { LightMoney }

/** Whether the app is drawn dark (the user's choice in Settings, or the phone's setting). */
val LocalIsDark = staticCompositionLocalOf { false }

/** [theme] is a [Prefs] THEME_ value. */
@Composable
fun SoNoTheme(theme: Int, content: @Composable () -> Unit) {
    val dark = when (theme) {
        Prefs.THEME_LIGHT -> false
        Prefs.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
    CompositionLocalProvider(LocalMoneyColors provides if (dark) DarkMoney else LightMoney, LocalIsDark provides dark) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = typography) {
            // Text outside a Surface would otherwise be black, even in dark mode.
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, content = content)
        }
    }
}

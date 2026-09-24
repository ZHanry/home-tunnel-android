package io.github.zhanry.hometunnel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF5D5CDB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEEEEFE),
    onPrimaryContainer = Color(0xFF29285F),
    secondary = Color(0xFF686B90),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E9F8),
    onSecondaryContainer = Color(0xFF282B4C),
    tertiary = Color(0xFF805600),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDA4),
    onTertiaryContainer = Color(0xFF281800),
    background = Color(0xFFFBFCFF),
    onBackground = Color(0xFF242640),
    surface = Color(0xFFFBFCFF),
    onSurface = Color(0xFF242640),
    surfaceVariant = Color(0xFFF1F2FA),
    onSurfaceVariant = Color(0xFF666D82),
    outline = Color(0xFF858BA2),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9B7FF),
    onPrimary = Color(0xFF282657),
    primaryContainer = Color(0xFF37365F),
    onPrimaryContainer = Color(0xFFE2E1FF),
    secondary = Color(0xFFC8C9E4),
    onSecondary = Color(0xFF292B48),
    secondaryContainer = Color(0xFF41435D),
    onSecondaryContainer = Color(0xFFE1E2F8),
    tertiary = Color(0xFFFFBA46),
    onTertiary = Color(0xFF442C00),
    tertiaryContainer = Color(0xFF614000),
    onTertiaryContainer = Color(0xFFFFDDA4),
    background = Color(0xFF121425),
    onBackground = Color(0xFFE8E9F6),
    surface = Color(0xFF121425),
    onSurface = Color(0xFFE8E9F6),
    surfaceVariant = Color(0xFF2B2E48),
    onSurfaceVariant = Color(0xFFBBC0D9),
    outline = Color(0xFF9196B3),
    error = Color(0xFFFFB4AB),
)

@Composable
fun HomeTunnelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}

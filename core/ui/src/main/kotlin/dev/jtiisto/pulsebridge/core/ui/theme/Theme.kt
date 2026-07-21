package dev.jtiisto.pulsebridge.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    background = Background,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
    primary = Primary,
    onBackground = OnBackground,
    onSurface = OnSurface,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    error = Error,
)

@Composable
fun PulseBridgeTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = PulseBridgeTypography,
        content = content,
    )
}

package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = DarkTealPrimary,
    onPrimary = DarkTealOnPrimary,
    primaryContainer = DarkTealContainer,
    onPrimaryContainer = DarkTealOnContainer,
    secondary = DarkAmberSecondary,
    onSecondary = DarkAmberOnSecondary,
    secondaryContainer = DarkAmberContainer,
    onSecondaryContainer = DarkAmberOnContainer,
    tertiary = DarkEmeraldTertiary,
    onTertiary = DarkEmeraldOnTertiary,
    tertiaryContainer = DarkEmeraldContainer,
    onTertiaryContainer = DarkEmeraldOnContainer,
    background = DarkSlateBackground,
    onBackground = DarkSlateOnSurface,
    surface = DarkSlateSurface,
    onSurface = DarkSlateOnSurface,
    surfaceVariant = DarkSlateSurfaceVariant,
    onSurfaceVariant = DarkSlateOnSurfaceVariant,
    outline = DarkSlateOutline
)

private val LightColorScheme = lightColorScheme(
    primary = TealInkPrimary,
    onPrimary = TealInkOnPrimary,
    primaryContainer = TealInkContainer,
    onPrimaryContainer = TealInkOnContainer,
    secondary = AmberHighlightSecondary,
    onSecondary = AmberOnSecondary,
    secondaryContainer = AmberContainer,
    onSecondaryContainer = AmberOnContainer,
    tertiary = EmeraldAccentTertiary,
    onTertiary = EmeraldOnTertiary,
    tertiaryContainer = EmeraldContainer,
    onTertiaryContainer = EmeraldOnContainer,
    background = ChalkBackground,
    onBackground = ChalkOnSurface,
    surface = ChalkSurface,
    onSurface = ChalkOnSurface,
    surfaceVariant = ChalkSurfaceVariant,
    onSurfaceVariant = ChalkOnSurfaceVariant,
    outline = ChalkOutline
)

private val GurivaShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun GurivaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = GurivaShapes,
        content = content
    )
}

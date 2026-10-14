package ru.tennispredictor.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val TennisLightColors = lightColorScheme(
    primary = Color(0xFF176B50),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9F1E5),
    onPrimaryContainer = Color(0xFF103D2E),
    secondary = Color(0xFFD8EF70),
    onSecondary = Color(0xFF27340C),
    secondaryContainer = Color(0xFFF0F7CB),
    onSecondaryContainer = Color(0xFF3B4B17),
    tertiary = Color(0xFF8A5A28),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F7F4),
    onBackground = Color(0xFF192820),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF192820),
    surfaceVariant = Color(0xFFEAF1EC),
    onSurfaceVariant = Color(0xFF506258),
    outline = Color(0xFFD0DCD4),
    outlineVariant = Color(0xFFE1E9E3),
    error = Color(0xFFBA1A1A)
)

private val TennisShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun TennisPredictTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TennisLightColors,
        typography = Typography(),
        shapes = TennisShapes,
        content = content
    )
}

package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val ladiesLightColors = lightColorScheme(
    primary = Color(0xFFA33100), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCA), onPrimaryContainer = Color(0xFF572000),
    inversePrimary = Color(0xFFFFB395),
    secondary = Color(0xFF68584F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0E0D6), onSecondaryContainer = Color(0xFF45362D),
    tertiary = Color(0xFF286721), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCDECC4), onTertiaryContainer = Color(0xFF174611),
    background = Color(0xFFFAF8F6), onBackground = Color(0xFF252321),
    surface = Color(0xFFFAF8F6), onSurface = Color(0xFF252321),
    surfaceVariant = Color(0xFFEDE8E4), onSurfaceVariant = Color(0xFF504B47),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F3F0),
    surfaceContainer = Color(0xFFF2EEEB), surfaceContainerHigh = Color(0xFFEDE8E4),
    surfaceContainerHighest = Color(0xFFE6E1DD),
    surfaceBright = Color.White, surfaceDim = Color(0xFFE6E1DD),
    outline = Color(0xFF77716C), outlineVariant = Color(0xFFC9C1BB),
    inverseSurface = Color(0xFF343638), inverseOnSurface = Color(0xFFF7F7F7),
    error = Color(0xFFA51B35), onError = Color.White,
    errorContainer = Color(0xFFFFDCE0), onErrorContainer = Color(0xFF760D24),
)

internal val ladiesDarkColors = darkColorScheme(
    // Exact reference orange is a filled accent; text needs more contrast on raised surfaces.
    primary = Color(0xFFFF8B5E), onPrimary = Color(0xFF1E160F),
    primaryContainer = Color(0xFFFF510A), onPrimaryContainer = Color(0xFF1E160F),
    inversePrimary = Color(0xFFA33100),
    secondary = Color(0xFFCDC0B8), onSecondary = Color(0xFF362B25),
    secondaryContainer = Color(0xFF484039), onSecondaryContainer = Color(0xFFF1E4DC),
    tertiary = Color(0xFF5DE44E), onTertiary = Color(0xFF153A0C),
    tertiaryContainer = Color(0xFF284C20), onTertiaryContainer = Color(0xFFD0F4C6),
    background = Color(0xFF202123), onBackground = Color(0xFFF7F7F7),
    surface = Color(0xFF202123), onSurface = Color(0xFFF7F7F7),
    surfaceVariant = Color(0xFF292B2D), onSurfaceVariant = Color(0xFFADADB0),
    surfaceContainerLowest = Color(0xFF191B1D), surfaceContainerLow = Color(0xFF202123),
    surfaceContainer = Color(0xFF292B2D), surfaceContainerHigh = Color(0xFF343638),
    surfaceContainerHighest = Color(0xFF3C3E40),
    surfaceBright = Color(0xFF3C3E40), surfaceDim = Color(0xFF191B1D),
    outline = Color(0xFF929295), outlineVariant = Color(0xFF48494D),
    inverseSurface = Color(0xFFEDE8E4), inverseOnSurface = Color(0xFF252321),
    error = Color(0xFFFF8A98), onError = Color(0xFF4A0917),
    errorContainer = Color(0xFF6A2633), onErrorContainer = Color(0xFFFFDCE0),
)

private val ladiesShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val baseType = Typography()
private val ladiesTypography = Typography(
    headlineMedium = baseType.headlineMedium.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    headlineSmall = baseType.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = baseType.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = baseType.titleMedium.copy(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
    bodyLarge = baseType.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = baseType.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = baseType.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = baseType.labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = baseType.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
)

@Composable
fun LadiesTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) ladiesDarkColors else ladiesLightColors,
        shapes = ladiesShapes,
        typography = ladiesTypography,
        content = content,
    )
}

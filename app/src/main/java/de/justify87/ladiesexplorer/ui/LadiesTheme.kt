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

// Legacy tokens.css: brand pink, cool neutral surfaces; text roles retain readable contrast.
internal val ladiesLightColors = lightColorScheme(
    primary = Color(0xFFD12D7C), onPrimary = Color.White,
    primaryContainer = Color(0xFFF9DBE9), onPrimaryContainer = Color(0xFF8B174E),
    inversePrimary = Color(0xFFFFA8D0),
    secondary = Color(0xFFA43A69), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFF2F7), onSecondaryContainer = Color(0xFF8B174E),
    tertiary = Color(0xFF4268A5), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE2EBFA), onTertiaryContainer = Color(0xFF203C6D),
    background = Color(0xFFFBFBFD), onBackground = Color(0xFF172238),
    surface = Color.White, onSurface = Color(0xFF172238),
    surfaceVariant = Color(0xFFF6F6FA), onSurfaceVariant = Color(0xFF59657A),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFBFBFD),
    surfaceContainer = Color(0xFFF6F6FA), surfaceContainerHigh = Color(0xFFECEEF4),
    surfaceContainerHighest = Color(0xFFE5E7EE),
    surfaceBright = Color.White, surfaceDim = Color(0xFFE5E7EE),
    outline = Color(0xFF727D90), outlineVariant = Color(0xFFECEEF3),
    inverseSurface = Color(0xFF252B39), inverseOnSurface = Color(0xFFF6F6FA),
    error = Color(0xFFB94865), onError = Color.White,
    errorContainer = Color(0xFFFFE1E8), onErrorContainer = Color(0xFF841A3B),
)

internal val ladiesDarkColors = darkColorScheme(
    primary = Color(0xFFFFA8D0), onPrimary = Color(0xFF5D1339),
    primaryContainer = Color(0xFF652448), onPrimaryContainer = Color(0xFFFFD9EA),
    inversePrimary = Color(0xFFC52472),
    secondary = Color(0xFFF3ACC9), onSecondary = Color(0xFF59243C),
    secondaryContainer = Color(0xFF4A1731), onSecondaryContainer = Color(0xFFF9DBE9),
    tertiary = Color(0xFFAFC9F5), onTertiary = Color(0xFF19375E),
    tertiaryContainer = Color(0xFF284569), onTertiaryContainer = Color(0xFFDCE9FF),
    background = Color(0xFF151822), onBackground = Color(0xFFF1F2F8),
    surface = Color(0xFF1C202C), onSurface = Color(0xFFF1F2F8),
    surfaceVariant = Color(0xFF292E3D), onSurfaceVariant = Color(0xFFAAB4C8),
    surfaceContainerLowest = Color(0xFF11141D), surfaceContainerLow = Color(0xFF1A1D28),
    surfaceContainer = Color(0xFF202430), surfaceContainerHigh = Color(0xFF292E3D),
    surfaceContainerHighest = Color(0xFF333848),
    surfaceBright = Color(0xFF363B4B), surfaceDim = Color(0xFF151822),
    outline = Color(0xFF98A2B5), outlineVariant = Color(0xFF42495B),
    inverseSurface = Color(0xFFECEEF4), inverseOnSurface = Color(0xFF252B39),
    error = Color(0xFFFFB3C5), onError = Color(0xFF680B30),
    errorContainer = Color(0xFF742D46), onErrorContainer = Color(0xFFFFD9E4),
)

private val ladiesShapes = Shapes(
    extraSmall = RoundedCornerShape(7.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val baseType = Typography()
private val ladiesTypography = Typography(
    headlineMedium = baseType.headlineMedium.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    headlineSmall = baseType.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = baseType.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = baseType.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = baseType.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = baseType.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
    labelMedium = baseType.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
)

@Composable
fun LadiesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) ladiesDarkColors else ladiesLightColors,
        shapes = ladiesShapes,
        typography = ladiesTypography,
        content = content,
    )
}

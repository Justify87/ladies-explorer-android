package de.justify87.ladiesexplorer.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val fallbackLight = lightColorScheme(
    primary = Color(0xFF4C5FB9),
    secondary = Color(0xFF635A84),
    tertiary = Color(0xFF8B4C75),
)

private val fallbackDark = darkColorScheme(
    primary = Color(0xFFBEC3FF),
    secondary = Color(0xFFC9BFEC),
    tertiary = Color(0xFFF1B5D8),
)

@Composable
fun LadiesTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> fallbackDark
        else -> fallbackLight
    }
    MaterialTheme(colorScheme = colors, content = content)
}

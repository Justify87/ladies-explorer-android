package de.justify87.ladiesexplorer.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LadiesThemeTest {
    private fun contrast(a: Color, b: Color): Float {
        val values = listOf(a.luminance(), b.luminance()).sorted()
        return (values[1] + 0.05f) / (values[0] + 0.05f)
    }

    private fun verify(scheme: ColorScheme) {
        val pairs = listOf(
            scheme.onPrimary to scheme.primary,
            scheme.onPrimaryContainer to scheme.primaryContainer,
            scheme.onSecondary to scheme.secondary,
            scheme.onSecondaryContainer to scheme.secondaryContainer,
            scheme.onTertiary to scheme.tertiary,
            scheme.onTertiaryContainer to scheme.tertiaryContainer,
            scheme.onBackground to scheme.background,
            scheme.onSurface to scheme.surface,
            scheme.onSurfaceVariant to scheme.surfaceVariant,
            scheme.onSurface to scheme.surfaceContainerHighest,
            scheme.onError to scheme.error,
            scheme.onErrorContainer to scheme.errorContainer,
            scheme.primary to scheme.surface,
            scheme.primary to scheme.background,
            scheme.error to scheme.surface,
            scheme.primary to scheme.surfaceContainerHigh,
            scheme.primary to scheme.surfaceContainerHighest,
            scheme.onSurfaceVariant to scheme.surfaceContainerHighest,
            scheme.onSurfaceVariant to scheme.surfaceContainer,
            scheme.onSurfaceVariant to scheme.surfaceContainerLow,
            scheme.primary to scheme.surfaceContainerLow,
            scheme.onSurface to scheme.surfaceContainer,
            scheme.error to scheme.surfaceContainerHighest,
        )
        pairs.forEach { (foreground, background) ->
            assertTrue("Insufficient contrast: $foreground on $background", contrast(foreground, background) >= 4.5f)
        }
    }

    @Test fun contentInsetUsesCompactAndExpandedRaster() {
        assertEquals(ShellMetrics.inset, ShellMetrics.contentInset(360))
        assertEquals(ShellMetrics.inset, ShellMetrics.contentInset(599))
        assertEquals(ShellMetrics.section, ShellMetrics.contentInset(600))
        assertEquals(0f, ShellMetrics.contentInset(192).value, .01f)
    }

    @Test fun brandAndReadableRoles() {
        assertEquals(Color(0xFFFF510A), ladiesDarkColors.primaryContainer)
        assertEquals(Color(0xFF202123), ladiesDarkColors.background)
        assertEquals(Color(0xFFFAF8F6), ladiesLightColors.background)
        assertTrue(contrast(shellFocusColor(true), ladiesDarkColors.surfaceContainerHighest) >= 3f)
        assertTrue(contrast(shellFocusColor(false), ladiesLightColors.surfaceContainerHighest) >= 3f)
        verify(ladiesLightColors)
        verify(ladiesDarkColors)
    }
}

package eu.darken.porter.manager.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import eu.darken.porter.manager.TestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.pow

/**
 * The surface tonal family is hand-entered per scheme, so every token is checked against the M3
 * tone it is supposed to carry, computed from the sRGB value rather than compared to a table.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class PorterColorsTest {

    private data class Token(val name: String, val light: Double, val dark: Double, val of: (ColorScheme) -> Color)

    private val tokens = listOf(
        Token("surfaceContainerLowest", 100.0, 4.0) { it.surfaceContainerLowest },
        Token("surfaceBright", 98.0, 24.0) { it.surfaceBright },
        Token("surfaceContainerLow", 96.0, 10.0) { it.surfaceContainerLow },
        Token("surfaceContainer", 94.0, 12.0) { it.surfaceContainer },
        Token("surfaceContainerHigh", 92.0, 17.0) { it.surfaceContainerHigh },
        Token("surfaceContainerHighest", 90.0, 22.0) { it.surfaceContainerHighest },
        Token("surfaceDim", 87.0, 6.0) { it.surfaceDim },
    )

    private val schemes = listOf(
        Triple("Blue.LightDefault", PorterColorsBlue.LightDefault, false),
        Triple("Blue.LightMediumContrast", PorterColorsBlue.LightMediumContrast, false),
        Triple("Blue.LightHighContrast", PorterColorsBlue.LightHighContrast, false),
        Triple("Blue.DarkDefault", PorterColorsBlue.DarkDefault, true),
        Triple("Blue.DarkMediumContrast", PorterColorsBlue.DarkMediumContrast, true),
        Triple("Blue.DarkHighContrast", PorterColorsBlue.DarkHighContrast, true),
        Triple("Green.LightDefault", PorterColorsGreen.LightDefault, false),
        Triple("Green.LightMediumContrast", PorterColorsGreen.LightMediumContrast, false),
        Triple("Green.LightHighContrast", PorterColorsGreen.LightHighContrast, false),
        Triple("Green.DarkDefault", PorterColorsGreen.DarkDefault, true),
        Triple("Green.DarkMediumContrast", PorterColorsGreen.DarkMediumContrast, true),
        Triple("Green.DarkHighContrast", PorterColorsGreen.DarkHighContrast, true),
        Triple("Amoled.LightDefault", PorterColorsAmoled.LightDefault, false),
        Triple("Amoled.LightMediumContrast", PorterColorsAmoled.LightMediumContrast, false),
        Triple("Amoled.LightHighContrast", PorterColorsAmoled.LightHighContrast, false),
        Triple("Amoled.DarkDefault", PorterColorsAmoled.DarkDefault, true),
        Triple("Amoled.DarkMediumContrast", PorterColorsAmoled.DarkMediumContrast, true),
        Triple("Amoled.DarkHighContrast", PorterColorsAmoled.DarkHighContrast, true),
    )

    private val amoledDark = listOf(
        "Amoled.DarkDefault" to PorterColorsAmoled.DarkDefault,
        "Amoled.DarkMediumContrast" to PorterColorsAmoled.DarkMediumContrast,
        "Amoled.DarkHighContrast" to PorterColorsAmoled.DarkHighContrast,
    )

    /** The true-black tokens of the AMOLED dark schemes, exempt from the tone check. */
    private val amoledBlackTokens = setOf("surfaceContainerLowest", "surfaceDim")

    private fun channel(value: Float): Double {
        val c = value.toDouble()
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color) =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    /** CIELAB L*, the quantity a Material 3 tone number names. */
    private fun tone(color: Color): Double {
        val y = luminance(color)
        return if (y <= 216.0 / 24389.0) y * 24389.0 / 27.0 else y.pow(1.0 / 3.0) * 116.0 - 16.0
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test fun surfaceTokensCarryTheirMaterialTone() {
        for ((name, scheme, dark) in schemes) {
            for (token in tokens) {
                if (name.startsWith("Amoled.Dark") && token.name in amoledBlackTokens) continue
                val expected = if (dark) token.dark else token.light
                val actual = tone(token.of(scheme))
                assertTrue(
                    "$name.${token.name} is tone %.2f, expected %.2f".format(actual, expected),
                    kotlin.math.abs(actual - expected) <= 1.0
                )
            }
        }
    }

    @Test fun amoledDarkKeepsATrueBlackGround() {
        for ((name, scheme) in amoledDark) {
            assertEquals("$name.surface", Color(0xFF000000), scheme.surface)
            assertEquals("$name.surfaceDim", Color(0xFF000000), scheme.surfaceDim)
            assertEquals("$name.surfaceContainerLowest", Color(0xFF000000), scheme.surfaceContainerLowest)
            for (token in tokens) {
                if (token.name in amoledBlackTokens) continue
                assertTrue(
                    "$name.${token.name} must be an elevated tone, not black",
                    tone(token.of(scheme)) > 1.0
                )
            }
        }
    }

    @Test fun onSurfaceStaysReadableOnEverySurfaceContainer() {
        for ((name, scheme, _) in schemes) {
            for (token in tokens) {
                val ratio = contrast(scheme.onSurface, token.of(scheme))
                assertTrue(
                    "$name.onSurface on ${token.name} is %.2f:1, below 4.5:1".format(ratio),
                    ratio >= 4.5
                )
            }
        }
    }
}

package com.discord.utilities.theme

import com.discord.models.domain.ModelUserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeResolverTest {
    private val accent = -14188314
    private val aliuVision = ThemeJson.parse(
        """
        {
          "manifest": { "name": "AliuVision" },
          "simple_colors": {
            "background": 0, "background_secondary": 1056964608, "accent": $accent,
            "active_channel": -2144894746, "mention_highlight": 606568678
          },
          "colors": { "uikit_btn_bg_color_selector_green": -14188314, "uikit_btn_bg_color_selector_red": -14589527 }
        }
        """.trimIndent(),
    ).theme

    @Test
    fun simpleColorsReachTheSurfacesTheyPaintedInAliucord() {
        val resolved = ThemeResolver.resolve(aliuVision)
        val attrs = resolved.attrs
        // `background` repaints primary_dark_600, whose original value colorBackgroundPrimary held.
        assertEquals(0, attrs["colorBackgroundPrimary"])
        assertEquals(0, attrs["colorBackgroundFloating"])
        // `background_secondary` comes later in the file and wins over the shared primary_dark_630.
        assertEquals(1056964608, attrs["colorBackgroundSecondary"])
        assertEquals(1056964608, attrs["colorBackgroundTertiary"])
        assertEquals(-2144894746, attrs["colorChannelItemSelected"])
        assertEquals(accent, attrs["color_brand"])
        assertEquals(accent, attrs["colorControlBrandForeground"])
        assertEquals((accent and 0xFFFFFF) or 0x1A000000, attrs["theme_chat_mention_background"])
        assertEquals(606568678, attrs["theme_chat_mentioned_me"])
        assertEquals((606568678 and 0xFFFFFF) or (0xFF shl 24), resolved.colors["status_yellow_500"])
        assertTrue(resolved.warnings.isEmpty())
    }

    @Test
    fun selectorNamesRecolorTheirLeafInsteadOfFlatteningStates() {
        val resolved = ThemeResolver.resolve(aliuVision)
        assertEquals(-14188314, resolved.colors["status_green_600"])
        assertEquals(-14589527, resolved.colors["status_red_500"])
        assertFalse(resolved.colors.containsKey("uikit_btn_bg_color_selector_green"))
        // colorStatusDanger still uses status_red_500 by name, so the palette override alone reaches it.
        assertNull(resolved.attrs["colorStatusDanger"])
    }

    @Test
    fun laterSectionsWin() {
        val theme = ThemeDefinition(
            ThemeDefinition.Manifest("x"),
            simpleColors = mapOf("background" to 1),
            colors = mapOf("primary_dark_600" to 2),
            attrs = mapOf("colorBackgroundPrimary" to 3),
        )
        val resolved = ThemeResolver.resolve(theme)
        assertEquals(2, resolved.colors["primary_dark_600"])
        assertEquals(3, resolved.attrs["colorBackgroundPrimary"])
        assertEquals(2, resolved.attrs["colorBackgroundMobilePrimary"])
    }

    @Test
    fun valueSwapsAreDarkOnlyAndUnknownKeysWarn() {
        val light = ThemeDefinition(
            ThemeDefinition.Manifest("x"),
            base = ModelUserSettings.THEME_LIGHT,
            simpleColors = mapOf("sparkle" to 5),
            colors = mapOf("primary_dark_600" to 2),
        )
        val resolved = ThemeResolver.resolve(light)
        assertEquals(2, resolved.colors["primary_dark_600"])
        assertNull(resolved.attrs["colorBackgroundPrimary"])
        assertEquals(listOf("simple_colors.sparkle is not a known key"), resolved.warnings)
    }
}

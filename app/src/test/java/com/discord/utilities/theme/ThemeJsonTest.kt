package com.discord.utilities.theme

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeJsonTest {
    @Test
    fun readsAnAliucordThemeAndKeepsUnappliedSections() {
        val parsed = ThemeJson.parse(
            """
            {
              // Aliucord themes are hand edited; comments happen.
              "manifest": { "name": "AliuVision", "version": "1.0.1", "author": "Ella", "license": "Apache License 2.0" },
              "background": { "url": "https://i.imgur.com/eOIFmqY.jpg", "blur_radius": 1.7 },
              "fonts": { "*": "https://example.com/a.ttf" },
              "raws": { "deafen": "file:///sdcard/d.mp3" },
              "future_section": { "x": 1 },
              "simple_colors": { "background": 0, "background_secondary": 1056964608, "accent": -14188314 },
              "colors": { "uikit_btn_bg_color_selector_green": -14188314, "broken": "nope" },
              "drawable_tints": { "ic_status_online_16dp": -12339839 }
            }
            """.trimIndent(),
        )
        val theme = parsed.theme
        assertEquals(ThemeDefinition.Manifest("AliuVision", "Ella", "1.0.1", "Apache License 2.0"), theme.manifest)
        assertEquals(mapOf("background" to 0, "background_secondary" to 1056964608, "accent" to -14188314), theme.simpleColors)
        assertEquals(mapOf("uikit_btn_bg_color_selector_green" to -14188314), theme.colors)
        assertEquals(ThemeDefinition.Background("https://i.imgur.com/eOIFmqY.jpg", 150, 1.7f), theme.background)
        assertEquals(mapOf("*" to "https://example.com/a.ttf"), theme.fonts)
        assertEquals(mapOf("deafen" to "file:///sdcard/d.mp3"), theme.raws)
        assertEquals(mapOf("ic_status_online_16dp" to -12339839), theme.drawableTints)
        assertEquals(setOf("future_section"), theme.extras.keys)
        // A transparent main background means the image was meant to show everywhere.
        assertEquals(ThemeDefinition.Transparency.FULL, theme.effectiveTransparency)
        assertEquals(ThemeDefinition.BASE_DEFAULT, theme.base)
        assertTrue(parsed.warnings.single().startsWith("colors.broken"))
    }

    @Test
    fun exportStaysLoadableByAliucordAndRoundTrips() {
        val theme = ThemeDefinition(
            manifest = ThemeDefinition.Manifest("Mine"),
            base = "midnight",
            simpleColors = mapOf("accent" to -14188314),
            colors = mapOf("brand_new" to 0x7F5865F2),
            attrs = mapOf("colorBackgroundPrimary" to 0xFF101010.toInt()),
            drawableTints = mapOf("ic_thread" to -1),
            background = ThemeDefinition.Background("file:///x.jpg", overlayAlpha = 80, blurRadius = 3f),
            fonts = mapOf("whitney_medium" to "https://example.com/w.ttf"),
            raws = mapOf("mute" to "https://example.com/m.mp3"),
            transparency = ThemeDefinition.Transparency.CHAT_SETTINGS,
            systemColors = mapOf("colors.link" to "system_accent1_500"),
            extras = mapOf("future_section" to JsonParser.parseString("""{"x":1}""")),
        )
        val json = ThemeJson.toJson(theme)
        val tree = JsonParser.parseString(json).asJsonObject
        // Aliucord's loader throws on anything but ints in its own sections.
        tree.getAsJsonObject("simple_colors").entrySet().forEach { assertTrue(it.value.asJsonPrimitive.isNumber) }
        assertEquals("system_accent1_500", tree.getAsJsonObject("colors").get("link").asString)
        assertEquals("#FF101010", tree.getAsJsonObject("attrs").get("colorBackgroundPrimary").asString)
        val reparsed = ThemeJson.parse(json)
        assertEquals(theme, reparsed.theme)
        assertTrue(reparsed.warnings.isEmpty())
    }

    @Test
    fun convertsTheLegacyFlatFormat() {
        val parsed = ThemeJson.parse(
            """{"name":"Dracula","author":"x","simple_accent_color":4283845988,"simple_bg_color":-14869217,
               "color_primary_dark_600":-14869217,"drawablecolor_ic_thread":-1,"background_url":"https://a/b.png",
               "font":"https://a/f.ttf"}""",
        )
        val theme = parsed.theme
        assertEquals("Dracula", theme.manifest.name)
        assertEquals(mapOf("accent" to 4283845988L.toInt(), "background" to -14869217), theme.simpleColors)
        assertEquals(mapOf("primary_dark_600" to -14869217), theme.colors)
        assertEquals("https://a/b.png", theme.background?.url)
        assertEquals(mapOf("*" to "https://a/f.ttf"), theme.fonts)
        assertEquals(mapOf("ic_thread" to -1), theme.drawableTints)
        assertTrue(parsed.warnings.single().contains("legacy"))
    }

    @Test
    fun materialYouColorsResolveLateAndLayeringFollowsAliucord() {
        val dynamic = ThemeJson.parse("""{"simple_colors":{"accent":"system_accent1_500","background":-1}}""").theme
        assertEquals(mapOf("simple_colors.accent" to "system_accent1_500"), dynamic.systemColors)
        assertEquals(mapOf("accent" to 7, "background" to -1), dynamic.withSystemColors { if (it == "system_accent1_500") 7 else null }.simpleColors)

        val lower = ThemeDefinition(ThemeDefinition.Manifest("a"), base = "light", colors = mapOf("x" to 1, "y" to 1), systemColors = dynamic.systemColors)
        val upper = ThemeDefinition(ThemeDefinition.Manifest("b"), colors = mapOf("y" to 2), simpleColors = mapOf("accent" to 3))
        val layered = ThemeDefinition.layer(listOf(lower, upper))
        assertEquals(mapOf("x" to 1, "y" to 2), layered.colors)
        assertEquals("light", layered.base)
        assertTrue("an upper plain color replaces a lower Material You one", layered.systemColors.isEmpty())
        assertEquals("b", layered.manifest.name)
    }

    @Test
    fun badSectionsAndBasesWarnInsteadOfFailing() {
        val parsed = ThemeJson.parse(
            """{"colors": [1, 2], "attrs": {"colorTextNormal": "#fff"}, "microcord": {"base": "neon"}}""",
        )
        assertEquals(mapOf("colorTextNormal" to -1), parsed.theme.attrs)
        assertEquals(ThemeDefinition.BASE_DEFAULT, parsed.theme.base)
        assertEquals(ThemeJson.DEFAULT_NAME, parsed.theme.manifest.name)
        assertEquals(2, parsed.warnings.size)
    }

    @Test
    fun refusesWhatIsNotATheme() {
        listOf("[]", "\"x\"", "{}", """{"name":"only a name"}""", "{not json", "x".repeat(ThemeJson.MAX_CHARS + 1))
            .forEach { text -> assertThrows(text.take(20), IllegalArgumentException::class.java) { ThemeJson.parse(text) } }
        val tooMany = (0..ThemeJson.MAX_ENTRIES).joinToString(",", "{\"colors\":{", "}}") { "\"c$it\":1" }
        assertThrows(IllegalArgumentException::class.java) { ThemeJson.parse(tooMany) }
        assertFalse(ThemeJson.parse("""{"colors":{}}""").theme.manifest.name.isEmpty())
    }
}

package com.discord.utilities.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import com.discord.R
import com.discord.models.domain.ModelUserSettings
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Discord's Nitro client themes: the gradient presets, and custom themes made on another client.
 *
 * Applied as a generated [ThemeDefinition] under the local themes: its gradient is the background
 * image of the whole app, and the surfaces turn see-through black (white on light) so it shows.
 * The numbers are RN's (`ClientThemesConstants`, `ClientThemesUtils.getGradientValue` and the
 * `gradient` entries of the semantic color table), mapped onto this app's theme attributes.
 */
sealed class ClientTheme {
    /** The built-in theme it sits on: [ModelUserSettings.THEME_DARKER], `_LIGHT` or `_MIDNIGHT`. */
    abstract val base: String
    abstract val colors: List<Int>

    /** Where each color sits, 0-100; RN spaces a custom theme's colors evenly for its surfaces. */
    abstract val stops: List<Float>
    abstract val angle: Float

    /** Whether the account holds it; a choice that never reached the account stays on this device. */
    abstract val synced: Boolean

    val isDark get() = base != ModelUserSettings.THEME_LIGHT

    data class Preset(
        val id: Int,
        @StringRes val name: Int,
        override val base: String,
        override val colors: List<Int>,
        override val stops: List<Float>,
        override val angle: Float,
        override val synced: Boolean = false,
    ) : ClientTheme()

    data class Custom(
        override val base: String,
        override val colors: List<Int>,
        /** As stored; when it does not pair up with [colors] the gradient spaces them evenly. */
        val gradientStops: List<Float>,
        override val angle: Float,
        /** 0-100: how much of the colors comes through. */
        val baseMix: Int,
        override val synced: Boolean = false,
    ) : ClientTheme() {
        override val stops: List<Float> get() = evenStops(colors.size)
    }

    fun withSynced(synced: Boolean): ClientTheme = when (this) {
        is Preset -> copy(synced = synced)
        is Custom -> copy(synced = synced)
    }

    /** The same theme, ignoring where it came from. */
    fun sameAs(other: ClientTheme?) = other != null && withSynced(false) == other.withSynced(false)

    // region colors

    private val overlay get() = if (isDark) BLACK else WHITE

    /** RN's `getGradientValue`: the gradient at [percent], mostly overlay so text stays readable. */
    internal fun value(percent: Float): Int {
        val opacity = when (this) {
            is Preset -> if (isDark) 0.2f else 0.4f
            is Custom -> (if (isDark) 0.12f else 0.3f) + 0.2f * baseMix / 100f
        }
        return ColorUtils.blendARGB(overlay, sample(colors, stops, percent), opacity)
    }

    private val start get() = value(0f)
    private val mid get() = value(50f)
    private val end get() = value(100f)
    private val bottom get() = if (angle > ANGLE_BREAKPOINT) end else start

    /** RN's `gradient.secondary`: a preset's last color; for a custom theme, its brightest (dark) or dimmest (light) one. */
    private val secondary: Int
        get() = when (this) {
            is Preset -> colors.last()
            is Custom -> {
                val byLuminance = colors.sortedBy(ColorUtils::calculateLuminance)
                if (isDark) hsl(byLuminance.last(), s = 0.2f, l = 0.9f) else hsl(byLuminance.first(), s = 0.2f, l = 0.7f)
            }
        }

    /** The attributes it overrides; [ThemeLinks] carries each to the attributes sharing its base color. */
    internal fun attrs(): Map<String, Int> {
        val dark = isDark
        val floating = hsl(mid, l = if (dark) 0.1f else 0.9f)
        val bar = hsl(bottom, l = if (dark) 0.16f else 0.98f)
        val modifier = hsl(secondary, s = 1f, l = if (dark) 0.92f else 0.08f)
        return buildMap {
            // Clear: the scrim RN paints on each one is baked into the background ([render]) instead,
            // since here the canvas surfaces nest, and three of them would bury the gradient.
            SURFACES.forEach { put(it, 0) }
            put("colorBackgroundSecondaryAlt", if (dark) alpha(BLACK, 0.5f) else alpha(WHITE, 0.5f))
            FLOATING.forEach { put(it, floating) }
            put("colorBackgroundNestedFloating", if (dark) hsl(mid, l = 0.12f, sScale = 0.5f) else hsl(mid, l = 0.925f, sScale = 0.8f))
            BARS.forEach { put(it, bar) }
            put("colorBackgroundAccent", alpha(hsl(mid, lScale = 0.5f, sScale = if (dark) 1.2f else 0.2f), if (dark) 0.8f else 0.9f))
            MODIFIERS.forEach { (name, a) -> put(name, ColorUtils.setAlphaComponent(modifier, a)) }
            put("colorInputBackground", alpha(BLACK, if (dark) 0.3f else 0.1f))
            put("colorInteractiveMuted", alpha(if (dark) WHITE else BLACK, 0.3f))
            put("colorGuildBarIconBackground", alpha(if (dark) WHITE else BLACK, 0.1f))
        }
    }

    /** The generated theme, with [backgroundUrl] pointing at a [render] of the gradient. */
    fun definition(backgroundUrl: String) = ThemeDefinition(
        manifest = ThemeDefinition.Manifest(name = "Nitro theme"),
        base = base,
        attrs = attrs(),
        background = ThemeDefinition.Background(backgroundUrl, overlayAlpha = 0),
        transparency = ThemeDefinition.Transparency.FULL,
    )

    /**
     * The gradient as RN's `ThemedGradient` draws it. With [scrim], a preset also gets the canvas
     * surfaces' 70% black (80% white on light), which is what the app shows. A custom theme's
     * colors are already toned down, and RN draws the chat straight onto them.
     */
    fun render(width: Int, height: Int, scrim: Boolean = false): Bitmap {
        val (x0, y0, x1, y1) = when (this) {
            // react-native-linear-gradient's angle, which is CSS's: 0 points up, 90 right.
            is Preset -> {
                val radians = Math.toRadians(angle.toDouble())
                val dx = sin(radians).toFloat()
                val dy = -cos(radians).toFloat()
                val half = (abs(width * dx) + abs(height * dy)) / 2
                listOf(width / 2f - dx * half, height / 2f - dy * half, width / 2f + dx * half, height / 2f + dy * half)
            }
            // RN's `CustomThemesGradient` start and end points.
            is Custom -> {
                val radians = Math.toRadians(angle - 90.0)
                val dx = 0.714f * cos(radians).toFloat()
                val dy = 0.714f * sin(radians).toFloat()
                listOf((0.6f - dx) * width, (0.5f - dy) * height, (0.6f + dx) * width, (0.5f + dy) * height)
            }
        }
        val (drawn, positions) = when (this) {
            is Preset -> colors to stops
            is Custom -> {
                // Toned down towards the overlay, less so the higher the mix.
                val toned = (if (colors.size == 1) colors + colors else colors).map { color ->
                    val amount = if (isDark) 0.5f + 0.25f * (1 - baseMix / 100f) else 0.5f + 0.2f * (1 - baseMix / 100f)
                    ColorUtils.blendARGB(color, overlay, amount)
                }
                toned to (gradientStops.takeIf { it.size == toned.size } ?: evenStops(toned.size))
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            shader = LinearGradient(x0, y0, x1, y1, drawn.toIntArray(), positions.map { it / 100f }.toFloatArray(), Shader.TileMode.CLAMP)
        }
        Canvas(bitmap).apply {
            drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            if (scrim && this@ClientTheme is Preset) drawColor(if (isDark) alpha(BLACK, 0.7f) else alpha(WHITE, 0.8f))
        }
        return bitmap
    }

    // endregion

    /** What the selection screen keeps: just enough to rebuild this. */
    fun toJson(): String = JsonObject().apply {
        addProperty(KEY_SYNCED, synced)
        when (val theme = this@ClientTheme) {
            is Preset -> addProperty(KEY_PRESET, theme.id)
            is Custom -> {
                addProperty(KEY_BASE, theme.base)
                add(KEY_COLORS, JsonArray().apply { theme.colors.forEach { add(it) } })
                add(KEY_STOPS, JsonArray().apply { theme.gradientStops.forEach { add(it) } })
                addProperty(KEY_ANGLE, theme.angle)
                addProperty(KEY_BASE_MIX, theme.baseMix)
            }
        }
    }.toString()

    companion object {
        private val BLACK = 0xFF000000.toInt()
        private val WHITE = 0xFFFFFFFF.toInt()

        /** RN's `GRADIENT_ANGLE_BREAKPOINT`: above it the gradient starts at the top. */
        private const val ANGLE_BREAKPOINT = 128f

        /** The app's canvas surfaces, clear so the gradient shows. */
        private val SURFACES = listOf(
            "colorBackgroundPrimary", "colorBackgroundSecondary", "colorBackgroundTertiary",
            "colorBackgroundMobilePrimary", "colorBackgroundMobileSecondary", "colorPrimary", "colorPrimaryDark", "toolbarColor",
        )

        /** Popups and dialogs are windows of their own, with nothing behind them to show: solid. */
        private val FLOATING = listOf("colorBackgroundFloating", "colorSurface", "notice_popup_bg", "boost_confirmation_card_bg")

        /** RN's floating bar and chat input colors, solid. */
        private val BARS = listOf("colorTabsBackground", "colorChatInputBackground", "colorChatInputActionBackground", "theme_chat_input")

        /** Hover and selection tints keep the base theme's strength, in the theme's color. */
        private val MODIFIERS = listOf(
            "colorBackgroundModifierHover" to 0x1f, "colorBackgroundModifierActive" to 0x29,
            "colorBackgroundModifierSelected" to 0x33, "colorBackgroundModifierStrong" to 0x3d,
            "colorBackgroundModifierAccent" to 0x29, "colorChannelItemSelected" to 0x33,
            "colorGuildFolderBackground" to 0x1f, "colorReactionBackground" to 0x1f,
        )

        /** Every attribute [attrs] sets, for the resource contract test. */
        internal val ATTRIBUTES: List<String>
            get() = SURFACES + FLOATING + BARS + MODIFIERS.map { it.first } + listOf(
                "colorBackgroundSecondaryAlt", "colorBackgroundNestedFloating", "colorBackgroundAccent",
                "colorInputBackground", "colorInteractiveMuted", "colorGuildBarIconBackground",
            )

        private const val KEY_SYNCED = "synced"
        private const val KEY_PRESET = "preset"
        private const val KEY_BASE = "base"
        private const val KEY_COLORS = "colors"
        private const val KEY_STOPS = "stops"
        private const val KEY_ANGLE = "angle"
        private const val KEY_BASE_MIX = "baseMix"

        private val DARKER = ModelUserSettings.THEME_DARKER
        private val LIGHT = ModelUserSettings.THEME_LIGHT

        private fun preset(id: Int, @StringRes name: Int, base: String, angle: Float, vararg stops: Pair<Long, Float>) =
            Preset(id, name, base, stops.map { it.first.toInt() }, stops.map { it.second }, angle)

        /** By id. RN `ClientThemesConstants`; colors are its `BG_GRADIENT_*` tokens. */
        val PRESETS: Map<Int, Preset> = listOf(
            preset(0, R.string.client_theme_mint_apple, LIGHT, 180f, 0xFF56B69F to 6.15f, 0xFF63BC61 to 48.7f, 0xFF9ECA67 to 93.07f),
            preset(1, R.string.client_theme_citrus_sherbert, LIGHT, 180f, 0xFFF3B336 to 31.1f, 0xFFEE8558 to 67.09f),
            preset(2, R.string.client_theme_retro_raincloud, LIGHT, 148.71f, 0xFF3A7CA1 to 5.64f, 0xFF7F7EB9 to 26.38f, 0xFF7F7EB9 to 49.92f, 0xFF3A7CA1 to 73.12f),
            preset(3, R.string.client_theme_hanami, LIGHT, 38.08f, 0xFFEFAAB3 to 3.56f, 0xFFEFD696 to 35.49f, 0xFFA6DAA2 to 68.78f),
            preset(4, R.string.client_theme_sunrise, LIGHT, 154.19f, 0xFF9F4175 to 8.62f, 0xFFC49064 to 48.07f, 0xFFA6953D to 76.04f),
            preset(5, R.string.client_theme_cotton_candy, LIGHT, 180.14f, 0xFFF4ABB8 to 8.5f, 0xFFB1C2FC to 94.28f),
            preset(6, R.string.client_theme_lofi_vibes, LIGHT, 179.52f, 0xFFA4C0F7 to 7.08f, 0xFFA9E4E8 to 34.94f, 0xFFB0E2B8 to 65.12f, 0xFFCFDFA2 to 96.23f),
            preset(7, R.string.client_theme_desert_khaki, LIGHT, 38.99f, 0xFFE7DBD0 to 12.92f, 0xFFDFD0B2 to 32.92f, 0xFFE0D6A3 to 52.11f),
            preset(8, R.string.client_theme_sunset, DARKER, 141.68f, 0xFF48288C to 27.57f, 0xFFDB7F4B to 71.25f),
            preset(9, R.string.client_theme_chroma_glow, DARKER, 128.92f, 0xFF0EB5BF to 3.94f, 0xFF4C0CE0 to 26.1f, 0xFFA308A7 to 39.82f, 0xFF9A53FF to 56.89f, 0xFF218BE0 to 76.45f),
            preset(10, R.string.client_theme_forest, DARKER, 162.27f, 0xFF142215 to 11.2f, 0xFF2D4D39 to 29.93f, 0xFF454C32 to 48.64f, 0xFF5A7C58 to 67.85f, 0xFFA98E4B to 83.54f),
            preset(11, R.string.client_theme_crimson_moon, DARKER, 64.92f, 0xFF950909 to 16.17f, 0xFF000000 to 72f),
            preset(12, R.string.client_theme_midnight_blurple, DARKER, 48.17f, 0xFF5348CA to 11.21f, 0xFF140730 to 61.92f),
            preset(13, R.string.client_theme_mars, DARKER, 170.82f, 0xFF895240 to 14.61f, 0xFF8F4343 to 74.62f),
            preset(14, R.string.client_theme_dusk, DARKER, 180f, 0xFF665069 to 12.84f, 0xFF91A3D1 to 85.99f),
            preset(15, R.string.client_theme_under_the_sea, DARKER, 179.14f, 0xFF647962 to 1.91f, 0xFF588575 to 48.99f, 0xFF6A8482 to 96.35f),
            preset(16, R.string.client_theme_easter_egg, LIGHT, 180f, 0xFF7289DA to 4f, 0xFF4E5D94 to 96f),
            preset(17, R.string.client_theme_retro_storm, DARKER, 148.71f, 0xFF3A7CA1 to 5.64f, 0xFF58579A to 26.38f, 0xFF58579A to 49.92f, 0xFF3A7CA1 to 73.12f),
            preset(18, R.string.client_theme_neon_nights, DARKER, 180f, 0xFF01A89E to 0f, 0xFF7D60BA to 50f, 0xFFB43898 to 100f),
            preset(19, R.string.client_theme_sepia, DARKER, 69.98f, 0xFF857664 to 14.14f, 0xFF5B4421 to 60.35f),
            preset(20, R.string.client_theme_strawberry_lemonade, DARKER, 161.03f, 0xFFAF1A6C to 18.79f, 0xFFC26B20 to 49.76f, 0xFFE7A525 to 80.72f),
            preset(21, R.string.client_theme_aurora, DARKER, 239.16f, 0xFF062053 to 10.39f, 0xFF191FBB to 26.87f, 0xFF13929A to 48.31f, 0xFF218573 to 64.98f, 0xFF051A81 to 92.5f),
            preset(22, R.string.client_theme_blurple_twilight, DARKER, 47.61f, 0xFF2C3FE7 to 11.18f, 0xFF261D83 to 64.54f),
        ).associateBy { it.id }

        /** RN's `BACKGROUND_GRADIENT_PRESETS_MOBILE` order. Easter Egg is only listed while it is the one in use. */
        val PICKER_ORDER = listOf(22, 8, 9, 10, 11, 12, 13, 14, 15, 17, 18, 19, 20, 21, 0, 1, 2, 3, 4, 5, 6, 7)
        const val EASTER_EGG = 16

        fun fromJson(text: String): ClientTheme? = runCatching {
            val json = JsonParser.parseString(text).asJsonObject
            val synced = json.get(KEY_SYNCED)?.asBoolean ?: false
            json.get(KEY_PRESET)?.let { return PRESETS[it.asInt]?.copy(synced = synced) }
            custom(
                base = json.get(KEY_BASE).asString,
                colors = json.getAsJsonArray(KEY_COLORS).map { it.asInt },
                stops = json.getAsJsonArray(KEY_STOPS).map { it.asFloat },
                angle = json.get(KEY_ANGLE).asFloat,
                baseMix = json.get(KEY_BASE_MIX).asInt,
                synced = synced,
            )
        }.getOrNull()

        /** RN's `CustomThemeMobileStore` validity: a base and at least one color. */
        fun custom(base: String, colors: List<Int>, stops: List<Float>, angle: Float, baseMix: Int, synced: Boolean): Custom? =
            if (colors.isEmpty()) null else Custom(base, colors, stops, angle, baseMix.coerceIn(0, 100), synced)

        private fun evenStops(count: Int) = if (count < 2) listOf(0f, 100f) else List(count) { it * 100f / (count - 1) }

        /** chroma's scale: linear RGB between the stops on either side, flat past the ends. */
        internal fun sample(colors: List<Int>, stops: List<Float>, percent: Float): Int {
            if (colors.size == 1) return colors[0]
            if (percent <= stops.first()) return colors.first()
            if (percent >= stops.last()) return colors.last()
            val upper = stops.indexOfFirst { it >= percent }
            val span = stops[upper] - stops[upper - 1]
            val fraction = if (span == 0f) 1f else (percent - stops[upper - 1]) / span
            return ColorUtils.blendARGB(colors[upper - 1], colors[upper], fraction)
        }

        /** chroma's `set("hsl.s"/"hsl.l")`: absolute when given, otherwise scaled. */
        private fun hsl(color: Int, s: Float? = null, l: Float? = null, sScale: Float = 1f, lScale: Float = 1f): Int {
            val values = FloatArray(3)
            ColorUtils.colorToHSL(color, values)
            values[1] = (s ?: (values[1] * sScale)).coerceIn(0f, 1f)
            values[2] = (l ?: (values[2] * lScale)).coerceIn(0f, 1f)
            return ColorUtils.HSLToColor(values)
        }

        private fun alpha(color: Int, alpha: Float) = ColorUtils.setAlphaComponent(color, (alpha * 255).toInt())
    }
}

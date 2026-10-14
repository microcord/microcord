package com.discord.utilities.theme

import com.discord.utilities.theme.ThemeDefinition.Companion.SECTION_ATTRS
import com.discord.utilities.theme.ThemeDefinition.Companion.SECTION_COLORS
import com.discord.utilities.theme.ThemeDefinition.Companion.SECTION_DRAWABLE_TINTS
import com.discord.utilities.theme.ThemeDefinition.Companion.SECTION_SIMPLE_COLORS
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import java.io.StringReader

/**
 * Reads and writes [ThemeDefinition] JSON.
 *
 * Parsing is deliberately forgiving, because the files come from people. Comments are allowed.
 * Aliucord's legacy flat format is converted. A bad entry is skipped with a warning instead of
 * rejecting the theme. Only a document that is not a theme at all is refused.
 *
 * The tree API is used rather than reflective Gson, which never runs Kotlin initialisers.
 */
object ThemeJson {
    const val MAX_CHARS = 1 shl 20
    const val MAX_ENTRIES = 5000
    const val FORMAT_VERSION = 1
    const val DEFAULT_NAME = "Untitled theme"

    class Parsed(val theme: ThemeDefinition, val warnings: List<String>)

    private const val MANIFEST = "manifest"
    private const val BACKGROUND = "background"
    private const val FONTS = "fonts"
    private const val RAWS = "raws"
    private const val MICROCORD = "microcord"
    private const val SYSTEM_COLOR_PREFIX = "system_"
    private val SECTIONS = setOf(
        MANIFEST, BACKGROUND, FONTS, RAWS, SECTION_SIMPLE_COLORS, SECTION_COLORS, SECTION_DRAWABLE_TINTS,
        SECTION_ATTRS, MICROCORD,
    )
    private val MANIFEST_KEYS = setOf("name", "author", "version", "license", "updater")

    /** Aliucord's legacy root keys and the `simple_colors` key each became. */
    private val LEGACY_SIMPLE = mapOf(
        "simple_accent_color" to "accent",
        "simple_bg_color" to "background",
        "simple_bg_secondary_color" to "background_secondary",
        "mention_highlight" to "mention_highlight",
        "active_channel_color" to "active_channel",
        "statusbar_color" to "statusbar",
        "input_background_color" to "input_background",
    )

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** Throws [IllegalArgumentException] when [text] is not a theme. */
    fun parse(text: String, fallbackName: String = DEFAULT_NAME): Parsed {
        require(text.length <= MAX_CHARS) { "Theme is larger than 1 MB" }
        val root = try {
            JsonReader(StringReader(text)).use { reader ->
                reader.setStrictness(Strictness.LENIENT)
                JsonParser.parseReader(reader)
            }
        } catch (e: JsonParseException) {
            throw IllegalArgumentException("Not valid JSON", e)
        }
        require(root.isJsonObject) { "A theme must be a JSON object" }
        val warnings = mutableListOf<String>()
        var json = root.asJsonObject
        if (json.keySet().none { it in SECTIONS }) {
            json = requireNotNull(convertLegacy(json)) { "Not a theme" }
            warnings += "Converted from Aliucord's legacy theme format"
        }
        val entries = listOf(SECTION_SIMPLE_COLORS, SECTION_COLORS, SECTION_ATTRS, SECTION_DRAWABLE_TINTS, FONTS, RAWS)
            .sumOf { (json.get(it) as? JsonObject)?.size() ?: 0 }
        require(entries <= MAX_ENTRIES) { "Theme has more than $MAX_ENTRIES entries" }

        val manifest = json.get(MANIFEST) as? JsonObject ?: JsonObject()
        val microcord = json.get(MICROCORD) as? JsonObject ?: JsonObject()
        val base = microcord.string("base") ?: ThemeDefinition.BASE_DEFAULT
        if (base !in ThemeDefinition.BASES) warnings += "Unknown base theme \"$base\", using the default"
        val transparency = microcord.string("transparency")
        if (transparency != null && ThemeDefinition.Transparency.of(transparency) == null) {
            warnings += "Unknown transparency \"$transparency\""
        }
        val systemColors = LinkedHashMap<String, String>()
        val theme = ThemeDefinition(
            manifest = ThemeDefinition.Manifest(
                name = manifest.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackName,
                author = manifest.string("author") ?: ThemeDefinition.DEFAULT_AUTHOR,
                version = manifest.string("version") ?: ThemeDefinition.DEFAULT_VERSION,
                license = manifest.string("license"),
                updater = manifest.string("updater"),
            ),
            base = base.takeIf { it in ThemeDefinition.BASES } ?: ThemeDefinition.BASE_DEFAULT,
            simpleColors = colorMap(json, SECTION_SIMPLE_COLORS, systemColors, warnings),
            colors = colorMap(json, SECTION_COLORS, systemColors, warnings),
            attrs = colorMap(json, SECTION_ATTRS, systemColors, warnings),
            drawableTints = colorMap(json, SECTION_DRAWABLE_TINTS, systemColors, warnings),
            background = background(json, warnings),
            fonts = stringMap(json, FONTS, warnings),
            raws = stringMap(json, RAWS, warnings),
            transparency = ThemeDefinition.Transparency.of(transparency),
            systemColors = systemColors,
            extras = json.entrySet()
                .filter { it.key !in SECTIONS }
                .associateTo(LinkedHashMap()) { it.key to it.value.deepCopy() },
        )
        return Parsed(theme, warnings)
    }

    /**
     * Aliucord's section order, then ours.
     *
     * Aliucord sections keep signed int colors, because Aliucord's loader throws on anything else.
     * It ignores `attrs` and `microcord`, so the same file still loads there. `attrs` is written as
     * hex for readability.
     */
    fun toJson(theme: ThemeDefinition): String {
        val root = JsonObject()
        root.add(MANIFEST, JsonObject().apply {
            addProperty("name", theme.manifest.name)
            addProperty("author", theme.manifest.author)
            addProperty("version", theme.manifest.version)
            theme.manifest.license?.let { addProperty("license", it) }
            theme.manifest.updater?.let { addProperty("updater", it) }
        })
        theme.background?.let { background ->
            root.add(BACKGROUND, JsonObject().apply {
                addProperty("url", background.url)
                addProperty("overlay_alpha", background.overlayAlpha)
                if (background.blurRadius > 0f) addProperty("blur_radius", background.blurRadius)
            })
        }
        root.add(FONTS, strings(theme.fonts))
        root.add(RAWS, strings(theme.raws))
        root.add(SECTION_SIMPLE_COLORS, colors(theme, SECTION_SIMPLE_COLORS, theme.simpleColors) { JsonPrimitive(it) })
        root.add(SECTION_COLORS, colors(theme, SECTION_COLORS, theme.colors) { JsonPrimitive(it) })
        root.add(SECTION_DRAWABLE_TINTS, colors(theme, SECTION_DRAWABLE_TINTS, theme.drawableTints) { JsonPrimitive(it) })
        root.add(SECTION_ATTRS, colors(theme, SECTION_ATTRS, theme.attrs) { JsonPrimitive(ThemeColorParser.toHex(it)) })
        root.add(MICROCORD, JsonObject().apply {
            addProperty("formatVersion", FORMAT_VERSION)
            addProperty("base", theme.base)
            theme.transparency?.let { addProperty("transparency", it.wire) }
        })
        theme.extras.forEach { (key, value) -> if (!root.has(key)) root.add(key, value) }
        return gson.toJson(root)
    }

    private fun colors(theme: ThemeDefinition, section: String, values: Map<String, Int>, encode: (Int) -> JsonElement) =
        JsonObject().apply {
            values.forEach { (key, value) -> add(key, encode(value)) }
            theme.systemColors.forEach { (path, name) ->
                if (path.substringBefore('.') == section) addProperty(path.substringAfter('.'), name)
            }
        }

    private fun strings(values: Map<String, String>) = JsonObject().apply { values.forEach { (k, v) -> addProperty(k, v) } }

    private fun colorMap(
        json: JsonObject,
        section: String,
        systemColors: MutableMap<String, String>,
        warnings: MutableList<String>,
    ): Map<String, Int> {
        val obj = section(json, section, warnings) ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for ((key, value) in obj.entrySet()) {
            val primitive = (value as? JsonPrimitive)?.takeUnless { it.isBoolean }
            val text = primitive?.asString
            when {
                text != null && text.startsWith(SYSTEM_COLOR_PREFIX) -> systemColors["$section.$key"] = text
                else -> {
                    val color = text?.let(ThemeColorParser::parse)
                    if (color == null) warnings += "$section.$key: $value is not a color" else out[key] = color
                }
            }
        }
        return out
    }

    private fun stringMap(json: JsonObject, section: String, warnings: MutableList<String>): Map<String, String> {
        val obj = section(json, section, warnings) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((key, value) in obj.entrySet()) {
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.asString?.trim()
            if (text.isNullOrEmpty()) warnings += "$section.$key is not a link" else out[key] = text
        }
        return out
    }

    private fun background(json: JsonObject, warnings: MutableList<String>): ThemeDefinition.Background? {
        val obj = section(json, BACKGROUND, warnings) ?: return null
        val url = obj.string("url")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val alpha = obj.number("overlay_alpha")?.toInt() ?: ThemeDefinition.DEFAULT_OVERLAY_ALPHA
        if (alpha !in 0..0xFF) warnings += "background.overlay_alpha must be 0-255"
        val blur = obj.number("blur_radius")?.toFloat() ?: 0f
        if (blur !in 0f..ThemeDefinition.MAX_BLUR_RADIUS) warnings += "background.blur_radius must be 0-25"
        return ThemeDefinition.Background(
            url = url,
            overlayAlpha = alpha.coerceIn(0, 0xFF),
            blurRadius = blur.coerceIn(0f, ThemeDefinition.MAX_BLUR_RADIUS),
        )
    }

    private fun section(json: JsonObject, section: String, warnings: MutableList<String>): JsonObject? {
        val element = json.get(section) ?: return null
        return element as? JsonObject ?: run {
            warnings += "\"$section\" is not an object and was skipped"
            null
        }
    }

    /** Aliucord's pre-1.0 flat format; `null` when nothing in it is recognisable as a theme. */
    private fun convertLegacy(old: JsonObject): JsonObject? {
        val manifest = JsonObject()
        val background = JsonObject()
        val fonts = JsonObject()
        val simple = JsonObject()
        val colors = JsonObject()
        val tints = JsonObject()
        for ((key, value) in old.entrySet()) {
            when {
                key in MANIFEST_KEYS -> manifest.add(key, value)
                key == "background_url" -> background.add("url", value)
                key == "background_transparency" -> background.add("overlay_alpha", value)
                key == "font" -> fonts.add(ThemeDefinition.ALL_FONTS, value)
                key.startsWith("font_") -> fonts.add(key.removePrefix("font_"), value)
                key in LEGACY_SIMPLE -> simple.add(LEGACY_SIMPLE.getValue(key), value)
                key.startsWith("drawablecolor_") -> tints.add(key.removePrefix("drawablecolor_"), value)
                key.startsWith("color_") -> colors.add(key.removePrefix("color_"), value)
            }
        }
        val sections = linkedMapOf<String, JsonElement>(
            BACKGROUND to background, FONTS to fonts, SECTION_SIMPLE_COLORS to simple, SECTION_COLORS to colors,
            SECTION_DRAWABLE_TINTS to tints,
        ).filterValues { (it as JsonObject).size() > 0 }
        if (sections.isEmpty()) return null
        return JsonObject().apply {
            add(MANIFEST, manifest)
            sections.forEach { (key, value) -> add(key, value) }
        }
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.number(key: String): Double? =
        (get(key) as? JsonPrimitive)?.let { it.asString.toDoubleOrNull() }
}

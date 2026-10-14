package com.discord.utilities.theme

import android.content.res.Resources
import android.util.TypedValue
import com.discord.R
import java.io.File
import java.util.zip.ZipFile

/**
 * Turns resolved overrides into the resource table [ThemeRuntime] loads, plus the files it serves.
 *
 * - **Theme attributes** become items of the [R.style.ThemeOverlay_Microcord_Runtime] placeholder,
 *   which the table replaces wholesale and every themed context applies on top of its base theme.
 * - **Palette colors** become plain color entries. Color state lists are skipped: one color cannot
 *   stand in for their states.
 * - **Drawable tints** point the drawable at a tinted copy of its compiled XML ([BinaryXml]).
 *   PNGs get a `<bitmap>` wrapper around a copy of the original image, added as a new entry so
 *   the wrapper does not load itself. Selectors and layer lists tint the drawables they reference
 *   through new tinted entries, leaving the shared originals alone.
 * - **Fonts and sounds** point at the theme's files.
 *
 * Files land in [outDir] and are served under [ASSET_PREFIX]. Names that do not exist are skipped
 * with a warning.
 */
internal class ThemeTableBuilder(
    private val resources: Resources,
    private val packageName: String,
    private val apkPath: String,
    private val outDir: File,
) {

    class Table(val bytes: ByteArray, val hasOverlay: Boolean, val warnings: List<String>)

    private val entries = mutableListOf<ArscWriter.Entry>()
    private val warnings = mutableListOf<String>()
    private val usedNames = mutableSetOf<String>()
    private var nextDrawableEntry = -1
    private val ids = BinaryXml.Ids(
        drawable = android.R.attr.drawable,
        src = android.R.attr.src,
        tint = android.R.attr.tint,
        tintMode = android.R.attr.tintMode,
    )

    fun build(resolved: ThemeResolver.Resolved, fonts: Map<String, File>, raws: Map<String, File>): Table {
        warnings += resolved.warnings
        outDir.mkdirs()
        val items = LinkedHashMap<Int, Int>()
        resolved.attrs.forEach { (name, color) ->
            val id = attrId(name)
            if (id == 0) warnings += "Unknown theme attribute $name" else items[id] = color
        }
        if (items.isNotEmpty()) entries += entry(R.style.ThemeOverlay_Microcord_Runtime, ArscWriter.Value.Style(items))
        resolved.colors.forEach { (name, color) ->
            val id = resources.getIdentifier(name, "color", packageName)
            when {
                id == 0 -> warnings += "Unknown color $name"
                !isPlainColor(id) -> warnings += "$name is a state list and was skipped"
                else -> entries += entry(id, ArscWriter.Value.Color(color))
            }
        }
        if (resolved.tints.isNotEmpty()) {
            ZipFile(apkPath).use { apk -> resolved.tints.forEach { (name, color) -> tint(apk, name, color) } }
        }
        fileEntries("font", fonts)
        fileEntries("raw", raws)
        entries += entry(R.color.theme_runtime_sentinel, ArscWriter.Value.Color(SENTINEL))
        val bytes = ArscWriter.write(packageName, entries)
        return Table(bytes, items.isNotEmpty(), warnings)
    }

    private fun fileEntries(type: String, files: Map<String, File>) = files.forEach { (name, file) ->
        val id = resources.getIdentifier(name, type, packageName)
        if (id == 0) {
            warnings += "Unknown $type $name"
            return@forEach
        }
        // A served `.xml` would be parsed as a font family or as XML, whatever the bytes are.
        val extension = when {
            type == "font" -> if (file.name.endsWith(".otf", ignoreCase = true)) ".otf" else ".ttf"
            else -> ".audio"
        }
        val copy = writeAsset("$type-$name$extension", file.readBytes())
        entries += entry(id, ArscWriter.Value.File(copy))
    }

    private fun tint(apk: ZipFile, name: String, color: Int) {
        val id = resources.getIdentifier(name, "drawable", packageName)
        if (id == 0) {
            warnings += "Unknown drawable $name"
            return
        }
        val path = tintedFile(apk, id, color, depth = 0)
        if (path == null) warnings += "$name could not be tinted" else entries += entry(id, ArscWriter.Value.File(path), ArscWriter.ANY_DENSITY_CONFIGS)
    }

    /** A served file showing drawable [id] tinted [color], or `null` when it cannot be tinted. */
    private fun tintedFile(apk: ZipFile, id: Int, color: Int, depth: Int): String? {
        val value = TypedValue()
        runCatching { resources.getValue(id, value, true) }.getOrNull() ?: return null
        val source = value.string?.toString()?.takeIf { value.type == TypedValue.TYPE_STRING } ?: return null
        val bytes = apk.getEntry(source)?.let { entry -> apk.getInputStream(entry).use { it.readBytes() } } ?: return null
        val key = "${resources.getResourceEntryName(id)}-${Integer.toHexString(color)}"
        return when {
            source.endsWith(".xml") -> {
                val tinted = runCatching {
                    BinaryXml.tint(bytes, color, ids) { child ->
                        if (depth < MAX_DEPTH && typeOf(child) == "drawable") tintedEntry(apk, child, color, depth + 1) else null
                    }
                }.getOrNull() ?: return null
                writeAsset("$key.xml", tinted)
            }
            source.endsWith(".png") || source.endsWith(".webp") || source.endsWith(".jpg") -> {
                // The wrapper cannot point back at [id]: the override would load itself.
                val image = writeAsset("$key-src." + source.substringAfterLast('.'), bytes)
                val src = newDrawableEntry("${key}_src", ArscWriter.Value.File(image), listOf(ArscWriter.Config(0, value.density)))
                writeAsset("$key.xml", BinaryXml.bitmap(src, ninePatch = source.endsWith(".9.png"), color = color, ids = ids))
            }
            else -> null
        }
    }

    /** A new drawable entry showing [id] tinted, so a container can use it without changing [id] everywhere. */
    private fun tintedEntry(apk: ZipFile, id: Int, color: Int, depth: Int): Int? {
        val file = tintedFile(apk, id, color, depth) ?: return null
        return newDrawableEntry("${resources.getResourceEntryName(id)}_tinted", ArscWriter.Value.File(file), ArscWriter.ANY_DENSITY_CONFIGS)
    }

    /** New drawable ids start past the app's own, which aapt2 numbers densely. */
    private fun newDrawableEntry(name: String, value: ArscWriter.Value, configs: List<ArscWriter.Config>): Int {
        val type = R.drawable.ic_chevron_right_grey_12dp and 0xFFFF0000.toInt()
        if (nextDrawableEntry < 0) nextDrawableEntry = firstFreeEntry(type)
        var key = "microcord_$name"
        var suffix = 2
        while (!usedNames.add(key)) key = "microcord_${name}_${suffix++}"
        val id = type or nextDrawableEntry++
        entries += ArscWriter.Entry(id, "drawable", key, value, configs)
        return id
    }

    private fun firstFreeEntry(type: Int): Int {
        var low = R.drawable.ic_chevron_right_grey_12dp and 0xFFFF
        var high = ArscWriter.MAX_ENTRY_ID
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (exists(type or mid)) low = mid else high = mid - 1
        }
        return low + 1
    }

    private fun exists(id: Int) = runCatching { resources.getResourceName(id) }.isSuccess

    private fun typeOf(id: Int) = runCatching { resources.getResourceTypeName(id) }.getOrNull()

    /** Writes [bytes] as [name] into [outDir]; returns the path the table serves it under. */
    private fun writeAsset(name: String, bytes: ByteArray): String {
        val safe = name.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }.joinToString("")
        File(outDir, safe).writeBytes(bytes)
        return ASSET_PREFIX + safe
    }

    private fun attrId(name: String): Int =
        if (name.startsWith(ANDROID_PREFIX)) {
            resources.getIdentifier(name.substring(ANDROID_PREFIX.length), "attr", "android")
        } else {
            resources.getIdentifier(name, "attr", packageName)
        }

    private fun entry(id: Int, value: ArscWriter.Value, configs: List<ArscWriter.Config> = ArscWriter.DEFAULT_CONFIGS) =
        ArscWriter.Entry(id, resources.getResourceTypeName(id), resources.getResourceEntryName(id), value, configs)
            .also { usedNames += it.keyName }

    private fun isPlainColor(id: Int): Boolean {
        val value = TypedValue()
        resources.getValue(id, value, true)
        return value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT
    }

    companion object {
        private const val ANDROID_PREFIX = "android:"
        private const val MAX_DEPTH = 3

        /** Where the table's files live, as the loader's assets provider is asked for them. */
        const val ASSET_PREFIX = "res/microcord/"

        /** Written into every table. Reading it back proves the device actually honours the table. */
        const val SENTINEL = 0x5A0A1E57
    }
}

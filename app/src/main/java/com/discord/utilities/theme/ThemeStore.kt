package com.discord.utilities.theme

import java.io.File
import java.security.SecureRandom

/**
 * Custom themes on disk.
 *
 * Layout under [dir]:
 * - `<id>.json`: one file per theme.
 * - `active`: the enabled themes' ids, one per line, lowest layer first.
 * - Two marker files used by the boot guard.
 *
 * Kept in files rather than preferences. Logout clears both preference files, and an account
 * switch clears user-scoped keys, but a theme belongs to the device, not the account. Ids are
 * generated here. Nothing read from inside a theme file is ever used as a path.
 */
class ThemeStore(val dir: File) {

    class Stored(val id: String, val theme: ThemeDefinition)

    /** Every readable theme, by name. A file that no longer parses is skipped, not deleted. */
    fun list(): List<Stored> = dir.listFiles().orEmpty()
        .mapNotNull { file ->
            val id = file.name.removeSuffix(JSON).takeIf { file.name.endsWith(JSON) && isValidId(it) }
            id?.let { read(it) }?.let { Stored(id, it) }
        }
        .sortedBy { it.theme.manifest.name.lowercase() }

    fun read(id: String): ThemeDefinition? =
        readText(id)?.let { text -> runCatching { ThemeJson.parse(text).theme }.getOrNull() }

    /** The file as stored, unparsed. */
    fun readText(id: String): String? =
        if (isValidId(id)) runCatching { themeFile(id).readText() }.getOrNull() else null

    /** Writes [theme] under [id], or under a new id; returns the id used. */
    fun save(theme: ThemeDefinition, id: String? = null): String {
        val target = id ?: newId()
        require(isValidId(target)) { "Invalid theme id" }
        write(themeFile(target), ThemeJson.toJson(theme))
        return target
    }

    fun delete(id: String) {
        if (!isValidId(id)) return
        if (id in activeIds) activeIds = activeIds - id
        themeFile(id).delete()
    }

    /**
     * The enabled themes, applied in this order with later ones winning, as Aliucord layers several
     * enabled themes. Empty means the built-in themes. Ids whose file is gone are left out.
     */
    var activeIds: List<String>
        get() = File(dir, ACTIVE).takeIf { it.isFile }?.readLines().orEmpty().map { it.trim() }
            .filter { isValidId(it) && themeFile(it).isFile }.distinct()
        set(value) {
            require(value.all(::isValidId)) { "Invalid theme id" }
            if (value.isEmpty()) File(dir, ACTIVE).delete() else write(File(dir, ACTIVE), value.distinct().joinToString(LINE))
        }

    /**
     * The boot guard: themed launches in a row that never proved healthy.
     *
     * One is not enough to blame the theme; the app may simply have been swiped away early.
     */
    var unconfirmedLaunches: Int
        get() = File(dir, BOOTING).takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0
        set(value) {
            if (value > 0) write(File(dir, BOOTING), value.toString()) else File(dir, BOOTING).delete()
        }

    /** Set when the guard turned a theme off; the Themes screen reads it once. */
    fun markDisabledByGuard() = write(File(dir, GUARD_NOTICE), "")

    fun consumeDisabledByGuard(): Boolean = File(dir, GUARD_NOTICE).delete()

    private fun themeFile(id: String) = File(dir, id + JSON)

    /** Temp file plus rename, so a crash mid-write never leaves a half theme behind. */
    private fun write(file: File, text: String) {
        dir.mkdirs()
        val temp = File(dir, file.name + ".tmp")
        temp.writeText(text)
        // Android's rename replaces atomically; the delete is only for hosts whose rename refuses to.
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "Could not write ${file.name}" }
        }
    }

    companion object {
        private const val JSON = ".json"
        private const val ACTIVE = "active"
        private const val LINE = "\n"
        private const val BOOTING = ".booting"
        private const val GUARD_NOTICE = ".guard_notice"
        private const val ID_PREFIX = "t-"
        private const val ID_DIGITS = 12
        private val random = SecureRandom()

        fun newId(): String = ID_PREFIX + (1..ID_DIGITS).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString("")

        fun isValidId(id: String): Boolean =
            id.length == ID_PREFIX.length + ID_DIGITS && id.startsWith(ID_PREFIX) &&
                id.substring(ID_PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' }
    }
}

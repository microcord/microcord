package com.discord.utilities.theme

import androidx.annotation.WorkerThread

/**
 * Aliucord's `manifest.updater`: a link to the latest copy of a theme. A newer `manifest.version`
 * there replaces the stored theme.
 */
object ThemeUpdater {

    /** Downloads a theme from [url]; throws when it cannot be fetched or is not a theme. */
    @WorkerThread
    fun download(url: String): ThemeJson.Parsed =
        ThemeJson.parse(ThemeAssets.fetch(url, 4 * ThemeJson.MAX_CHARS).toString(Charsets.UTF_8))

    /** The newer copy of the stored theme [id], already saved over it, or `null` when it is current. */
    @WorkerThread
    fun update(store: ThemeStore, id: String): ThemeDefinition? {
        val current = store.read(id) ?: return null
        val url = current.manifest.updater ?: return null
        val latest = download(url).theme
        if (!isNewer(latest.manifest.version, current.manifest.version)) return null
        store.save(latest, id)
        return latest
    }

    /** Compares dotted versions number by number, as Aliucord does; `1.10` is newer than `1.9`. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.trim().toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.trim().toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

package com.discord.utilities.theme

import android.app.Application
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.content.res.Resources
import android.content.res.loader.AssetsProvider
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import com.discord.R
import com.discord.app.AppLog
import java.io.File
import java.security.MessageDigest

/**
 * Applies the enabled custom themes to every themed context, without hooking the framework.
 *
 * One [ResourcesLoader] is added to the application's resources the first time a theme is on.
 * Activities created afterwards inherit it. With no theme enabled, startup reads one file and
 * nothing else runs. Enabling themes generates a small resource table ([ThemeTableBuilder])
 * and the files it points at. The loader serves both on top of the APK's own resources:
 * - palette colors, tinted drawables, fonts and sounds are replaced in place;
 * - theme attributes land in [R.style.ThemeOverlay_Microcord_Runtime], which [applyOverlay] layers
 *   over each base theme.
 *
 * Several themes can be enabled at once. They layer like Aliucord's: later ones win key by key.
 *
 * Views that already exist keep their colors, so a change recreates the activity ([generation]).
 *
 * Building a table costs a few hundred resource-name lookups: about 150 ms on a current phone, more
 * with icon tints. Tables are therefore cached, keyed by:
 * - the theme files and their downloaded assets;
 * - the mapping and writer versions;
 * - the installed APK.
 *
 * A startup with unchanged themes only maps the cached files, and never touches the network.
 *
 * `ResourcesLoader` is API 30. Below that, custom themes are unavailable and the built-in themes
 * are untouched.
 *
 * A launch with a theme applied counts as on probation until its first screen has stayed up for a
 * few seconds ([confirmHealthy]). After two such launches in a row die early, the themes are
 * turned off.
 */
object ThemeRuntime {
    private const val DIR = "themes"
    private const val HEALTHY_AFTER_MS = 3_000L
    private const val MAX_UNCONFIRMED_LAUNCHES = 2
    private const val SYSTEM_COLOR = "\"system_"

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    val isSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Bumped on every change; an activity created under an older value recreates itself. */
    @Volatile
    var generation = 0
        private set

    /** The built-in theme the enabled custom themes sit on, or `null` when none is enabled. */
    @Volatile
    var activeBase: String? = null
        private set

    /** Problems from the last table build: names that do not exist, skipped state lists. */
    @Volatile
    var lastWarnings: List<String> = emptyList()
        private set

    @Volatile
    private var overlayActive = false
    private var app: Application? = null
    private var backend: Backend? = null
    private var healthCheckPosted = false
    private var launchCounted = false

    fun store(context: Context) = ThemeStore(File(context.applicationContext.filesDir, DIR))

    /** Called once from `App.onCreate`, before any activity exists. Never throws. */
    @MainThread
    fun install(application: Application) {
        if (!isSupported) return
        app = application
        val store = store(application)
        try {
            // Without an enabled theme nothing else runs: no loader, no guard file.
            val ids = store.activeIds
            if (ids.isEmpty()) return
            val unconfirmed = store.unconfirmedLaunches
            if (unconfirmed >= MAX_UNCONFIRMED_LAUNCHES) {
                // The last themed launches never got as far as confirmHealthy.
                store.unconfirmedLaunches = 0
                store.activeIds = emptyList()
                store.markDisabledByGuard()
                AppLog.w("ThemeRuntime", "Previous launches with a custom theme did not survive; themes disabled")
                return
            }
            activate(application, store, ids)
        } catch (e: Throwable) {
            AppLog.e("ThemeRuntime", "Could not apply the custom theme at startup", e)
            store.activeIds = emptyList()
            deactivate()
        }
    }

    /**
     * Enables exactly [ids], lowest layer first; empty returns to the built-in themes.
     *
     * Throws when the themes cannot be applied; the built-in themes are back on then. The caller recreates the
     * visible activity. Others recreate when they next resume. Downloads nothing: fetch a theme's
     * assets with [ThemeAssets.fetchMissing] first.
     */
    @MainThread
    fun enable(context: Context, ids: List<String>) {
        if (!isSupported) throw IllegalStateException("Custom themes need Android 11")
        val store = store(context)
        if (ids.isEmpty()) {
            store.activeIds = emptyList()
            deactivate()
        } else {
            try {
                activate(context.applicationContext, store, ids)
            } catch (e: Exception) {
                store.unconfirmedLaunches = 0
                store.activeIds = emptyList()
                deactivate()
                generation++
                throw e
            }
            store.activeIds = ids
        }
        generation++
    }

    /**
     * Builds the tables for [ids] ahead of [enable], off the main thread, so turning themes on only
     * maps finished files. Skipped when this process cannot see the APK's resources without the
     * active theme in them; [enable] then builds.
     */
    @WorkerThread
    fun prepare(context: Context, ids: List<String>) {
        if (!isSupported || ids.isEmpty()) return
        val clean = context.createPackageContext(context.packageName, 0)
        if (clean.resources.getColor(R.color.theme_runtime_sentinel, null) == ThemeTableBuilder.SENTINEL) return
        val store = store(context)
        val texts = ids.map { id -> store.readText(id) ?: return }
        val dir = File(cacheDir(context), cacheKey(context, texts))
        if (!File(dir, META).isFile) build(clean, texts, dir)
    }

    /**
     * Counts a themed launch that reached the UI, once per process; [confirmHealthy] clears it.
     * Processes started for a notification action or a service never show a screen, so they
     * prove nothing either way.
     */
    @MainThread
    fun onActivityCreated(context: Context) {
        if (!isSupported || launchCounted || activeBase == null) return
        launchCounted = true
        val store = store(context)
        store.unconfirmedLaunches = store.unconfirmedLaunches + 1
    }

    /** Layers the enabled themes' attributes over [theme]. A no-op when no theme is enabled. */
    fun applyOverlay(theme: Resources.Theme) {
        if (overlayActive) theme.applyStyle(R.style.ThemeOverlay_Microcord_Runtime, true)
    }

    /** Called when an activity resumes; ends the probation of a themed launch once it has stayed up. */
    @MainThread
    fun confirmHealthy(context: Context) {
        if (!isSupported || healthCheckPosted || activeBase == null) return
        val store = store(context)
        if (store.unconfirmedLaunches == 0) return
        healthCheckPosted = true
        Handler(Looper.getMainLooper()).postDelayed({
            healthCheckPosted = false
            store.unconfirmedLaunches = 0
        }, HEALTHY_AFTER_MS)
    }

    /** What startup needs about a built table, without parsing any theme. */
    private class Meta(
        val base: String,
        val hasOverlay: Boolean,
        val transparency: ThemeDefinition.Transparency,
        val warnings: List<String>,
    ) {
        fun encode() = (listOf(base, if (hasOverlay) "1" else "0", transparency.wire) + warnings).joinToString(LINE)

        companion object {
            fun decode(text: String): Meta? {
                val lines = text.split(LINE)
                if (lines.size < 3) return null
                val transparency = ThemeDefinition.Transparency.of(lines[2]) ?: return null
                return Meta(lines[0], lines[1] == "1", transparency, lines.drop(3))
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun activate(context: Context, store: ThemeStore, ids: List<String>) {
        // Added on first use: activities created afterwards inherit it, and the ones already open
        // recreate on the generation change.
        val backend = backend ?: Backend(checkNotNull(app) { "ThemeRuntime is not installed" }).also { backend = it }
        val texts = ids.map { id -> requireNotNull(store.readText(id)) { "Theme $id could not be read" } }
        val key = cacheKey(context, texts)
        val dir = File(cacheDir(context), key)
        val meta = File(dir, META).takeIf { it.isFile }?.readText()?.let(Meta::decode) ?: run {
            // The build reads the APK's own drawables and colors, so the active theme must be out of the way.
            deactivate()
            build(context, texts, dir)
        }
        backend.load(File(dir, TABLE), File(dir, FILES))
        if (context.resources.getColor(R.color.theme_runtime_sentinel, null) != ThemeTableBuilder.SENTINEL) {
            deactivate()
            cacheDir(context).deleteRecursively()
            throw IllegalStateException("This device ignored the theme's resource table")
        }
        // Only now that this build is in use can the older ones go.
        cacheDir(context).listFiles()?.forEach { if (it != dir) it.deleteRecursively() }
        overlayActive = meta.hasOverlay
        activeBase = meta.base
        lastWarnings = meta.warnings
        ThemeWallpaper.set(File(dir, WALLPAPER).takeIf { it.isFile }, meta.transparency)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun deactivate() {
        backend?.clear()
        overlayActive = false
        activeBase = null
        lastWarnings = emptyList()
        ThemeWallpaper.set(null, ThemeDefinition.Transparency.NONE)
    }

    private fun cacheDir(context: Context) = File(context.codeCacheDir, DIR)

    /**
     * Resource ids change with every APK, so the installed APK is part of the key. So are:
     * - the downloaded assets, so a file that arrives later is picked up;
     * - the device's Material You colors, when a theme uses them.
     */
    private fun cacheKey(context: Context, texts: List<String>): String {
        val apk = File(context.applicationInfo.sourceDir)
        val digest = MessageDigest.getInstance("SHA-256")
        texts.forEach { digest.update(it.toByteArray()); digest.update(0) }
        digest.update("${AliucordMapping.VERSION}|${ArscWriter.VERSION}|${apk.lastModified()}|${apk.length()}".toByteArray())
        ThemeAssets.dir(context).listFiles()?.sortedBy { it.name }?.forEach { digest.update("${it.name}:${it.length()}|".toByteArray()) }
        if (texts.any { SYSTEM_COLOR in it }) SystemColors.all(context).forEach { digest.update(it.toString().toByteArray()) }
        return digest.digest().take(8).joinToString("") { Integer.toHexString((it.toInt() and 0xFF) or 0x100).substring(1) }
    }

    /**
     * Builds the table and its files into [dir]. Files are written once under their key and never
     * rewritten: a loaded table must not change while it is mapped. [context]'s resources must not
     * contain a theme, since the build copies the APK's own drawables and colors.
     */
    private fun build(context: Context, texts: List<String>, dir: File): Meta {
        val theme = ThemeDefinition.layer(texts.map { ThemeJson.parse(it).theme })
            .withSystemColors { SystemColors.resolve(context, it) }
        val staging = File(dir.parentFile, dir.name + ".tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        val fonts = ThemeDefinition.FONTS.mapNotNull { font ->
            val reference = theme.fonts[font] ?: theme.fonts[ThemeDefinition.ALL_FONTS] ?: return@mapNotNull null
            ThemeAssets.local(context, reference)?.let { font to it }
        }.toMap()
        val raws = theme.raws.filterKeys { it in ThemeDefinition.SOUNDS }
            .mapNotNull { (name, reference) -> ThemeAssets.local(context, reference)?.let { name to it } }.toMap()
        val built = ThemeTableBuilder(context.resources, context.packageName, context.applicationInfo.sourceDir, File(staging, FILES))
            .build(ThemeLinks.link(ThemeResolver.resolve(theme), ThemeLinks.readBaseColors(context, theme.base)), fonts, raws)
        File(staging, TABLE).writeBytes(built.bytes)
        val transparency = theme.background
            ?.let { background -> ThemeAssets.local(context, background.url)?.let { background to it } }
            ?.takeIf { (background, image) -> runCatching { ThemeWallpaper.prepare(image, background, File(staging, WALLPAPER)) }.getOrDefault(false) }
            ?.let { theme.effectiveTransparency } ?: ThemeDefinition.Transparency.NONE
        val missing = ThemeAssets.references(theme).filter { ThemeAssets.local(context, it) == null }
        val meta = Meta(theme.base, built.hasOverlay, transparency, built.warnings + missing.map { "Not downloaded yet: $it" })
        File(staging, META).writeText(meta.encode())
        dir.deleteRecursively()
        check(staging.renameTo(dir)) { "Could not store the theme table" }
        return meta
    }

    /** Material You colors by `android.R.color` name; they exist from Android 12. */
    private object SystemColors {
        private val names by lazy {
            android.R.color::class.java.fields.map { it.name }.filter { it.startsWith("system_") }.sorted()
        }

        fun resolve(context: Context, name: String): Int? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            return runCatching { context.getColor(android.R.color::class.java.getField(name).getInt(null)) }.getOrNull()
        }

        fun all(context: Context): List<Int> = names.mapNotNull { resolve(context, it) }
    }

    /**
     * The API 30 half.
     *
     * Holds the one loader and its current provider. They stay strongly reachable: on Android 11 a
     * finalized provider can close assets that are still in use.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private class Backend(application: Application) {
        private val loader = ResourcesLoader()
        private var provider: ResourcesProvider? = null

        init {
            application.resources.addLoaders(loader)
        }

        /** `loadFromTable` duplicates the descriptor, so ours can be closed straight away. */
        fun load(table: File, files: File) {
            val next = ParcelFileDescriptor.open(table, ParcelFileDescriptor.MODE_READ_ONLY).use {
                ResourcesProvider.loadFromTable(it, Files(files))
            }
            val previous = provider
            loader.setProviders(listOf(next))
            provider = next
            previous?.let { runCatching { it.close() } }
        }

        fun clear() {
            loader.clearProviders()
            provider?.let { runCatching { it.close() } }
            provider = null
        }
    }

    /** Serves the table's files: drawables, fonts and sounds the table points at. */
    @RequiresApi(Build.VERSION_CODES.R)
    private class Files(private val dir: File) : AssetsProvider {
        override fun loadAssetFd(path: String, accessMode: Int): AssetFileDescriptor? {
            val name = path.removePrefix(ThemeTableBuilder.ASSET_PREFIX)
            if (name == path || name.isEmpty() || '/' in name || name.startsWith(".")) return null
            val file = File(dir, name).takeIf { it.isFile } ?: return null
            return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, file.length())
        }
    }

    private const val TABLE = "table.arsc"
    private const val META = "meta"
    private const val FILES = "files"
    private const val WALLPAPER = "wallpaper.jpg"
    private const val LINE = "\n"
}

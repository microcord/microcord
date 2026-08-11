package com.discord.stores

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.core.content.edit
import com.discord.models.domain.ModelUserSettings
import com.discord.models.domain.auth.ModelLoginResult
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.cache.SharedPreferencesProvider
import rx.Observable

class StoreUserSettingsSystem(
    private val onFontScaleUpdated: (Int) -> Unit,
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    private val preferences: SharedPreferences = SharedPreferencesProvider.get(),
    private val sessionDurablePreferences: SharedPreferences = SharedPreferencesProvider.getPrefsSessionDurable(),
) : StoreV2() {
    data class Settings(val theme: String, val _locale: String?, val fontScale: Int) {
        val locale: String get() = _locale ?: ModelUserSettings.getDefaultLocale()
    }

    private lateinit var settings: Settings
    private var defaultTheme = ModelUserSettings.THEME_LIGHT

    fun getTheme() = settings.theme

    /**
     * Light is the only light theme; Material You inherits the dark palette and none of them follow
     * the OS night mode, so `Configuration.UI_MODE_NIGHT_MASK` says nothing about how the app looks.
     */
    fun isDarkTheme() = settings.theme != ModelUserSettings.THEME_LIGHT

    fun getLocale() = settings.locale
    fun getFontScale() = settings.fontScale
    fun getIsThemeSyncEnabled() = sessionDurablePreferences.getBoolean(KEY_THEME_SYNC, true)
    fun getIsLocaleSyncEnabled() = preferences.getBoolean(KEY_LOCALE_SYNC, true)
    fun observeSettings(sample: Boolean): Observable<Settings> {
        val observable = observationDeck.connectRx(this, generator = { settings })
        return (if (sample) observable.throttleLast(1500, java.util.concurrent.TimeUnit.MILLISECONDS) else observable)
            .distinctUntilChanged()
    }

    override fun init(context: Context) {
        val defaultTheme = if (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) {
            ModelUserSettings.THEME_DARK
        } else ModelUserSettings.THEME_LIGHT
        restore(defaultTheme)
    }

    internal fun restore(defaultTheme: String = ModelUserSettings.THEME_LIGHT) {
        this.defaultTheme = defaultTheme
        val stored = sessionDurablePreferences.getString(KEY_THEME, defaultTheme) ?: defaultTheme
        // Without this an install that had the AMOLED theme on falls through to Ash, since nothing
        // answers to the old name any more.
        val theme = if (stored == ModelUserSettings.THEME_LEGACY_MIDNIGHT) {
            ModelUserSettings.THEME_MIDNIGHT.also { migrated ->
                sessionDurablePreferences.edit { putString(KEY_THEME, migrated) }
            }
        } else {
            stored
        }
        settings = Settings(
            theme,
            preferences.getString(KEY_LOCALE, null),
            preferences.getInt(KEY_FONT_SCALE, -1),
        )
    }

    fun setTheme(theme: String, apiSync: Boolean, onRequestApiSync: (() -> Unit)? = null) = dispatcher.schedule {
        require(!getIsThemeSyncEnabled() || !apiSync || onRequestApiSync != null) { "API callback required." }
        onRequestApiSync?.takeIf { getIsThemeSyncEnabled() && apiSync }?.invoke()
        settings = settings.copy(theme = theme)
        sessionDurablePreferences.edit { putString(KEY_THEME, theme) }
        markChanged()
    }

    fun setLocale(locale: String?, apiSync: Boolean, onRequestApiSync: (() -> Unit)? = null) = dispatcher.schedule {
        require(!getIsLocaleSyncEnabled() || !apiSync || onRequestApiSync != null) { "API callback required." }
        onRequestApiSync?.takeIf { getIsLocaleSyncEnabled() && apiSync }?.invoke()
        settings = settings.copy(_locale = locale)
        preferences.edit { putString(KEY_LOCALE, locale) }
        markChanged()
    }

    fun setFontScale(fontScale: Int) {
        preferences.edit { putInt(KEY_FONT_SCALE, fontScale) }
        dispatcher.schedule {
            settings = settings.copy(fontScale = fontScale)
            markChanged()
            onFontScaleUpdated(fontScale)
        }
    }

    fun setIsSyncThemeEnabled(enabled: Boolean) = sessionDurablePreferences.edit { putBoolean(KEY_THEME_SYNC, enabled) }
    fun setIsLocaleSyncEnabled(enabled: Boolean) = preferences.edit { putBoolean(KEY_LOCALE_SYNC, enabled) }

    @StoreThread
    fun handleConnectionOpen(userSettings: ModelUserSettings) = handleUserSettings(userSettings)
    @StoreThread
    fun handleUserSettingsUpdate(userSettings: ModelUserSettings) = handleUserSettings(userSettings)
    @StoreThread
    internal fun handleLoginResult(loginResult: ModelLoginResult) {
        StoreThreadScheduler.assertCurrentThread()
        val bootstrap = loginResult.userSettings ?: return
        var updated = settings
        if (acceptsSyncedTheme()) {
            bootstrap.theme?.let { updated = updated.copy(theme = it) }
        }
        if (getIsLocaleSyncEnabled()) bootstrap.locale?.let { updated = updated.copy(_locale = it) }
        if (updated != settings) {
            settings = updated
            sessionDurablePreferences.edit { putString(KEY_THEME, updated.theme) }
            preferences.edit { putString(KEY_LOCALE, updated._locale) }
            markChanged()
        }
    }

    @StoreThread
    fun handlePreLogout() {
        StoreThreadScheduler.assertCurrentThread()
        setIsSyncThemeEnabled(true)
        setIsLocaleSyncEnabled(true)
        settings = settings.copy(_locale = null, fontScale = -1)
        preferences.edit { remove(KEY_LOCALE); putInt(KEY_FONT_SCALE, -1) }
        onFontScaleUpdated(-1)
        markChanged()
    }

    @StoreThread
    internal fun reset() {
        StoreThreadScheduler.assertCurrentThread()
        settings = Settings(defaultTheme, null, -1)
        onFontScaleUpdated(-1)
        markChanged()
    }

    /**
     * Whether an incoming account theme should replace the local one.
     *
     * Material You is the only theme the account cannot express, so it is the only one an echo
     * could undo. Guards the synced path alone: picking a theme by hand always wins.
     */
    private fun acceptsSyncedTheme(): Boolean =
        getIsThemeSyncEnabled() && settings.theme != ModelUserSettings.THEME_MATERIAL_YOU

    @StoreThread
    private fun handleUserSettings(userSettings: ModelUserSettings) {
        StoreThreadScheduler.assertCurrentThread()
        var updated = settings
        if (acceptsSyncedTheme()) {
            userSettings.theme?.let { updated = updated.copy(theme = it) }
        }
        if (getIsLocaleSyncEnabled()) userSettings.locale?.let { updated = updated.copy(_locale = it) }
        if (updated != settings) {
            settings = updated
            sessionDurablePreferences.edit { putString(KEY_THEME, updated.theme) }
            preferences.edit { putString(KEY_LOCALE, updated._locale) }
            markChanged()
        }
    }

    companion object {
        private const val KEY_THEME = "CACHE_KEY_THEME"
        private const val KEY_THEME_SYNC = "CACHE_KEY_THEME_SYNC"
        private const val KEY_LOCALE = "CACHE_KEY_LOCALE"
        private const val KEY_LOCALE_SYNC = "CACHE_KEY_LOCALE_SYNC"
        private const val KEY_FONT_SCALE = "CACHE_KEY_FONT_SCALE"
    }
}

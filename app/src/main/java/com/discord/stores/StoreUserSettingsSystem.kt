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
        settings = Settings(
            sessionDurablePreferences.getString(KEY_THEME, defaultTheme) ?: defaultTheme,
            preferences.getString(KEY_LOCALE, null),
            preferences.getInt(KEY_FONT_SCALE, -1),
        )
    }

    fun setTheme(theme: String, apiSync: Boolean, onRequestApiSync: (() -> Unit)? = null) = dispatcher.schedule {
        require(!getIsThemeSyncEnabled() || !apiSync || onRequestApiSync != null) { "API callback required." }
        onRequestApiSync?.takeIf { getIsThemeSyncEnabled() && apiSync }?.invoke()
        val retainedTheme = if (settings.theme == ModelUserSettings.THEME_PURE_EVIL && theme == ModelUserSettings.THEME_DARK) {
            ModelUserSettings.THEME_PURE_EVIL
        } else theme
        settings = settings.copy(theme = retainedTheme)
        sessionDurablePreferences.edit { putString(KEY_THEME, retainedTheme) }
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
        if (getIsThemeSyncEnabled()) bootstrap.theme?.let { updated = updated.copy(theme = it) }
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

    @StoreThread
    private fun handleUserSettings(userSettings: ModelUserSettings) {
        StoreThreadScheduler.assertCurrentThread()
        var updated = settings
        if (getIsThemeSyncEnabled()) userSettings.theme?.let { updated = updated.copy(theme = it) }
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

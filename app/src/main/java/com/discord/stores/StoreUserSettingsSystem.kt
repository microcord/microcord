package com.discord.stores

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import androidx.core.content.edit
import com.discord.models.domain.ModelUserSettings
import com.discord.models.domain.auth.ModelLoginResult
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.theme.ThemeRuntime
import discord_protos.discord_users.v1.PreloadedUserSettingsOuterClass.PreloadedUserSettings
import rx.Observable

class StoreUserSettingsSystem(
    private val onFontScaleUpdated: (Int) -> Unit,
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    private val preferences: SharedPreferences = SharedPreferencesProvider.get(),
    private val sessionDurablePreferences: SharedPreferences = SharedPreferencesProvider.getPrefsSessionDurable(),
) : StoreV2() {
    /**
     * [theme] is the theme picked in settings. With [followSystem] on it is the theme used while
     * the device is in dark mode, and Light is used otherwise; [resolvedTheme] is what is shown.
     */
    data class Settings(
        val theme: String,
        val _locale: String?,
        val fontScale: Int,
        val followSystem: Boolean = false,
    ) {
        val locale: String get() = _locale ?: ModelUserSettings.getDefaultLocale()

        /**
         * Re-read on every activity creation, which a device dark-mode switch triggers. An active
         * custom theme decides its own base and ignores following the device.
         */
        val resolvedTheme: String
            get() = ThemeRuntime.activeBase ?: when {
                !followSystem -> theme
                Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK !=
                    Configuration.UI_MODE_NIGHT_YES -> ModelUserSettings.THEME_LIGHT
                theme == ModelUserSettings.THEME_LIGHT -> ModelUserSettings.THEME_DARKER
                else -> theme
            }
    }

    private lateinit var settings: Settings
    private var defaultTheme = ModelUserSettings.THEME_LIGHT

    /** The theme in effect; see [Settings.resolvedTheme]. */
    fun getTheme() = settings.resolvedTheme

    /** The theme picked in settings, which [getTheme] may override while following the device. */
    fun getThemeSetting() = settings.theme

    /**
     * Light is the only light theme; Material You inherits the dark palette and none of them follow
     * the OS night mode, so `Configuration.UI_MODE_NIGHT_MASK` says nothing about how the app looks.
     */
    fun isDarkTheme() = settings.resolvedTheme != ModelUserSettings.THEME_LIGHT

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
        // "system" was briefly a theme of its own before following the device became a switch.
        val storedRaw = sessionDurablePreferences.getString(KEY_THEME, defaultTheme) ?: defaultTheme
        val stored = if (storedRaw == "system") ModelUserSettings.THEME_DARKER else storedRaw
        val followSystem = storedRaw == "system" || sessionDurablePreferences.getBoolean(KEY_THEME_FOLLOW_SYSTEM, false)
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
            followSystem,
        )
    }

    /** [onSynced] runs only when the choice reached the account, so callers can confirm it. */
    fun setTheme(theme: String, apiSync: Boolean, onSynced: (() -> Unit)? = null) {
        if (apiSync && getIsThemeSyncEnabled()) {
            UserSettings.theme.set(theme)
            onSynced?.invoke()
        }
        dispatcher.schedule {
            settings = settings.copy(theme = theme)
            sessionDurablePreferences.edit { putString(KEY_THEME, theme) }
            markChanged()
        }
    }

    fun setFollowSystem(enabled: Boolean) = dispatcher.schedule {
        settings = settings.copy(followSystem = enabled)
        sessionDurablePreferences.edit { putBoolean(KEY_THEME_FOLLOW_SYSTEM, enabled) }
        markChanged()
    }

    fun setLocale(locale: String?, apiSync: Boolean, onSynced: (() -> Unit)? = null) {
        if (apiSync && getIsLocaleSyncEnabled()) {
            UserSettings.locale.set(locale)
            onSynced?.invoke()
        }
        dispatcher.schedule {
            settings = settings.copy(_locale = locale)
            preferences.edit { putString(KEY_LOCALE, locale) }
            markChanged()
        }
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
        settings = Settings(defaultTheme, null, -1, settings.followSystem)
        onFontScaleUpdated(-1)
        markChanged()
    }

    /**
     * Whether an incoming account theme should replace the local one.
     *
     * Material You and following the device are what the account cannot express, so they are what
     * an echo could undo (another client's Light would replace the chosen dark theme). Guards the
     * synced path alone: picking a theme by hand always wins.
     */
    private fun acceptsSyncedTheme(): Boolean = getIsThemeSyncEnabled() &&
        settings.theme != ModelUserSettings.THEME_MATERIAL_YOU && !settings.followSystem

    @StoreThread
    fun handleUserSettingsProto(proto: PreloadedUserSettings) {
        StoreThreadScheduler.assertCurrentThread()
        var updated = settings
        if (acceptsSyncedTheme()) {
            UserSettings.theme.get(proto)?.let { updated = updated.copy(theme = it) }
        }
        if (getIsLocaleSyncEnabled()) UserSettings.locale.get(proto)?.let { updated = updated.copy(_locale = it) }
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
        private const val KEY_THEME_FOLLOW_SYSTEM = "CACHE_KEY_THEME_FOLLOW_SYSTEM"
        private const val KEY_LOCALE = "CACHE_KEY_LOCALE"
        private const val KEY_LOCALE_SYNC = "CACHE_KEY_LOCALE_SYNC"
        private const val KEY_FONT_SCALE = "CACHE_KEY_FONT_SCALE"
    }
}

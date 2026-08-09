package com.discord.widgets.settings

import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import com.discord.R
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelUserSettings
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserSettings
import com.discord.stores.StoreUserSettingsSystem
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils.isStaff
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject

/**
 * State for [WidgetSettingsAppearance]: theme, theme sync and font scale.
 *
 * Reference: `WidgetSettingsAppearance.Model`, which the reference builds in the fragment's
 * companion. The two easter eggs live here too, because their counters must survive the rotation
 * that changing the theme causes.
 *
 * The font-scale slider writes through a debounced subject rather than on every pixel: each write
 * to `StoreUserSettingsSystem.setFontScale` recreates the activity.
 */
class SettingsAppearanceViewModel(
    private val userSettingsSystem: StoreUserSettingsSystem = StoreStream.getUserSettingsSystem(),
    storeStateObservable: Observable<StoreState> = observeStoreState(),
) : AppViewModel<SettingsAppearanceViewModel.ViewState>(ViewState.Uninitialized) {

    data class StoreState(
        val meUser: MeUser,
        val settings: StoreUserSettingsSystem.Settings,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()

        data class Loaded(
            val meUser: MeUser,
            val currentTheme: String,
            val fontScale: Int,
            val isThemeSyncEnabled: Boolean,
        ) : ViewState()
    }

    /** One-shot effects the screen renders but does not decide. */
    sealed class Event {
        /**
         * The sixth consecutive tap on "Light": flash the screen white and turn the torch on for
         * three seconds. (The reference compares the *pre-increment* counter against
         * `HOLY_LIGHT_UNLOCK_COUNT = 5`, so five taps arm it and the sixth fires it.)
         */
        object ShowHolyLight : Event()

        data class ThemeUpdated(@StringRes val messageResId: Int) : Event()
    }

    private val fontScaleWrites = BehaviorSubject.create<Int>()
    private val eventSubject = PublishSubject.create<Event>()

    private var holyLightTaps = 0

    init {
        storeStateObservable
            .map { storeState ->
                ViewState.Loaded(
                    meUser = storeState.meUser,
                    currentTheme = storeState.settings.theme,
                    fontScale = storeState.settings.fontScale,
                    isThemeSyncEnabled = userSettingsSystem.getIsThemeSyncEnabled(),
                )
            }
            .distinctUntilChanged()
            .ui(this)
            .subscribe { updateViewState(it) }

        fontScaleWrites
            .debounce(FONT_SCALE_WRITE_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            .ui(this)
            .subscribe { fontScale -> userSettingsSystem.setFontScale(fontScale) }
    }

    fun observeEvents(): Observable<Event> = eventSubject

    /** Repeated taps on "Light" light the screen up. The run resets after a pause. */
    @MainThread
    fun onLightThemeTapped() {
        updateTheme(ModelUserSettings.THEME_LIGHT)
        val taps = holyLightTaps++
        if (taps == 0) resetAfterTimeout { holyLightTaps = 0 }
        if (taps == HOLY_LIGHT_UNLOCK_COUNT) eventSubject.onNext(Event.ShowHolyLight)
    }

    @MainThread
    fun onDarkThemeTapped() = updateTheme(ModelUserSettings.THEME_DARK)

    @MainThread
    fun onOnyxThemeTapped() = updateTheme(ModelUserSettings.THEME_MIDNIGHT)

    @MainThread
    fun onDarkerThemeTapped() = updateTheme(ModelUserSettings.THEME_DARKER)

    /**
     * Material You is client-only, so the account has no value that could round-trip it. Sync is
     * turned off first: [StoreUserSettingsSystem.setIsSyncThemeEnabled] writes preferences without
     * marking the store changed, so it needs the [StoreUserSettingsSystem.setTheme] that follows to
     * publish the state the switch renders from.
     */
    @MainThread
    fun onMaterialYouThemeTapped() {
        userSettingsSystem.setIsSyncThemeEnabled(false)
        userSettingsSystem.setTheme(ModelUserSettings.THEME_MATERIAL_YOU, apiSync = false)
    }

    /** A run of taps has to be a run: pausing for [EASTER_EGG_UNLOCK_TIMEOUT_SECONDS] starts over. */
    private fun resetAfterTimeout(reset: () -> Unit) {
        Observable.timer(EASTER_EGG_UNLOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .ui(this)
            .subscribe { reset() }
    }

    @MainThread
    fun setThemeSyncEnabled(enabled: Boolean) {
        userSettingsSystem.setIsSyncThemeEnabled(enabled)
    }

    /** Called continuously while the slider moves; only the last value in a burst is persisted. */
    @MainThread
    fun onFontScaleChanged(fontScale: Int) = fontScaleWrites.onNext(fontScale)

    @MainThread
    fun resetFontScale() = userSettingsSystem.setFontScale(USE_SYSTEM_FONT_SCALE)

    /** Applies a Discord-supported [theme] locally, then mirrors it to the account. */
    @MainThread
    private fun updateTheme(theme: String) {
        userSettingsSystem.setTheme(theme, apiSync = true) {
            val api = RestAPI.apiOrNull ?: return@setTheme
            api.updateUserSettings(RestAPIParams.UserSettings.createWithTheme(theme))
                .restSubscribeOn()
                .ui(this)
                .appSubscribe(StoreUserSettings::class.java) {
                    eventSubject.onNext(Event.ThemeUpdated(R.string.theme_updated))
                }
        }
    }

    companion object {
        /** `FontUtils.USE_SYSTEM_FONT_SCALE`; that utility has not been reconstructed. */
        const val USE_SYSTEM_FONT_SCALE = -1

        /** `FontUtils.MIN_FONT_SCALING` / `MAX_FONT_SCALING`, as percentages. */
        const val MIN_FONT_SCALING = 80
        const val MAX_FONT_SCALING = 150

        private const val HOLY_LIGHT_UNLOCK_COUNT = 5
        private const val EASTER_EGG_UNLOCK_TIMEOUT_SECONDS = 5L
        private const val FONT_SCALE_WRITE_DEBOUNCE_MS = 400L

        fun observeStoreState(): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getUsers().observeMe(),
            StoreStream.getUserSettingsSystem().observeSettings(false),
        ) { meUser, settings -> StoreState(meUser, settings) }

        /** Percentage form of the device's own font scale, for the "use OS setting" label. */
        fun systemFontScalePercent(context: Context): Int = Math.round(
            android.provider.Settings.System.getFloat(
                context.contentResolver,
                android.provider.Settings.System.FONT_SCALE,
                1f,
            ) * 100f,
        )
    }
}

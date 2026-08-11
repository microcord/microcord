package com.discord.widgets.settings

import androidx.annotation.MainThread
import com.discord.app.AppViewModel
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserSettingsSystem
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.PublishSubject

/**
 * State for [WidgetSettingsLanguage].
 *
 * Reference: `WidgetSettingsLanguage` reads `StoreUserSettingsSystem.observeSettings` inline. The
 * locale write is a two-step: the local store applies it immediately (so the recreate picks it up)
 * and, when sync is on, the account is told afterwards.
 */
class SettingsLanguageViewModel(
    private val userSettingsSystem: StoreUserSettingsSystem = StoreStream.getUserSettingsSystem(),
    storeStateObservable: Observable<StoreUserSettingsSystem.Settings> = observeStoreState(),
) : AppViewModel<SettingsLanguageViewModel.ViewState>(ViewState.Uninitialized) {

    sealed class ViewState {
        object Uninitialized : ViewState()
        data class Loaded(val locale: String, val isLocaleSyncEnabled: Boolean) : ViewState()
    }

    sealed class Event {
        /** The account accepted the new locale. The activity restart is `AppActivity`'s job. */
        object LocaleUpdated : Event()
    }

    private val eventSubject = PublishSubject.create<Event>()

    init {
        storeStateObservable
            .map<ViewState> { settings ->
                ViewState.Loaded(settings.locale, userSettingsSystem.getIsLocaleSyncEnabled())
            }
            .distinctUntilChanged()
            .ui(this)
            .subscribe { updateViewState(it) }
    }

    fun observeEvents(): Observable<Event> = eventSubject

    @MainThread
    fun selectLocale(locale: String) {
        userSettingsSystem.setLocale(locale, apiSync = true) {
            eventSubject.onNext(Event.LocaleUpdated)
        }
    }

    @MainThread
    fun setLocaleSyncEnabled(enabled: Boolean) {
        userSettingsSystem.setIsLocaleSyncEnabled(enabled)
    }

    companion object {
        fun observeStoreState(): Observable<StoreUserSettingsSystem.Settings> =
            StoreStream.getUserSettingsSystem().observeSettings(false)
    }
}

package com.discord.widgets.guilds

import androidx.annotation.StringRes
import com.discord.R
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelGuildFolder
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreGuildsSorted
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserSettings
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.PublishSubject

class WidgetGuildFolderSettingsViewModel(
    val folderId: Long,
    storeUserSettings: StoreUserSettings? = null,
    storeGuildsSorted: StoreGuildsSorted? = null,
    storeStateObservable: Observable<StoreState>? = null,
) : AppViewModel<WidgetGuildFolderSettingsViewModel.ViewState>(ViewState.Uninitialized) {

    data class FormState(val name: String?, val color: Int?)

    sealed class StoreState {
        object Invalid : StoreState()
        data class Valid(val folder: ModelGuildFolder, val sortedGuilds: List<StoreGuildsSorted.Entry>) : StoreState()
    }

    sealed class ViewState {
        object Uninitialized : ViewState()
        data class Loaded(val formState: FormState, val showSave: Boolean) : ViewState()
    }

    sealed class Event {
        data class UpdateFolderSettingsSuccess(@StringRes val successMessageStringRes: Int) : Event()
        data class UpdateFolderSettingsFailure(@StringRes val failureMessageStringRes: Int) : Event()
    }

    private val eventSubject = PublishSubject.create<Event>()
    val storeUserSettings: StoreUserSettings by lazy { storeUserSettings ?: StoreStream.getUserSettings() }
    var previousStoreState: StoreState? = null
        private set

    init {
        (storeStateObservable ?: observeStoreState(
            folderId,
            this.storeUserSettings,
            storeGuildsSorted ?: StoreStream.getGuildsSorted(),
        ))
            .computationLatest()
            .ui(this)
            .appSubscribe(WidgetGuildFolderSettingsViewModel::class.java) { state ->
                previousStoreState = state
                if (state is StoreState.Valid) {
                    val form = FormState(state.folder.name, state.folder.color)
                    updateViewState(ViewState.Loaded(form, shouldShowSave(state, form)))
                }
            }
    }

    fun observeEvents(): Observable<Event> = eventSubject

    fun setName(name: String) = updateFormState { copy(name = name) }

    fun setColor(color: Int?) = updateFormState { copy(color = color) }

    fun saveFolder() {
        val storeState = previousStoreState as? StoreState.Valid ?: return
        val form = (viewState as? ViewState.Loaded)?.formState ?: return
        val folders = storeState.sortedGuilds.map { entry ->
            if (entry is StoreGuildsSorted.Entry.Folder && entry.id == folderId) {
                entry.copy(color = form.color, name = form.name).asModelGuildFolder()
            } else {
                entry.asModelGuildFolder()
            }
        }
        RestAPI.api.updateUserSettings(RestAPIParams.UserSettings.createWithGuildFolders(folders))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                WidgetGuildFolderSettingsViewModel::class.java,
                errorHandler = {
                    eventSubject.onNext(Event.UpdateFolderSettingsFailure(R.string.default_failure_to_perform_action_message))
                },
            ) {
                eventSubject.onNext(Event.UpdateFolderSettingsSuccess(R.string.guild_folder_updated_success))
            }
    }

    private fun updateFormState(change: FormState.() -> FormState) {
        val state = viewState as? ViewState.Loaded ?: return
        val form = state.formState.change()
        val storeState = previousStoreState as? StoreState.Valid
        updateViewState(state.copy(formState = form, showSave = storeState?.let { shouldShowSave(it, form) } == true))
    }

    private fun shouldShowSave(storeState: StoreState.Valid, formState: FormState): Boolean =
        storeState.folder.name != formState.name || storeState.folder.color != formState.color

    companion object {
        fun observeStoreState(
            folderId: Long,
            storeUserSettings: StoreUserSettings,
            storeGuildsSorted: StoreGuildsSorted,
        ): Observable<StoreState> = Observable.combineLatest(
            storeUserSettings.observeGuildFolders(),
            storeGuildsSorted.observeEntries(),
        ) { folders, sorted ->
            folders.firstOrNull { it.id == folderId }
                ?.let { StoreState.Valid(it, sorted) }
                ?: StoreState.Invalid
        }
    }
}

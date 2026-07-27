package com.discord.widgets.channels.settings

import android.content.Context
import androidx.annotation.MainThread
import com.discord.api.channel.Channel
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelNotificationSettings
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreChannels
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserGuildSettings
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.PublishSubject

/**
 * Drives [WidgetChannelGroupDMSettings]: rename a group DM, change or clear its icon, mute it, or
 * leave it.
 *
 * The icon is the only genuinely stateful part. A newly picked image arrives as a data URL long
 * before it is saved, so the view state keeps the *original* URL alongside the *edited* one and
 * derives [ViewState.Valid.hasUnsavedIconChange] from the difference. That single flag decides
 * which URL the preview shows, whether the "Remove" affordance is offered, and what gets sent when
 * the save button is pressed.
 */
class ChannelGroupDMSettingsViewModel(
    private val channelId: Long,
    private val restApi: RestAPIInterface = com.discord.utilities.rest.RestAPI.api,
    private val storeUserGuildSettings: StoreUserGuildSettings = StoreStream.getUserGuildSettings(),
    storeStateObservable: Observable<StoreState> = observeStoreState(channelId),
) : AppViewModel<ChannelGroupDMSettingsViewModel.ViewState>() {

    private val eventSubject: PublishSubject<Event> = PublishSubject.create()

    init {
        storeStateObservable
            .computationLatest()
            .ui(this)
            .appSubscribe(ChannelGroupDMSettingsViewModel::class.java) { handleStoreState(it) }
    }

    sealed class Event {
        object LeaveGroupSuccess : Event()
        object SettingsSaved : Event()
    }

    sealed class StoreState {
        /** The channel is gone — deleted, or the viewer was removed. */
        object Invalid : StoreState()

        data class Valid(
            val channel: Channel,
            val notificationSettings: ModelNotificationSettings,
        ) : StoreState()
    }

    sealed class ViewState {
        object Invalid : ViewState()

        data class Valid(
            val channelSettings: ChannelSettings,
            /** The icon as the server currently has it. */
            val originalIconUrl: String?,
            /** The icon as the user has left it, possibly a not-yet-uploaded data URL. */
            val editedIconUrl: String?,
        ) : ViewState() {
            val hasUnsavedIconChange: Boolean get() = originalIconUrl != editedIconUrl

            /** True when there is nothing worth offering a "Remove" action for. */
            val isDefaultPhoto: Boolean
                get() = editedIconUrl == null ||
                    editedIconUrl == IconUtils.getDefaultForGroupDM(channelSettings.channel.id)

            /**
             * The URL the preview should show: the pending edit while one exists (falling back to
             * the generated default when the edit is "removed"), otherwise the saved icon.
             */
            val currentIconUrl: String?
                get() = if (hasUnsavedIconChange) {
                    editedIconUrl ?: IconUtils.getDefaultForGroupDM(channelSettings.channel.id)
                } else {
                    IconUtils.getForChannel(channelSettings.channel)
                }
        }
    }

    @MainThread
    fun observeEvents(): Observable<Event> = eventSubject

    @MainThread
    private fun handleStoreState(storeState: StoreState) = when (storeState) {
        StoreState.Invalid -> updateViewState(ViewState.Invalid)
        is StoreState.Valid -> {
            val channelSettings = ChannelSettings
                .createFromNotificationSettings(storeState.channel, storeState.notificationSettings)
            val current = viewState
            // A store emission must not discard an icon the user has picked but not saved.
            updateViewState(
                if (current is ViewState.Valid) {
                    current.copy(channelSettings = channelSettings)
                } else {
                    ViewState.Valid(
                        channelSettings = channelSettings,
                        originalIconUrl = IconUtils.getForChannel(storeState.channel),
                        editedIconUrl = IconUtils.getForChannel(storeState.channel),
                    )
                },
            )
        }
    }

    @MainThread
    fun onIconEdited(iconUrl: String) {
        val current = viewState as? ViewState.Valid ?: return
        updateViewState(current.copy(editedIconUrl = iconUrl))
    }

    /** Clears the pending icon so the group falls back to its generated default. */
    @MainThread
    fun removeEditedIcon() {
        val current = viewState as? ViewState.Valid ?: return
        updateViewState(current.copy(editedIconUrl = null))
    }

    @MainThread
    fun editGroup(channelId: Long, name: String) {
        val current = viewState as? ViewState.Valid ?: return
        // An unsaved edit is sent as-is (empty string clears the icon); otherwise the icon is
        // round-tripped unchanged so a rename alone does not drop it.
        val icon =
            if (current.hasUnsavedIconChange) current.editedIconUrl.orEmpty() else current.originalIconUrl
        restApi.editGroupDM(channelId, RestAPIParams.GroupDM(name, icon))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(ChannelGroupDMSettingsViewModel::class.java) {
                eventSubject.onNext(Event.SettingsSaved)
            }
    }

    @MainThread
    fun leaveGroup() {
        val current = viewState as? ViewState.Valid ?: return
        restApi.deleteChannel(current.channelSettings.channel.id)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(ChannelGroupDMSettingsViewModel::class.java) {
                eventSubject.onNext(Event.LeaveGroupSuccess)
            }
    }

    fun unmute(context: Context) =
        storeUserGuildSettings.setChannelMuted(context, channelId, false, null)

    companion object {
        fun observeStoreState(
            channelId: Long,
            storeChannels: StoreChannels = StoreStream.getChannels(),
            storeUserGuildSettings: StoreUserGuildSettings = StoreStream.getUserGuildSettings(),
        ): Observable<StoreState> = storeChannels.observeChannel(channelId).switchMap { channel ->
            if (channel == null) {
                Observable.just(StoreState.Invalid)
            } else {
                storeUserGuildSettings.observeGuildSettings(channel.guildId)
                    .map { StoreState.Valid(channel, it) }
            }
        }
    }
}

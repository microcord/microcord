package com.discord.widgets.channels.settings

import androidx.annotation.MainThread
import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDeletedMessage
import com.discord.api.channel.isThread
import com.discord.app.AppViewModel
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuildsNsfw
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.threads.ThreadUtils
import rx.Observable
import rx.subjects.PublishSubject

/**
 * Drives [WidgetThreadSettings]: rename a thread, set its slow mode, open its pins, or delete it.
 *
 * The name and cooldown are held as *drafts* rather than written straight through, so a store
 * emission arriving mid-edit does not overwrite what the user typed — [handleStoreState] keeps the
 * existing drafts once they exist and only refreshes the channel behind them.
 *
 * Saving sends only the fields that actually changed; a rename and a cooldown change are one PATCH.
 */
class WidgetThreadSettingsViewModel(
    val channelId: Long,
    private val restApi: RestAPIInterface = RestAPI.api,
    storeStateObservable: Observable<StoreState> = observeStoreState(channelId),
) : AppViewModel<WidgetThreadSettingsViewModel.ViewState>() {

    private val eventSubject: PublishSubject<Event> = PublishSubject.create()

    init {
        storeStateObservable
            .distinctUntilChanged()
            .ui(this)
            .appSubscribe(WidgetThreadSettingsViewModel::class.java) { handleStoreState(it) }
    }

    sealed class Event {
        data class ShowToast(@StringRes val messageStringRes: Int) : Event()
        object Close : Event()
    }

    data class StoreState(val channel: Channel?, val canManageThread: Boolean)

    sealed class ViewState {
        /** The channel is gone or is not a thread; the screen closes itself. */
        object Invalid : ViewState()

        data class Valid(
            val channel: Channel,
            val channelNameDraft: String?,
            val slowModeCooldownDraft: Int,
            /** Only a private thread can restrict who adds members; absent means anyone may. */
            val invitableDraft: Boolean,
            val isPrivateThread: Boolean,
            val hasUnsavedChanges: Boolean,
            val canManageThread: Boolean,
            /** Pins are hidden behind an un-agreed NSFW gate. */
            val isPinsEnabled: Boolean,
        ) : ViewState()
    }

    fun observeEvents(): Observable<Event> = eventSubject

    @MainThread
    private fun handleStoreState(storeState: StoreState) {
        val channel = storeState.channel
        if (channel?.name == null || !channel.isThread()) {
            updateViewState(ViewState.Invalid)
            return
        }
        val isPinsEnabled = !channel.nsfw || isGuildNsfwGateAgreed(channel.guildId)
        val current = viewState as? ViewState.Valid
        updateViewState(
            if (current?.channelNameDraft != null) {
                current.copy(channel = channel)
            } else {
                ViewState.Valid(
                    channel = channel,
                    channelNameDraft = channel.name,
                    slowModeCooldownDraft = channel.rateLimitPerUser,
                    invitableDraft = channel.threadMetadata?.invitable != false,
                    isPrivateThread = channel.type == Channel.PRIVATE_THREAD,
                    hasUnsavedChanges = false,
                    canManageThread = storeState.canManageThread,
                    isPinsEnabled = isPinsEnabled,
                )
            },
        )
    }

    @MainThread
    fun onChannelNameInputChanged(value: String) {
        val current = viewState as? ViewState.Valid ?: return
        updateViewState(current.copy(channelNameDraft = value, hasUnsavedChanges = true))
    }

    @MainThread
    fun onInvitableChanged(invitable: Boolean) {
        val current = viewState as? ViewState.Valid ?: return
        updateViewState(current.copy(invitableDraft = invitable, hasUnsavedChanges = true))
    }

    fun onSlowModeInputChanged(cooldown: Int) {
        val current = viewState as? ViewState.Valid ?: return
        updateViewState(current.copy(slowModeCooldownDraft = cooldown, hasUnsavedChanges = true))
    }

    fun onThreadDeleted(channelId: Long) {
        restApi.deleteChannel(channelId)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetThreadSettingsViewModel::class.java,
                errorHandler = {
                    eventSubject.onNext(Event.ShowToast(R.string.default_failure_to_perform_action_message))
                },
                onNext = { channel ->
                    // The confirmation string depends on what kind of channel was deleted.
                    channel.getDeletedMessage()?.let { eventSubject.onNext(Event.ShowToast(it)) }
                    eventSubject.onNext(Event.Close)
                },
            )
    }

    fun saveThread() {
        val current = viewState as? ViewState.Valid ?: return
        val name = current.channelNameDraft.takeIf { it != current.channel.name }
        val rateLimit = current.slowModeCooldownDraft.takeIf { it != current.channel.rateLimitPerUser }
        val invitable = current.invitableDraft
            .takeIf { current.isPrivateThread && it != (current.channel.threadMetadata?.invitable != false) }
        restApi.editTextChannel(
            channelId,
            RestAPIParams.TextChannel(
                name = name,
                type = null,
                topic = null,
                nsfw = null,
                rateLimitPerUser = rateLimit,
                defaultAutoArchiveDuration = null,
                invitable = invitable,
            ),
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetThreadSettingsViewModel::class.java,
                errorHandler = {
                    eventSubject.onNext(Event.ShowToast(R.string.default_failure_to_perform_action_message))
                },
                onNext = { channel ->
                    StoreStream.handleThreadCreateOrUpdate(channel)
                    val latest = viewState as? ViewState.Valid ?: return@appSubscribe
                    val unchangedSinceSubmit = latest.channelNameDraft == current.channelNameDraft &&
                        latest.slowModeCooldownDraft == current.slowModeCooldownDraft &&
                        latest.invitableDraft == current.invitableDraft
                    updateViewState(
                        latest.copy(
                            channel = channel,
                            channelNameDraft = if (unchangedSinceSubmit) channel.name else latest.channelNameDraft,
                            slowModeCooldownDraft = if (unchangedSinceSubmit) {
                                channel.rateLimitPerUser
                            } else {
                                latest.slowModeCooldownDraft
                            },
                            hasUnsavedChanges = !unchangedSinceSubmit,
                        ),
                    )
                    eventSubject.onNext(Event.ShowToast(R.string.thread_settings_updated))
                },
            )
    }

    companion object {
        /**
         * `StoreStream` publishes no accessor for [StoreGuildsNsfw] yet, so the gate reads as
         * agreed and pins stay reachable on an NSFW thread the viewer has not gated through.
         */
        private fun isGuildNsfwGateAgreed(@Suppress("UNUSED_PARAMETER") guildId: Long): Boolean = true

        fun observeStoreState(
            channelId: Long,
            observationDeck: ObservationDeck = ObservationDeckProvider.get(),
            storeChannels: StoreChannels = StoreStream.getChannels(),
            storePermissions: StorePermissions = StoreStream.getPermissions(),
            storeUser: StoreUser = StoreStream.getUsers(),
        ): Observable<StoreState> = observationDeck.connectRx(
            storeChannels,
            storePermissions,
            storeUser,
        ) {
            val channel = storeChannels.getChannel(channelId)
            StoreState(
                channel = channel,
                canManageThread = channel != null && ThreadUtils.canManageThread(
                    storeUser.getMe(),
                    channel,
                    storePermissions.getPermissionsByChannel()[channel.id],
                ),
            )
        }
    }
}

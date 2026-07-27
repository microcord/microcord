package com.discord.widgets.stage.sheet

import androidx.annotation.MainThread
import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.stageinstance.StageInstance
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.app.AppViewModel
import com.discord.stores.StoreChannels
import com.discord.stores.StoreStageInstances
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.filterNull
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.stage.StageChannelAPI
import rx.Observable
import rx.subjects.PublishSubject

/**
 * Backs the sheet that opens a stage — or, once one is running, edits its topic.
 *
 * The two cases are the same form and the same button; which REST call runs is decided purely by
 * whether a [StageInstance] already exists for the channel.
 */
class WidgetStageStartEventBottomSheetViewModel(
    private val channelId: Long,
    private val usersStore: StoreUser = StoreStream.getUsers(),
    channelsStore: StoreChannels = StoreStream.getChannels(),
    stageInstanceStore: StoreStageInstances = StoreStream.getStageInstances(),
    storeStateObservable: Observable<StoreState> =
        observeStoreState(channelId, channelsStore, stageInstanceStore),
) : AppViewModel<WidgetStageStartEventBottomSheetViewModel.ViewState>() {

    data class StoreState(val channel: Channel, val stageInstance: StageInstance?)

    sealed class ViewState {
        object Invalid : ViewState()

        data class Loaded(
            val channel: Channel,
            val topic: String,
            val stageInstance: StageInstance?,
        ) : ViewState()
    }

    sealed class Event {
        data class SetTopicSuccess(val channel: Channel) : Event()

        /** The topic box is empty; the message is shown inline on the field, not as a toast. */
        data class InvalidTopic(@StringRes val failureMessageStringRes: Int) : Event()

        data class SetTopicFailure(@StringRes val failureMessageStringRes: Int) : Event()
    }

    private val eventSubject = PublishSubject.create<Event>()

    private var storeState: StoreState? = null

    init {
        storeStateObservable
            .ui(this)
            .appSubscribe(WidgetStageStartEventBottomSheetViewModel::class.java) {
                handleStoreState(it)
            }
    }

    fun observeEvents(): Observable<Event> = eventSubject

    /** Mirrors every keystroke back into the view state so the button's enablement tracks it. */
    fun setTopic(topic: String) {
        val loaded = viewState as? ViewState.Loaded ?: return
        updateViewState(loaded.copy(topic = topic))
    }

    /**
     * Starts the stage, or saves the topic of the one already running.
     *
     * @param microphonePermissionGranted when the stage is *started* and the microphone was granted,
     *   the starter is also unsuppressed so they open as a speaker. Editing an existing stage never
     *   changes who is speaking.
     */
    fun openStage(microphonePermissionGranted: Boolean) {
        val loaded = viewState as? ViewState.Loaded ?: return
        val topic = loaded.topic.trim().takeIf { it.isNotEmpty() }
        if (topic == null) {
            eventSubject.onNext(
                Event.InvalidTopic(R.string.start_stage_channel_event_modal_topic_required),
            )
            return
        }

        if (loaded.stageInstance == null) {
            StageChannelAPI.startStageInstance(
                channelId = channelId,
                topic = topic,
                privacyLevel = StageInstancePrivacyLevel.GUILD_ONLY,
                sendStartNotification = false,
            )
                .restSubscribeOn()
                .ui(this)
                .appSubscribe(
                    errorClass = WidgetStageStartEventBottomSheetViewModel::class.java,
                    errorHandler = { emitSetStatusFailureEvent() },
                ) {
                    if (microphonePermissionGranted) setSelfSpeaker()
                    emitSetStatusSuccessEvent(loaded.channel)
                }
        } else {
            StageChannelAPI.updateStageInstance(channelId = channelId, topic = topic)
                .restSubscribeOn()
                .ui(this)
                .appSubscribe(
                    errorClass = WidgetStageStartEventBottomSheetViewModel::class.java,
                    errorHandler = { emitSetStatusFailureEvent() },
                ) { emitSetStatusSuccessEvent(loaded.channel) }
        }
    }

    @MainThread
    private fun handleStoreState(storeState: StoreState) {
        this.storeState = storeState
        updateViewState(
            ViewState.Loaded(
                channel = storeState.channel,
                topic = storeState.stageInstance?.topic ?: "",
                stageInstance = storeState.stageInstance,
            ),
        )
    }

    /** Puts the person who just opened the stage on it. Failure is silent, as in the reference. */
    private fun setSelfSpeaker() {
        val channel = storeState?.channel ?: return
        StageChannelAPI.setUserSuppressedInChannel(
            channel = channel,
            userId = usersStore.getMe().id,
            isSuppressed = false,
        )
            .restSubscribeOn()
            .appSubscribe(
                errorClass = WidgetStageStartEventBottomSheetViewModel::class.java,
                errorHandler = { },
            ) { }
    }

    private fun emitSetStatusSuccessEvent(channel: Channel) =
        eventSubject.onNext(Event.SetTopicSuccess(channel))

    private fun emitSetStatusFailureEvent() = eventSubject.onNext(
        Event.SetTopicFailure(R.string.default_failure_to_perform_action_message),
    )

    companion object {
        fun observeStoreState(
            channelId: Long,
            channelsStore: StoreChannels,
            stageInstanceStore: StoreStageInstances,
        ): Observable<StoreState> = Observable.combineLatest(
            channelsStore.observeChannel(channelId).filterNull(),
            stageInstanceStore.observeStageInstanceForChannel(channelId).distinctUntilChanged(),
        ) { channel, stageInstance -> StoreState(channel, stageInstance) }
    }
}

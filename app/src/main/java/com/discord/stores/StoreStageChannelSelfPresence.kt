package com.discord.stores

import com.discord.api.activity.Activity
import com.discord.api.activity.ActivityType
import com.discord.api.voice.state.VoiceState
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.leadingEdgeThrottle
import rx.Observable
import rx.subjects.BehaviorSubject
import java.util.concurrent.TimeUnit

/**
 * Publishes "listening to a stage" as the current user's rich presence.
 *
 * This is the only store in the batch that *writes* to the gateway rather than reading from it, and
 * the whole design is about not writing too often. A busy stage produces a voice-state update for
 * every listener joining, leaving or being muted, and each one could change the party size embedded
 * in the activity.
 *
 * The rate limit is therefore **proportional to the size of the stage**: the throttle window is
 * `participants / 100 + 1` seconds, recomputed whenever the participant count crosses a hundred.
 * A 20-person stage republishes at most once a second; a 500-person stage at most once every six.
 * [publishStateTrigger] is the leading-edge input to that throttle, so the first change in a window
 * goes out immediately and the rest coalesce.
 *
 * [updateActivity] additionally suppresses no-op republishes: only a changed party id (a different
 * stage, or a different participant bucket) or a changed name is worth an update.
 *
 * The published activity is gated on the user's "display current activity" setting; when it is off
 * the activity is cleared rather than simply not sent, so turning the setting off takes effect
 * immediately.
 */
class StoreStageChannelSelfPresence(
    private val dispatcher: Dispatcher,
    private val userStore: StoreUser,
    private val userPresence: StoreUserPresence,
    private val stageChannels: StoreStageChannels,
    private val voiceChannelSelected: StoreVoiceChannelSelected,
    private val userSettings: StoreUserSettings,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    /**
     * Reference: `ActivityUtilsKt.createStageChannelListeningActivity()`. That builder needs
     * `StageCallRichPresencePartyData` plus the `packStageChannelPartyId` / `unpackStageChannelParty`
     * pair, none of which exist in `utilities/presence/ActivityUtils.kt` yet, so the default
     * publishes nothing. Install it and this store becomes live with no other change.
     */
    private val createStageChannelListeningActivity: () -> Activity? = { null },
) : StoreV2() {

    /** Leading-edge input to the size-proportional throttle; see the class doc. */
    private val publishStateTrigger = BehaviorSubject.create<Unit>()

    private var stageChannelActivity: Activity? = null

    @StoreThread
    internal fun getStageChannelActivity(): Activity? = stageChannelActivity

    /** Subscribes the publish pipeline. Call once, after the store graph is built. */
    fun init() {
        observationDeck.connectRx(stageChannels, voiceChannelSelected) {
            val participants =
                stageChannels.getChannelRolesInternal(voiceChannelSelected.getSelectedVoiceChannelId())
            (participants?.size ?: 0) / PARTICIPANTS_PER_THROTTLE_SECOND + 1
        }
            .distinctUntilChanged()
            .switchMap { windowSeconds ->
                Observable.combineLatest(
                    publishStateTrigger.leadingEdgeThrottle(windowSeconds.toLong(), TimeUnit.SECONDS),
                    userSettings.observeIsShowCurrentGameEnabled(),
                ) { _, isEnabled -> isEnabled }
            }
            .appSubscribe(StoreStageChannelSelfPresence::class.java) { showCurrentActivity ->
                dispatcher.schedule {
                    userPresence.updateActivity(
                        ActivityType.LISTENING,
                        stageChannelActivity.takeIf { showCurrentActivity },
                        true,
                    )
                }
            }
    }

    @StoreThread
    fun handleStageInstanceCreate() = updateActivity()

    @StoreThread
    fun handleStageInstanceUpdate() = updateActivity()

    @StoreThread
    fun handleStageInstanceDelete() = updateActivity()

    @StoreThread
    fun handleVoiceChannelSelected() = updateActivity()

    /** Only the current user's voice state can change what *this* client is listening to. */
    @StoreThread
    fun handleVoiceStateUpdate(voiceState: VoiceState) {
        if (voiceState.userId != userStore.getMeInternal().id) return
        updateActivity()
    }

    @StoreThread
    internal fun reset() {
        stageChannelActivity = null
        publishStateTrigger.onNext(Unit)
    }

    /**
     * Recomputes the activity and triggers a publish only when it is materially different. Party id
     * carries the channel, the guild and the participant bucket, so it changes far more often than
     * the name; both are compared because a renamed stage keeps its party id.
     */
    @StoreThread
    private fun updateActivity() {
        val activity = createStageChannelListeningActivity()
        val samePartyId = activity?.party?.id == stageChannelActivity?.party?.id
        val sameName = activity?.name == stageChannelActivity?.name
        if (samePartyId && sameName) return
        stageChannelActivity = activity
        publishStateTrigger.onNext(Unit)
    }

    companion object {
        /** One extra second of throttle per hundred stage participants. */
        private const val PARTICIPANTS_PER_THROTTLE_SECOND = 100
    }
}

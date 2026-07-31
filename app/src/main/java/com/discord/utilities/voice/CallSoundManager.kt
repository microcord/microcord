package com.discord.utilities.voice

import androidx.annotation.MainThread
import com.discord.api.channel.Channel
import com.discord.api.channel.shouldPlayJoinLeaveSounds
import com.discord.app.AppComponent
import com.discord.models.domain.ModelApplicationStream
import com.discord.models.user.MeUser
import com.discord.rtcconnection.RtcConnection
import com.discord.stores.StoreApplicationStreaming
import com.discord.stores.StoreChannels
import com.discord.stores.StoreRtcConnection
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.media.AppSound
import com.discord.utilities.media.AppSoundManager
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import rx.Observable
import rx.Subscription
import rx.functions.Func7

class CallSoundManager(
    private val appComponent: AppComponent,
    private val appSoundManager: AppSoundManager = AppSoundManager.Provider.get(),
    private val storeStateGenerator: IStoreStateGenerator = StoreStateGenerator(),
) {
    private var activeStreamKey: String? = null
    private var activeStreamUserId: Long? = null
    private var activeStreamViewerCount = 0
    private var numConnectedParticipants = 0
    private var storeStateSubscription: Subscription? = null
    private var streamingUserIds: List<Long> = emptyList()
    private var voiceChannelId: Long? = null

    @MainThread
    private fun handleStoreState(channelId: Long, storeState: StoreState) {
        val previousVoiceChannelId = voiceChannelId
        val previousStreamingUserIds = streamingUserIds
        val previousActiveStreamViewerCount = activeStreamViewerCount
        val previousActiveStreamKey = activeStreamKey
        val previousActiveStreamUserId = activeStreamUserId
        val previousConnectedParticipants = numConnectedParticipants

        voiceChannelId = channelId
        streamingUserIds = storeState.streamsByUser.values
            .filter { it.channelId == channelId }
            .map { it.ownerId }

        val activeStream = storeState.activeApplicationStream
            ?.takeIf { it.state.isStreamActive() }
        if (activeStream == null) {
            activeStreamKey = null
            activeStreamUserId = null
            activeStreamViewerCount = 0
        } else {
            val streamKey = activeStream.stream.encodedStreamKey
            activeStreamKey = streamKey
            activeStreamUserId = activeStream.stream.ownerId
            activeStreamViewerCount = storeState.streamSpectators[streamKey]
                .orEmpty()
                .count { it != storeState.me.id }
        }

        val isSameActiveStream = activeStreamKey != null && activeStreamKey == previousActiveStreamKey
        val streamStarted = streamingUserIds.any { it !in previousStreamingUserIds }
        val streamEnded = previousStreamingUserIds.any {
            it !in streamingUserIds && (it == storeState.me.id || it == previousActiveStreamUserId)
        }
        val streamViewerJoined = isSameActiveStream &&
            previousActiveStreamViewerCount <= JOIN_LEAVE_USER_LIMIT &&
            activeStreamViewerCount > previousActiveStreamViewerCount
        val streamViewerLeft = isSameActiveStream &&
            previousActiveStreamViewerCount <= JOIN_LEAVE_USER_LIMIT &&
            activeStreamViewerCount < previousActiveStreamViewerCount

        if (voiceChannelId == previousVoiceChannelId) {
            when {
                streamStarted -> appSoundManager.play(AppSound.SOUND_STREAM_STARTED)
                streamEnded -> appSoundManager.play(AppSound.SOUND_STREAM_ENDED)
                streamViewerJoined -> appSoundManager.play(AppSound.SOUND_STREAM_USER_JOINED)
                streamViewerLeft -> appSoundManager.play(AppSound.SOUND_STREAM_USER_LEFT)
            }
        }

        val rtcConnected = storeState.rtcConnectionState == RtcConnection.State.RtcConnected
        numConnectedParticipants = storeState.voiceParticipants.values.count { it.isConnected }
        val ringingParticipants = storeState.voiceParticipants.values.count { it.isRinging }

        if (
            rtcConnected &&
            storeState.selectedVoiceChannel?.shouldPlayJoinLeaveSounds() == true &&
            previousConnectedParticipants <= JOIN_LEAVE_USER_LIMIT
        ) {
            when {
                numConnectedParticipants > previousConnectedParticipants ->
                    appSoundManager.play(AppSound.SOUND_USER_JOINED)
                numConnectedParticipants < previousConnectedParticipants ->
                    appSoundManager.play(AppSound.SOUND_USER_LEFT)
            }
        }

        if (
            numConnectedParticipants < 2 &&
            ringingParticipants > 0 &&
            !appSoundManager.isPlaying(AppSound.SOUND_CALL_CALLING) &&
            rtcConnected
        ) {
            appSoundManager.play(AppSound.SOUND_CALL_CALLING)
            return
        }
        if (!rtcConnected || ringingParticipants == 0 || numConnectedParticipants >= 2) {
            appSoundManager.stop(AppSound.SOUND_CALL_CALLING)
        }
    }

    fun subscribeToStoreState(voiceChannelId: Long) {
        storeStateSubscription?.unsubscribe()
        storeStateGenerator.observeStoreState(voiceChannelId)
            .distinctUntilChanged()
            .computationLatest()
            .ui(appComponent)
            .appSubscribe(
                errorClass = CallSoundManager::class.java,
                subscriptionHandler = { storeStateSubscription = it },
                onTerminated = { appSoundManager.stop(AppSound.SOUND_CALL_CALLING) },
            ) { handleStoreState(voiceChannelId, it) }
    }

    interface IStoreStateGenerator {
        fun observeStoreState(voiceChannelId: Long): Observable<StoreState>
    }

    data class StoreState(
        val voiceParticipants: Map<Long, StoreVoiceParticipants.VoiceUser>,
        val rtcConnectionState: RtcConnection.State,
        val streamsByUser: Map<Long, ModelApplicationStream>,
        val streamSpectators: Map<String, List<Long>>,
        val activeApplicationStream: StoreApplicationStreaming.ActiveApplicationStream?,
        val me: MeUser,
        val selectedVoiceChannel: Channel?,
    )

    class StoreStateGenerator(
        private val storeVoiceParticipants: StoreVoiceParticipants = StoreStream.getVoiceParticipants(),
        private val storeRtcConnection: StoreRtcConnection = StoreStream.getRtcConnection(),
        private val storeApplicationStreaming: StoreApplicationStreaming = StoreStream.getApplicationStreaming(),
        private val storeUser: StoreUser = StoreStream.getUsers(),
        private val storeChannels: StoreChannels = StoreStream.getChannels(),
    ) : IStoreStateGenerator {
        override fun observeStoreState(voiceChannelId: Long): Observable<StoreState> =
            Observable.combineLatest(
                storeVoiceParticipants.get(voiceChannelId),
                storeRtcConnection.getConnectionState(),
                storeApplicationStreaming.observeStreamsByUser(),
                storeApplicationStreaming.observeStreamSpectators(),
                storeApplicationStreaming.observeActiveStream(),
                storeUser.observeMe(),
                storeChannels.observeChannel(voiceChannelId),
                Func7 { participants, stateChange, streams, spectators, activeStream, me, channel ->
                    StoreState(
                        participants,
                        stateChange.state,
                        streams,
                        spectators,
                        activeStream,
                        me,
                        channel,
                    )
                },
            )
    }

    private companion object {
        private const val JOIN_LEAVE_USER_LIMIT = 25
    }
}

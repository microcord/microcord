package com.discord.stores

import com.discord.api.voice.state.VoiceState
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelCall
import com.discord.models.domain.ModelPayload
import com.discord.models.user.CoreUser
import com.discord.stores.updates.ObservationDeck
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.schedulers.TestScheduler

/**
 * Behaviour of the voice store graph: caches, snapshot isolation and the derived participant fields.
 *
 * Two tests here are regressions for defects the 2026-07-26 parity audit found live in the target
 * (`docs/parity-audit/audit-stores-voice-gateway.md` §2k and §1):
 * [meUserMuteReadsLocalConfigurationNotTheGatewayEcho] and its deafen counterpart.
 */
class VoiceStoresTest {

    // region StoreVoiceStates

    @Test
    fun voiceStatesIndexByGuildAndDropUsersWithNoChannel() {
        val harness = Harness()
        val store = StoreVoiceStates({ _, _, _ -> }, harness.deck)
        harness.register(store)

        harness.dispatch { store.handleVoiceStateUpdate(voiceState(userId = 1, guildId = 5, channelId = 50)) }
        assertEquals(setOf(1L), store.get()[5L]?.keys)

        harness.dispatch { store.handleVoiceStateUpdate(voiceState(userId = 1, guildId = 5, channelId = null)) }
        assertEquals(emptySet<Long>(), store.get()[5L]?.keys)
    }

    @Test
    fun voiceStatesNotifyCarriesThePreviousChannelSoBothSidesRecompute() {
        val harness = Harness()
        val notifications = mutableListOf<Triple<Long, Long, Long>>()
        val store = StoreVoiceStates({ guildId, channelId, userId ->
            notifications += Triple(guildId, channelId, userId)
        }, harness.deck)
        harness.register(store)

        harness.dispatch { store.handleVoiceStateUpdate(voiceState(userId = 1, guildId = 5, channelId = 50)) }
        harness.dispatch { store.handleVoiceStateUpdate(voiceState(userId = 1, guildId = 5, channelId = 51)) }
        harness.dispatch { store.handleVoiceStateUpdate(voiceState(userId = 1, guildId = 5, channelId = null)) }

        assertEquals(
            listOf(
                Triple(5L, 0L, 1L),  // joined; there was no previous channel
                Triple(5L, 50L, 1L), // moved; the channel we left has to redraw
                Triple(5L, 51L, 1L), // disconnected from 51
            ),
            notifications,
        )
    }

    /**
     * A voice state for us from another session means a different device took the call. Storing it
     * would show this device as connected to a channel it is not in.
     */
    @Test
    fun voiceStateForMeFromAnotherSessionIsDroppedNotStored() {
        val harness = Harness()
        val store = StoreVoiceStates({ _, _, _ -> }, harness.deck)
        harness.register(store)
        harness.dispatch { store.handleConnectionOpen(readyPayload()) }

        harness.dispatch {
            store.handleVoiceStateUpdate(
                voiceState(userId = ME_ID, guildId = 5, channelId = 50, sessionId = "other-session"),
            )
        }
        assertNull(store.get()[5L]?.get(ME_ID))

        harness.dispatch {
            store.handleVoiceStateUpdate(
                voiceState(userId = ME_ID, guildId = 5, channelId = 50, sessionId = SESSION_ID),
            )
        }
        assertEquals(50L, store.get()[5L]?.get(ME_ID)?.channelId)
    }

    /** Only the guilds touched during a dispatch are re-copied; the rest keep the same instance. */
    @Test
    fun snapshotReusesUntouchedGuildMaps() {
        val harness = Harness()
        val store = StoreVoiceStates({ _, _, _ -> }, harness.deck)
        harness.register(store)

        harness.dispatch {
            store.handleVoiceStateUpdate(voiceState(userId = 1, guildId = 5, channelId = 50))
            store.handleVoiceStateUpdate(voiceState(userId = 2, guildId = 6, channelId = 60))
        }
        val untouched = store.get()[6L]

        harness.dispatch { store.handleVoiceStateUpdate(voiceState(userId = 3, guildId = 5, channelId = 50)) }
        assertSame(untouched, store.get()[6L])
        assertEquals(setOf(1L, 3L), store.get()[5L]?.keys)
    }

    @Test
    fun getForChannelFiltersWithinAGuild() {
        val harness = Harness()
        val store = StoreVoiceStates({ _, _, _ -> }, harness.deck)
        harness.register(store)
        harness.dispatch {
            store.handleVoiceStateUpdate(voiceState(userId = 1, guildId = 5, channelId = 50))
            store.handleVoiceStateUpdate(voiceState(userId = 2, guildId = 5, channelId = 51))
        }
        assertEquals(setOf(1L), store.getForChannel(5, 50).keys)
    }

    // endregion

    // region StoreVoiceSpeaking

    @Test
    fun speakingUsersAccumulateAndClearOnLeavingVoice() {
        val harness = Harness()
        val store = StoreVoiceSpeaking(harness.deck)
        harness.register(store)

        harness.dispatch {
            store.handleSpeakingUpdates(
                listOf(
                    StoreRtcConnection.SpeakingUserUpdate(1, true),
                    StoreRtcConnection.SpeakingUserUpdate(2, true),
                    StoreRtcConnection.SpeakingUserUpdate(1, false),
                ),
            )
        }
        assertEquals(setOf(2L), store.getSpeakingUsers())

        harness.dispatch { store.handleVoiceChannelSelected(77) }
        assertEquals(setOf(2L), store.getSpeakingUsers())

        harness.dispatch { store.handleVoiceChannelSelected(0) }
        assertEquals(emptySet<Long>(), store.getSpeakingUsers())
    }

    // endregion

    // region StoreCallsIncoming

    @Test
    fun incomingCallTracksOurOwnRingingEntryOnly() {
        val harness = Harness()
        val users = StoreUser(dispatcher = harness.dispatcher, observationDeck = harness.deck)
        val store = StoreCallsIncoming(harness.dispatcher, users, harness.deck)
        harness.register(users, store)
        harness.dispatch { users.handleConnectionOpen(readyPayload()) }

        harness.dispatch { store.handleCallCreateOrUpdate(ModelCall(channelId = 20, ringing = listOf(99L))) }
        assertEquals(emptySet<Long>(), store.getIncomingCalls())

        harness.dispatch { store.handleCallCreateOrUpdate(ModelCall(channelId = 20, ringing = listOf(ME_ID))) }
        assertEquals(setOf(20L), store.getIncomingCalls())

        // Answering the call is what clears the banner, not the server.
        harness.dispatch { store.handleVoiceChannelSelected(20) }
        assertEquals(emptySet<Long>(), store.getIncomingCalls())
    }

    @Test
    fun callDeleteClearsAnIncomingRing() {
        val harness = Harness()
        val users = StoreUser(dispatcher = harness.dispatcher, observationDeck = harness.deck)
        val store = StoreCallsIncoming(harness.dispatcher, users, harness.deck)
        harness.register(users, store)
        harness.dispatch { users.handleConnectionOpen(readyPayload()) }

        harness.dispatch { store.handleCallCreateOrUpdate(ModelCall(channelId = 20, ringing = listOf(ME_ID))) }
        harness.dispatch { store.handleCallDelete(ModelCall(channelId = 20)) }
        assertEquals(emptySet<Long>(), store.getIncomingCalls())
    }

    // endregion

    // region StoreVideoStreams

    @Test
    fun userStreamsAreRemovedOnceBothTracksAreGone() {
        val harness = Harness()
        val store = StoreVideoStreams(harness.deck)
        harness.register(store)

        harness.dispatch { store.handleVideoStreamUpdate(1, 7) }
        assertEquals(7, store.observeUserStreamsBlocking()[1L]?.callStreamId)

        harness.dispatch { store.handleApplicationStreamUpdate(1, 9) }
        assertEquals(9, store.observeUserStreamsBlocking()[1L]?.applicationStreamId)
        assertEquals(1L, store.findUserForApplicationStream(9))

        harness.dispatch {
            store.handleVideoStreamUpdate(1, null)
            store.handleApplicationStreamUpdate(1, null)
        }
        assertEquals(emptyMap<Long, StoreVideoStreams.UserStreams>(), store.observeUserStreamsBlocking())
    }

    @Test
    fun leavingVoiceDropsEveryVideoTrack() {
        val harness = Harness()
        val store = StoreVideoStreams(harness.deck)
        harness.register(store)

        harness.dispatch { store.handleVideoStreamUpdate(1, 7) }
        harness.dispatch { store.handleVoiceChannelSelected(0) }
        assertEquals(emptyMap<Long, StoreVideoStreams.UserStreams>(), store.observeUserStreamsBlocking())
    }

    @Test
    fun aUserLeavingVoiceDropsOnlyTheirTracks() {
        val harness = Harness()
        val store = StoreVideoStreams(harness.deck)
        harness.register(store)

        harness.dispatch {
            store.handleVideoStreamUpdate(1, 7)
            store.handleVideoStreamUpdate(2, 8)
        }
        harness.dispatch {
            store.handleVoiceStateUpdates(voiceState(userId = 1, guildId = 5, channelId = null))
        }
        assertEquals(setOf(2L), store.observeUserStreamsBlocking().keys)
    }

    // endregion

    // region StoreVoiceParticipants.VoiceUser

    /**
     * Regression: the me-user's mute indicator must come from local settings, not from the gateway
     * echo of our own voice state.
     *
     * The target previously derived `isMuted` from `voiceState.selfMute || voiceState.mute` for
     * everyone, so tapping mute did nothing visible until the gateway round-tripped it — and nothing
     * at all while disconnected.
     */
    @Test
    fun meUserMuteReadsLocalConfigurationNotTheGatewayEcho() {
        val locallyMuted = StoreMediaSettings.VoiceConfiguration(isSelfMuted = true)
        val notYetEchoed = voiceState(userId = ME_ID, guildId = 5, channelId = 50, selfMute = false)

        assertTrue(voiceUser(isMe = true, voiceState = notYetEchoed, config = locallyMuted).isMuted)
        // The same configuration must not leak onto anybody else's tile.
        assertFalse(voiceUser(isMe = false, voiceState = notYetEchoed, config = locallyMuted).isMuted)
    }

    @Test
    fun meUserDeafenReadsLocalConfigurationNotTheGatewayEcho() {
        val locallyDeafened = StoreMediaSettings.VoiceConfiguration(isSelfDeafened = true)
        val notYetEchoed = voiceState(userId = ME_ID, guildId = 5, channelId = 50, selfDeaf = false)

        assertTrue(voiceUser(isMe = true, voiceState = notYetEchoed, config = locallyDeafened).isDeafened)
        assertFalse(voiceUser(isMe = false, voiceState = notYetEchoed, config = locallyDeafened).isDeafened)
    }

    /** A server mute or deafen wins over local settings; a moderator cannot be overridden. */
    @Test
    fun serverMuteAndDeafenStillApplyToTheMeUser() {
        val unmutedLocally = StoreMediaSettings.VoiceConfiguration()
        val serverMuted = voiceState(userId = ME_ID, guildId = 5, channelId = 50, mute = true)
        val serverDeafened = voiceState(userId = ME_ID, guildId = 5, channelId = 50, deaf = true)

        assertTrue(voiceUser(isMe = true, voiceState = serverMuted, config = unmutedLocally).isMuted)
        assertTrue(voiceUser(isMe = true, voiceState = serverDeafened, config = unmutedLocally).isDeafened)
    }

    /** Deafened implies muted, so the mute tile lights up without a separate flag. */
    @Test
    fun selfDeafenedImpliesSelfMutedForTheMeUser() {
        val deafened = StoreMediaSettings.VoiceConfiguration(isSelfDeafened = true)
        assertTrue(voiceUser(isMe = true, voiceState = null, config = deafened).isMuted)
    }

    /**
     * The engine keeps reporting audio energy for a server-muted user; showing them as speaking
     * would contradict the mute badge on the same tile.
     */
    @Test
    fun speakingIsSuppressedWhileServerMutedOrDeafened() {
        assertTrue(voiceUser(voiceState = voiceState(1, 5, 50), speaking = true).isSpeaking)
        assertFalse(voiceUser(voiceState = voiceState(1, 5, 50, mute = true), speaking = true).isSpeaking)
        assertFalse(voiceUser(voiceState = voiceState(1, 5, 50, deaf = true), speaking = true).isSpeaking)
        assertFalse(voiceUser(voiceState = null, speaking = true).isSpeaking)
    }

    @Test
    fun displayNameAndConnectedAreDerived() {
        val disconnected = voiceUser(voiceState = null)
        assertFalse(disconnected.isConnected)
        assertEquals("me", disconnected.displayName)
        assertTrue(voiceUser(voiceState = voiceState(1, 5, 50)).isConnected)
    }

    // endregion

    // region StoreMediaSettings

    @Test
    fun deafeningImpliesMuteAndUndeafeningOnUnmute() {
        val store = StoreMediaSettings()
        store.setSelfDeafen(true)
        assertTrue(store.getVoiceConfigurationBlocking().isSelfMuted())

        store.setSelfMuted(false)
        assertFalse(store.getVoiceConfigurationBlocking().isSelfDeafened)
        assertFalse(store.getVoiceConfigurationBlocking().isSelfMuted())
    }

    /** Without VAD permission the user is forced muted and cannot unmute until they switch mode. */
    @Test
    fun forcedMuteBlocksUnmuteUntilInputModeChanges() {
        val store = StoreMediaSettings()
        store.handleCanUseVad(false)
        assertTrue(store.getVoiceConfigurationBlocking().isSelfMuted())
        assertEquals(StoreMediaSettings.SelfMuteFailure.CANNOT_USE_VAD, store.setSelfMuted(false))

        store.setVoiceInputMode(MediaEngineConnectionInputModePushToTalk)
        assertNull(store.setSelfMuted(false))
        assertFalse(store.getVoiceConfigurationBlocking().isSelfMuted())
    }

    @Test
    fun revertTemporaryDisableKrispOnlyUndoesTheTemporaryVariants() {
        val store = StoreMediaSettings()
        store.setVADUseKrisp(StoreMediaSettings.VadUseKrisp.TemporarilyDisabled)
        store.setNoiseProcessing(StoreMediaSettings.NoiseProcessing.CancellationTemporarilyDisabled)
        store.revertTemporaryDisableKrisp()
        assertEquals(StoreMediaSettings.VadUseKrisp.Enabled, store.getVoiceConfigurationBlocking().vadUseKrisp)
        assertEquals(
            StoreMediaSettings.NoiseProcessing.Cancellation,
            store.getVoiceConfigurationBlocking().noiseProcessing,
        )

        store.setVADUseKrisp(StoreMediaSettings.VadUseKrisp.Disabled)
        store.revertTemporaryDisableKrisp()
        assertEquals(StoreMediaSettings.VadUseKrisp.Disabled, store.getVoiceConfigurationBlocking().vadUseKrisp)
    }

    @Test
    fun perUserVolumeAndMuteAreStoredIndependently() {
        val store = StoreMediaSettings()
        store.toggleUserMuted(7)
        store.setUserOutputVolume(7, 42f)
        store.setUserOffScreen(8, true)
        val config = store.getVoiceConfigurationBlocking()
        assertEquals(mapOf(7L to true), config.mutedUsers)
        assertEquals(mapOf(7L to 42f), config.userOutputVolumes)
        assertEquals(mapOf(8L to true), config.offScreenUsers)

        store.toggleUserMuted(7)
        assertEquals(mapOf(7L to false), store.getVoiceConfigurationBlocking().mutedUsers)
    }

    // endregion

    // region helpers

    private val MediaEngineConnectionInputModePushToTalk
        get() = com.discord.rtcconnection.mediaengine.MediaEngineConnection.InputMode.PUSH_TO_TALK

    private fun voiceUser(
        isMe: Boolean = false,
        voiceState: VoiceState?,
        config: StoreMediaSettings.VoiceConfiguration? = null,
        speaking: Boolean = false,
    ) = StoreVoiceParticipants.VoiceUser(
        user = CoreUser(ME_ID, "me"),
        voiceState = voiceState,
        isRinging = false,
        streams = null,
        isMe = isMe,
        guildMember = null,
        watchingStream = null,
        streamContext = null,
        isBooster = false,
        voiceConfiguration = config,
        _isSpeaking = speaking,
    )

    private fun voiceState(
        userId: Long,
        guildId: Long,
        channelId: Long?,
        sessionId: String? = SESSION_ID,
        selfMute: Boolean = false,
        selfDeaf: Boolean = false,
        mute: Boolean = false,
        deaf: Boolean = false,
    ) = VoiceState(
        userId = userId,
        suppress = false,
        sessionId = sessionId,
        selfMute = selfMute,
        selfDeaf = selfDeaf,
        selfVideo = false,
        selfStream = false,
        mute = mute,
        guildId = guildId,
        deaf = deaf,
        member = null,
        requestToSpeakTimestamp = null,
        channelId = channelId,
    )

    /** Drives a store graph on a deterministic dispatcher, exactly as the real store thread does. */
    private class Harness {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()

        fun register(vararg stores: StoreV2) {
            dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(stores.toList(), deck))
        }

        fun dispatch(action: () -> Unit) {
            dispatcher.schedule(action)
            scheduler.triggerActions()
        }
    }

    private companion object {
        private const val ME_ID = 7L
        private const val SESSION_ID = "session"

        private val gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .create()

        fun readyPayload(): ModelPayload = gson.fromJson(
            """
            {
              "user":{"id":$ME_ID,"username":"me","discriminator":"0000"},
              "session_id":"$SESSION_ID",
              "guilds":[],
              "private_channels":[],
              "relationships":[]
            }
            """.trimIndent(),
            ModelPayload::class.java,
        )
    }

    // endregion
}

/** Reads the published snapshot without going through the Rx observation deck. */
private fun StoreVideoStreams.observeUserStreamsBlocking(): Map<Long, StoreVideoStreams.UserStreams> {
    var result: Map<Long, StoreVideoStreams.UserStreams> = emptyMap()
    observeUserStreams().take(1).subscribe { result = it }
    return result
}

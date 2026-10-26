package com.discord.stores

import com.discord.api.user.User
import com.discord.api.voice.state.VoiceState
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreVoiceStatesOtherSessionTest {
    @Test
    fun ourStateFromAnotherClientIsKeptApartUntilThisDeviceJoins() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val store = StoreVoiceStates({ _, _, _ -> }, deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        val other = mutableListOf<VoiceState?>()
        store.observeOtherSessionSelfState(GUILD).subscribe(other::add)
        val payload = ModelPayload().apply {
            sessionId = "here"
            me = User(id = ME, username = "me")
        }

        dispatcher.schedule {
            store.handleConnectionOpen(payload)
            store.handleVoiceStateUpdate(state(session = "desktop", channelId = 10))
        }
        scheduler.triggerActions()
        assertEquals(10L, other.last()?.channelId)
        assertEquals(emptyMap<Long, VoiceState>(), store.get()[GUILD].orEmpty())

        dispatcher.schedule { store.handleVoiceStateUpdate(state(session = "here", channelId = 20)) }
        scheduler.triggerActions()
        assertNull(other.last())
        assertEquals(20L, store.get()[GUILD]?.get(ME)?.channelId)

        dispatcher.schedule {
            store.handleVoiceStateUpdate(state(session = "here", channelId = null))
            store.handleVoiceStateUpdate(state(session = "desktop", channelId = 10))
            store.handleVoiceStateUpdate(state(session = "desktop", channelId = null))
        }
        scheduler.triggerActions()
        assertNull(other.last())
    }

    private fun state(session: String, channelId: Long?) = VoiceState(
        userId = ME,
        suppress = false,
        sessionId = session,
        selfMute = false,
        selfDeaf = false,
        selfVideo = false,
        selfStream = false,
        mute = false,
        guildId = GUILD,
        deaf = false,
        member = null,
        requestToSpeakTimestamp = null,
        channelId = channelId,
    )

    private companion object {
        const val ME = 1L
        const val GUILD = 5L
    }
}

package com.discord.widgets.channels.memberlist

import com.discord.api.channel.Channel
import com.discord.api.presence.ClientStatus
import com.discord.api.presence.Presence
import com.discord.api.user.User
import com.discord.models.domain.ModelPayload
import com.discord.stores.Dispatcher
import com.discord.stores.StoreApplicationStreaming
import com.discord.stores.StoreUser
import com.discord.stores.StoreUserPresence
import com.discord.stores.StoreV2DispatchHandler
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.Clock
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import rx.schedulers.TestScheduler

class PrivateChannelMemberListServiceTest {
    @Before
    fun setUp() = Persister.init(Files.createTempDirectory("private-members").toFile())

    @Test
    fun groupDmStateIncludesCurrentUserAndRecipients() {
        val scheduler = TestScheduler()
        val deck = ObservationDeck()
        val dispatcher = Dispatcher(scheduler)
        val users = StoreUser(
            dispatcher = dispatcher,
            observationDeck = deck,
            meCache = Persister("private-members-${System.nanoTime()}", StoreUser.EMPTY_ME_USER),
        )
        val presences = StoreUserPresence(Clock { 0L }, deck)
        val streams = StoreApplicationStreaming(dispatcher, users, observationDeck = deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(users, presences, streams), deck))

        val recipient = User(2L, "recipient")
        val channel = channel(recipient)
        val payload = ModelPayload().apply {
            me = User(1L, "me")
            privateChannels = listOf(channel)
        }
        dispatcher.schedule {
            users.handleConnectionOpen(payload)
            presences.handleConnectionOpen(payload)
        }
        scheduler.triggerActions()

        val state = PrivateChannelMemberListService(users, presences, streams)
            .observeStateForGroupDm(channel)
            .toBlocking()
            .first()

        assertEquals(setOf(1L, 2L), state.users.keys)
        assertEquals(channel, state.channel)
        assertEquals(setOf(1L), state.presences.keys)
        assertEquals(emptyMap<Long, Any>(), state.applicationStreams)
    }

    @Test
    fun groupDmRosterSurvivesRecipientGoingOffline() {
        val scheduler = TestScheduler()
        val deck = ObservationDeck()
        val dispatcher = Dispatcher(scheduler)
        val users = StoreUser(
            dispatcher = dispatcher,
            observationDeck = deck,
            meCache = Persister("private-members-offline-${System.nanoTime()}", StoreUser.EMPTY_ME_USER),
        )
        val presences = StoreUserPresence(Clock { 0L }, deck)
        val streams = StoreApplicationStreaming(dispatcher, users, observationDeck = deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(users, presences, streams), deck))
        val recipient = User(2L, "recipient")
        val channel = channel(recipient)
        val payload = ModelPayload().apply {
            me = User(1L, "me")
            privateChannels = listOf(channel)
        }
        dispatcher.schedule {
            users.handleConnectionOpen(payload)
            presences.handleConnectionOpen(payload)
        }
        scheduler.triggerActions()

        val states = mutableListOf<PrivateChannelMemberListService.State>()
        val subscription = PrivateChannelMemberListService(users, presences, streams)
            .observeStateForGroupDm(channel)
            .subscribe(states::add)
        dispatcher.schedule {
            presences.handlePresenceUpdate(0L, Presence(ClientStatus.ONLINE, userId = recipient.id))
        }
        scheduler.triggerActions()
        dispatcher.schedule {
            presences.handlePresenceUpdate(0L, Presence(ClientStatus.OFFLINE, userId = recipient.id))
        }
        scheduler.triggerActions()

        val offlineState = states.last()
        assertEquals(setOf(1L, 2L), offlineState.users.keys)
        assertEquals(setOf(1L), offlineState.presences.keys)
        subscription.unsubscribe()
    }

    private fun channel(recipient: User) = Channel(
        topic = null,
        type = Channel.GROUP_DM,
        guildId = 0,
        name = "group",
        lastMessageId = 0,
        id = 10,
        ownerId = 1,
        recipientIds = listOf(recipient.id),
        recipients = listOf(recipient),
        position = 0,
        permissionOverwrites = null,
        userLimit = 0,
        bitrate = 0,
        icon = null,
        originChannelId = 0,
        applicationId = 0,
        nicks = null,
        nsfw = false,
        parentId = 0,
        memberListId = null,
        rateLimitPerUser = 0,
        defaultAutoArchiveDuration = null,
        rtcRegion = null,
        flags = null,
        guildHashes = null,
        availableTags = null,
        appliedTags = null,
        threadMetadata = null,
        messageCount = null,
        memberCount = null,
        memberIdsPreview = null,
        member = null,
    )
}

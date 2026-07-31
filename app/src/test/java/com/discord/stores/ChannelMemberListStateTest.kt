package com.discord.stores

import com.discord.api.guildmember.GuildMember
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelGuildMemberListUpdate
import com.discord.models.domain.ModelGuildMemberListUpdate.Group
import com.discord.models.domain.ModelGuildMemberListUpdate.Item
import com.discord.models.domain.ModelGuildMemberListUpdate.Operation
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.lazy.memberlist.MemberListRow
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import rx.schedulers.Schedulers

class ChannelMemberListStateTest {
    private val dispatcher = Dispatcher(Schedulers.from(Executor { it.run() }))
    private val deck = ObservationDeck()

    @Test fun parsesExactGatewayShapeAndAppliesEveryOperation() {
        val parsed = gson.fromJson(
            """{"guild_id":"1","id":"everyone","groups":[{"id":"online","count":2}],"online_count":2,"member_count":3,"ops":[{"op":"SYNC","range":[0,1],"items":[{"group":{"id":"online","count":2}},{"member":${memberJson(1,"one")}}]},{"op":"UPDATE","index":1,"item":{"member":${memberJson(2,"two")}}},{"op":"INSERT","index":2,"item":{"member":${memberJson(3,"three")}}},{"op":"DELETE","index":1}]}""",
            ModelGuildMemberListUpdate::class.java,
        )
        val store = StoreChannelMembers(deck)
        register(store)
        dispatcher.schedule { store.handleGuildMemberListUpdate(parsed) }

        val state = requireNotNull(store.get(1, "everyone"))
        assertEquals(2, state.onlineCount)
        assertEquals(2, state.memberCount)
        assertEquals("online", (state.rows[0] as MemberListRow.GroupHeader).group.id)
        assertEquals(3, (state.rows[1] as MemberListRow.Member).member.user.id)
        assertEquals(0, state.groupIndices.keys.single())
    }

    @Test fun invalidationCreatesPlaceholdersAndSnapshotsAreDetached() {
        val store = StoreChannelMembers(deck)
        register(store)
        dispatcher.schedule {
            store.handleGuildMemberListUpdate(update(Operation.Sync(0..2, listOf(group(), member(1), member(2)))))
        }
        val old = requireNotNull(store.get(1, "everyone"))
        dispatcher.schedule {
            store.handleGuildMemberListUpdate(update(Operation.Invalidate(1..2)))
        }

        assertNotSame(old, store.get(1, "everyone"))
        assertEquals(1, (old.rows[1] as MemberListRow.Member).member.user.id)
        assertNull(store.get(1, "everyone")?.rows?.get(1))
        assertNull(store.get(1, "everyone")?.rows?.get(2))
    }

    @Test fun invalidOperationInvalidatesInsteadOfCrashingDispatcher() {
        val store = StoreChannelMembers(deck)
        register(store)
        dispatcher.schedule { store.handleGuildMemberListUpdate(update(Operation.Sync(0..1, listOf(group(), member(1))))) }
        dispatcher.schedule { store.handleGuildMemberListUpdate(update(Operation.Update(99, member(2)))) }

        assertEquals(listOf(null, null, null), store.get(1, "everyone")?.rows)
    }

    @Test fun memberUpdatesGuildRemovalAndResetPublishNewSnapshots() {
        val store = StoreChannelMembers(deck)
        register(store)
        dispatcher.schedule { store.handleGuildMemberListUpdate(update(Operation.Sync(0..1, listOf(group(), member(1))))) }
        dispatcher.schedule { store.handleGuildMemberUpdate(1, apiMember(1, "renamed")) }
        assertEquals("renamed", (store.get(1, "everyone")?.rows?.get(1) as MemberListRow.Member).member.nick)

        dispatcher.schedule { store.handleGuildRemove(1) }
        assertNull(store.get(1, "everyone"))
        dispatcher.schedule { store.reset() }
    }

    @Test fun subscriptionsExpandChunksAndDedupePayloads() {
        val payloads = mutableListOf<GuildSubscriptionPayload>()
        val store = StoreGuildSubscriptions(deck, dispatcher, onSubscription = payloads::add)
        register(store)
        dispatcher.schedule { store.handlePanelOpened(1, 10) }
        dispatcher.schedule { store.handlePanelOpened(1, 10) }
        dispatcher.schedule { store.handleViewportChanged(1, 10, 150..250) }

        assertEquals(2, payloads.size)
        assertEquals(listOf(0 until 100, 100 until 200, 200 until 300), store.get(1)[10])
        assertEquals(listOf(listOf(0, 99), listOf(100, 199), listOf(200, 299)), payloads.last().channels[10])

        dispatcher.schedule { store.handlePanelClosed(1, 10) }
        assertEquals(emptyMap<Long, List<List<Int>>>(), payloads.last().channels)
    }

    @Test fun subscriptionPayloadPreservesEveryGatewayField() {
        val gateway = GuildSubscriptionPayload(
            1,
            GuildSubscriptions(
                channels = mapOf(10L to listOf(0 until 100)),
                typing = true,
                activities = true,
                members = setOf(20L),
                threads = true,
                threadMemberLists = listOf(30L),
            ),
        ).toGatewayPayload()

        assertEquals(mapOf(10L to listOf(listOf(0, 99))), gateway.channels)
        assertEquals(true, gateway.typing)
        assertEquals(true, gateway.activities)
        assertEquals(listOf(20L), gateway.members)
        assertEquals(true, gateway.threads)
        assertEquals(listOf(30L), gateway.threadMemberLists)
    }

    private fun register(store: StoreV2) =
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))

    private fun update(vararg ops: Operation) = ModelGuildMemberListUpdate(
        guildId = 1,
        ops = ops.toList(),
        groups = listOf(Group("online", 2)),
    )

    private fun group() = Item.GroupItem(Group("online", 2))
    private fun member(id: Long) = Item.MemberItem(apiMember(id, "user$id"))
    private fun apiMember(id: Long, nick: String): GuildMember =
        gson.fromJson(memberJson(id, nick), GuildMember::class.java)

    private fun memberJson(id: Long, nick: String) =
        """{"guild_id":1,"user":{"id":$id,"username":"user$id"},"roles":[],"nick":"$nick","pending":false}"""

    companion object {
        private val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters().create()
    }
}

package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.guild.Guild as ApiGuild
import com.discord.api.guildmember.GuildMember as ApiGuildMember
import com.discord.api.guildmember.GuildMembersChunk
import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import com.discord.api.stageinstance.StageInstance
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.api.user.User
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelPayload
import com.discord.models.message.Message
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.persister.Persister
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.schedulers.TestScheduler

/**
 * Behavioural checks for the guild half of the store graph.
 *
 * The cases here are the ones the parity audit found broken or missing, so each test names the
 * user-visible symptom it protects rather than the method it calls.
 */
class GuildStoreGraphTest {
    private val directory = Files.createTempDirectory("guild-store-graph").toFile()
    private val scheduler = TestScheduler()
    private val dispatcher = Dispatcher(scheduler)
    private val deck = ObservationDeck()

    @Before fun setUp() = Persister.init(directory)
    @After fun tearDown() { directory.deleteRecursively() }

    // region StoreGuilds

    @Test fun partialMemberUpdateKeepsCachedBioAndBannerWhileFullUpdateReplacesThem() {
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val guilds = StoreGuilds(users, dispatcher, deck)
        register(users, guilds)
        dispatcher.schedule { users.handleConnectionOpen(userPayload()) }

        dispatcher.schedule {
            guilds.handleGuildMember(member(7, bio = "hello", banner = "banner-hash"), 1, isFullGuildMember = true)
        }
        scheduler.triggerActions()

        // GUILD_MEMBER_ADD carries no profile fields; they must survive it.
        dispatcher.schedule { guilds.handleGuildMemberAdd(member(7, nick = "renamed")) }
        scheduler.triggerActions()

        assertEquals("renamed", guilds.getMember(1, 7)?.nick)
        assertEquals("hello", guilds.getMember(1, 7)?.bio)
        assertEquals("banner-hash", guilds.getMember(1, 7)?.bannerHash)

        // A source that really does carry the whole member is allowed to clear them.
        dispatcher.schedule { guilds.handleGuildMember(member(7), 1, isFullGuildMember = true) }
        scheduler.triggerActions()
        assertNull(guilds.getMember(1, 7)?.bio)
        assertNull(guilds.getMember(1, 7)?.bannerHash)
    }

    @Test fun timedOutMembersAreObservableAndClearingOneRecomputesOwnPermissions() {
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        var permissionRecomputes = 0
        val guilds = StoreGuilds(
            users,
            dispatcher,
            deck,
            onMeCommunicationEnabled = { permissionRecomputes++ },
            communicationDisabledScheduler = scheduler,
        )
        register(users, guilds)

        var timedOut = emptyList<Long>()
        guilds.observeCommunicationDisabledGuildMembers().subscribe { members -> timedOut = members.map { it.userId } }

        val deadline = System.currentTimeMillis() + 10 * 60 * 1000L
        dispatcher.schedule {
            users.handleConnectionOpen(userPayload())
            guilds.handleGuildAdd(guild(1, "Guild"))
            guilds.handleGuildMember(member(7, timeoutUntil = UtcDateTime(deadline)), 1)
        }
        scheduler.triggerActions()

        // The sweep source has to see the member, and has to depend on the guild set as well as
        // the member map — a guild the user left must drop out of this list.
        assertEquals(listOf(7L), timedOut)

        dispatcher.schedule { guilds.handleGuildRemove(guild(1, "Guild")) }
        scheduler.triggerActions()
        assertEquals(emptyList<Long>(), timedOut)

        // Clearing our own timeout has to feed back into the permission store, because a timeout
        // masks permissions down to the view/read allow list.
        dispatcher.schedule {
            guilds.handleGuildMember(member(7, timeoutUntil = UtcDateTime(deadline)), 1)
            guilds.handleGuildMemberCommunicationEnabledInternal(1, 7)
        }
        scheduler.triggerActions()

        assertNull(guilds.getMember(1, 7)?.communicationDisabledUntil)
        assertEquals(1, permissionRecomputes)

        // The periodic sweep must subscribe without throwing; it is what lifts expired timeouts.
        guilds.initClearCommunicationDisabledObserver(null)
        scheduler.advanceTimeBy(11, TimeUnit.SECONDS)
    }

    @Test fun lurkedGuildsStayInMemoryButAreNeverPersisted() {
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val cache = Persister<Map<Long, com.discord.models.guild.Guild>>(StoreGuilds.GUILDS_CACHE_KEY, emptyMap())
        val guilds = StoreGuilds(users, dispatcher, deck, cache, getLurkingGuildIds = { setOf(2L) })
        register(users, guilds)

        dispatcher.schedule {
            users.handleConnectionOpen(userPayload())
            guilds.handleGuildAdd(guild(1, "Joined"))
            guilds.handleGuildAdd(guild(2, "Lurked"))
        }
        scheduler.triggerActions()

        assertEquals(setOf(1L, 2L), guilds.getGuilds().keys)
        assertEquals(setOf(1L), cache.get().keys)
    }

    @Test fun rolesSortHighestFirstAndBreakTiesOnTheOlderRole() {
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val guilds = StoreGuilds(users, dispatcher, deck)
        register(users, guilds)
        var sorted = emptyList<Long>()

        dispatcher.schedule {
            users.handleConnectionOpen(userPayload())
            guilds.handleGuildRoleCreateOrUpdate(1, role(100, position = 1))
            guilds.handleGuildRoleCreateOrUpdate(1, role(200, position = 5))
            guilds.handleGuildRoleCreateOrUpdate(1, role(300, position = 5))
        }
        guilds.observeSortedRoles(1).subscribe { roles -> sorted = roles.map(GuildRole::id) }
        scheduler.triggerActions()

        assertEquals(listOf(200L, 300L, 100L), sorted)
    }

    // endregion

    // region StoreLurking

    @Test fun aLurkRequestedBeforeReadyIsReplayedAndEndsWhenTheUserActuallyJoins() {
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val guilds = StoreGuilds(users, dispatcher, deck)
        val guildSelected = StoreGuildSelected(dispatcher, deck)
        val channels = StoreChannels(dispatcher, deck, isAuthenticated = { true })
        val lurking = StoreLurking(guilds, guildSelected, channels, dispatcher)
        register(users, guilds, guildSelected, channels)

        var lurkingIds = emptySet<Long>()
        lurking.getLurkingGuildIds().subscribe { lurkingIds = it }

        // No gateway session yet: the request is parked rather than dropped.
        lurking.startLurkingAndNavigate(5, null)
        scheduler.triggerActions()
        assertEquals(emptySet<Long>(), lurking.getLurkingGuildIdsSync())

        dispatcher.schedule { lurking.handleConnectionOpen(ModelPayload().apply { sessionId = "session" }) }
        scheduler.triggerActions()
        scheduler.triggerActions()

        assertEquals(setOf(5L), lurking.getLurkingGuildIdsSync())
        assertEquals(setOf(5L), lurkingIds)
        assertTrue(lurking.isLurking(5))

        // The guild arriving with a join timestamp means the preview turned into a membership.
        dispatcher.schedule { lurking.handleGuildAdd(joinedGuild(5)) }
        scheduler.triggerActions()
        assertEquals(emptySet<Long>(), lurking.getLurkingGuildIdsSync())
        assertFalse(lurking.isLurking(5))
    }

    // endregion

    // region StoreGuildMemberRequester

    @Test fun unknownMessageAuthorsAreRequestedOnceAndQueuedWhileDisconnected() {
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val guilds = StoreGuilds(users, dispatcher, deck)
        val channels = StoreChannels(dispatcher, deck, isAuthenticated = { true })
        val requests = mutableListOf<Pair<Long, List<Long>>>()
        val requester = StoreGuildMemberRequester(dispatcher, guilds, channels) { guildId, userIds ->
            requests += guildId to userIds.sorted()
        }
        register(users, guilds, channels)

        dispatcher.schedule {
            users.handleConnectionOpen(userPayload())
            channels.handleConnectionOpen(ModelPayload().withGuilds(listOf(guildWithChannel())))
        }
        scheduler.triggerActions()

        // Offline: the page is parked, nothing goes out.
        dispatcher.schedule { requester.handleLoadMessages(10, listOf(message(1, author = 20))) }
        scheduler.triggerActions()
        assertTrue(requests.isEmpty())

        dispatcher.schedule { requester.handleConnectionOpen() }
        scheduler.triggerActions()
        assertEquals(listOf(1L to listOf(20L)), requests)

        // The same author again must not produce a second request.
        dispatcher.schedule { requester.handleLoadMessages(10, listOf(message(2, author = 20))) }
        scheduler.triggerActions()
        assertEquals(1, requests.size)

        // Once the server answers, and the member is still unknown, a reconnect retries it.
        dispatcher.schedule {
            requester.handleGuildMembersChunk(GuildMembersChunk(1, emptyList(), null, listOf(20L)))
            requester.handleConnectionReady(true)
        }
        scheduler.triggerActions()
        assertEquals(1, requests.size)
    }

    // endregion

    // region StoreGuildMemberCounts

    @Test fun memberCountFollowsJoinsAndLeavesButNeverInventsAGuild() {
        val counts = StoreGuildMemberCounts(deck)
        register(counts)

        dispatcher.schedule { counts.handleConnectionOpen(ModelPayload().withGuilds(listOf(guild(1, "Guild")))) }
        scheduler.triggerActions()
        val initial = counts.getApproximateMemberCount(1)

        dispatcher.schedule {
            counts.handleGuildMemberAdd(member(7))
            counts.handleGuildMemberAdd(member(8))
            counts.handleGuildMemberRemove(1)
            // Guild 99 was never hydrated, so this must not create a count for it.
            counts.handleGuildMemberRemove(99)
        }
        scheduler.triggerActions()

        assertEquals(initial + 1, counts.getApproximateMemberCount(1))
        assertEquals(0, counts.getApproximateMemberCount(99))
    }

    // endregion

    // region StoreGuildSubscriptions

    @Test fun selectingAGuildRequestsTypingActivitiesAndThreads() {
        val payloads = mutableListOf<GuildSubscriptionPayload>()
        val subscriptions = StoreGuildSubscriptions(deck, payloads::add)
        register(subscriptions)

        dispatcher.schedule { subscriptions.handleGuildSelect(1) }
        scheduler.triggerActions()

        assertEquals(1, payloads.size)
        assertEquals(true, payloads.single().subscriptions.typing)
        assertEquals(true, payloads.single().subscriptions.activities)
        assertEquals(true, payloads.single().subscriptions.threads)
    }

    @Test fun losingTheConnectionKeepsOnlyTheSelectedAndVoiceGuilds() {
        val payloads = mutableListOf<GuildSubscriptionPayload>()
        val subscriptions = StoreGuildSubscriptions(
            deck,
            dispatcher,
            getSelectedGuildId = { 1L },
            getConnectedVoiceGuildId = { 3L },
            onSubscription = payloads::add,
        )
        register(subscriptions)

        dispatcher.schedule {
            subscriptions.handleGuildSelect(1)
            subscriptions.handleGuildSelect(2)
            subscriptions.handleGuildSelect(3)
        }
        dispatcher.schedule { subscriptions.handleConnectionReady(false) }
        scheduler.triggerActions()

        payloads.clear()
        dispatcher.schedule { subscriptions.handleConnectionReady(true) }
        scheduler.triggerActions()

        assertEquals(setOf(1L, 3L), payloads.map(GuildSubscriptionPayload::guildId).toSet())
    }

    @Test fun individualMemberSubscriptionsAreReferenceCounted() {
        val payloads = mutableListOf<GuildSubscriptionPayload>()
        val subscriptions = StoreGuildSubscriptions(deck, payloads::add)
        register(subscriptions)

        dispatcher.schedule {
            subscriptions.handleSubscribeMember(1, 20)
            subscriptions.handleSubscribeMember(1, 20)
        }
        scheduler.triggerActions()

        // Two holders, one emission: the second subscribe is already covered by the first.
        assertEquals(1, payloads.size)
        assertEquals(setOf(20L), payloads.single().subscriptions.members)
    }

    // endregion

    // region StoreGuildSelected

    @Test fun recentSelectionsPersistThroughLeastRecentlyAddedSetMinusLurkedGuilds() {
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val guilds = StoreGuilds(users, dispatcher, deck)
        var viewed = 0L
        val store = StoreGuildSelected(
            dispatcher,
            deck,
            guilds,
            getLurkingGuildIds = { setOf(2L) },
            trackGuildViewed = { viewed = it },
        )
        register(users, guilds, store)

        dispatcher.schedule {
            users.handleConnectionOpen(userPayload())
            guilds.handleGuildAdd(guild(1, "Guild"))
        }
        (1L..3L).forEach(store::set)
        scheduler.triggerActions()

        assertEquals(listOf(3L, 2L, 1L), store.getRecentlySelectedGuildIds())
        assertEquals(3L, viewed)

        var selected: com.discord.models.guild.Guild? = null
        store.observeSelectedGuild().subscribe { selected = it }
        store.set(1L)
        scheduler.triggerActions()
        assertEquals("Guild", selected?.name)

        Persister.persistAll()
        Persister.init(directory)
        val restored = StoreGuildSelected(dispatcher, deck)
        register(restored)
        dispatcher.schedule { restored.restore() }
        scheduler.triggerActions()

        // Guild 2 was only ever lurked, so it is absent from the restored recency list.
        assertEquals(listOf(1L, 3L), restored.getRecentlySelectedGuildIds())
    }

    // endregion

    // region StorePermissions

    @Test fun stageInstancesWidenLurkerPermissionsAndSingleChannelUpdatesStayScoped() {
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val guilds = StoreGuilds(users, dispatcher, deck)
        val channels = StoreChannels(dispatcher, deck, isAuthenticated = { true })
        val stageInstances = StoreStageInstances(deck)
        val permissions = StorePermissions(
            users, channels, guilds, deck,
            storeStageInstances = stageInstances,
        )
        register(users, guilds, channels, stageInstances, permissions)

        val payload = ModelPayload().withGuilds(listOf(lurkerGuild()))
        dispatcher.schedule {
            users.handleConnectionOpen(userPayload())
            guilds.handleConnectionOpen(payload)
            channels.handleConnectionOpen(payload)
            permissions.handleConnectionOpen()
        }
        scheduler.triggerActions()

        // The member never joined, so they are a lurker and see only the lurker allow-list.
        val withoutStage = permissions.getPermissionsByChannelInternal(1)[10] ?: 0L
        assertFalse(can(Permission.REQUEST_TO_SPEAK, withoutStage))

        dispatcher.schedule {
            stageInstances.handleStageInstanceCreate(stageInstance())
            permissions.handleStageInstanceChange(stageInstance())
        }
        scheduler.triggerActions()

        val withStage = permissions.getPermissionsByChannelInternal(1)[10] ?: 0L
        assertTrue(can(Permission.REQUEST_TO_SPEAK, withStage))
    }

    @Test fun deletingAChannelDropsOnlyThatChannelFromTheGuildMap() {
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val guilds = StoreGuilds(users, dispatcher, deck)
        val channels = StoreChannels(dispatcher, deck, isAuthenticated = { true })
        val permissions = StorePermissions(users, channels, guilds, deck)
        register(users, guilds, channels, permissions)

        val payload = ModelPayload().withGuilds(listOf(twoChannelGuild()))
        dispatcher.schedule {
            users.handleConnectionOpen(userPayload())
            guilds.handleConnectionOpen(payload)
            channels.handleConnectionOpen(payload)
            permissions.handleConnectionOpen()
        }
        scheduler.triggerActions()
        assertEquals(setOf(10L, 11L), permissions.getPermissionsByChannelInternal(1).keys)

        dispatcher.schedule { permissions.handleChannelOrThreadDelete(channel(11)) }
        scheduler.triggerActions()
        assertEquals(setOf(10L), permissions.getPermissionsByChannelInternal(1).keys)
    }

    @Test fun loadedMessagesRecomputeTheGuildOwningTheirThreads() {
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val guilds = StoreGuilds(users, dispatcher, deck)
        val channels = StoreChannels(dispatcher, deck, isAuthenticated = { true })
        val permissions = StorePermissions(users, channels, guilds, deck)
        register(users, guilds, channels, permissions)

        val payload = ModelPayload().withGuilds(listOf(twoChannelGuild()))
        dispatcher.schedule {
            users.handleConnectionOpen(userPayload())
            guilds.handleConnectionOpen(payload)
            channels.handleConnectionOpen(payload)
            permissions.handleConnectionOpen()
            permissions.recomputeAllPermissions()
        }
        scheduler.triggerActions()

        // A message with no thread must not blow up or clear anything.
        dispatcher.schedule { permissions.handleMessagesLoaded(listOf(message(1, author = 7))) }
        dispatcher.schedule { permissions.handleSearchFinish(listOf(message(1, author = 7))) }
        scheduler.triggerActions()
        assertEquals(setOf(10L, 11L), permissions.getPermissionsByChannelInternal(1).keys)
    }

    // endregion

    // region fetch-state stores

    @Test fun welcomeScreenFromAJoinSkipsTheFetchAndIsRememberedAsSeen() {
        val store = StoreGuildWelcomeScreens(dispatcher, deck)
        register(store)

        dispatcher.schedule { store.handleGuildJoined(1, null) }
        store.markWelcomeScreenShown(1)
        scheduler.triggerActions()

        assertEquals(StoreGuildWelcomeScreens.State.Loaded(null), store.getGuildWelcomeScreen(1))
        assertTrue(store.hasWelcomeScreenBeenSeen(1))
        assertFalse(store.hasWelcomeScreenBeenSeen(2))
    }

    @Test fun voiceRegionsOnlyExposeLoadedResults() {
        val store = StoreGuildVoiceRegions(dispatcher, deck)
        register(store)

        dispatcher.schedule {
            store.handleGuildVoiceRegionsFetchStart(1)
            store.handleGuildVoiceRegionsFetchFailed(2)
        }
        scheduler.triggerActions()
        assertEquals(emptyList<Any>(), store.getGuildVoiceRegions(1))
        assertEquals(emptyList<Any>(), store.getGuildVoiceRegions(2))
    }

    @Test fun guildProfileFailureIsRetriedWhileSuccessIsNot() {
        val store = StoreGuildProfiles(dispatcher, deck)
        register(store)

        dispatcher.schedule { store.handleGuildProfileFetchFailed(1) }
        scheduler.triggerActions()
        assertEquals(StoreGuildProfiles.FetchStates.FAILED, store.getGuildProfile(1)?.fetchState)

        dispatcher.schedule { store.handleGuildProfileFetchStart(1) }
        scheduler.triggerActions()
        assertEquals(StoreGuildProfiles.FetchStates.FETCHING, store.getGuildProfile(1)?.fetchState)
    }

    // endregion

    private fun register(vararg stores: StoreV2) =
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(stores.toList(), deck))

    private fun can(permission: Long, permissions: Long) = PermissionUtils.can(permission, permissions)

    private fun member(
        userId: Long,
        nick: String? = null,
        bio: String? = null,
        banner: String? = null,
        timeoutUntil: UtcDateTime? = null,
    ) = ApiGuildMember(
        guildId = 1,
        user = User(id = userId, username = "user$userId"),
        roles = emptyList(),
        nick = nick,
        premiumSince = null,
        joinedAt = UtcDateTime(0),
        pending = false,
        presence = null,
        userId = userId,
        avatar = null,
        bio = bio,
        banner = banner,
        communicationDisabledUntil = timeoutUntil,
    )

    private fun role(id: Long, position: Int) =
        GuildRole(position, "role$id", id, 0, false, 0L, false, false, null, null, null)

    private fun guild(id: Long, name: String): ApiGuild = gson.fromJson(
        """{"id":$id,"name":"$name","owner_id":9,"unavailable":false,"features":[],"member_count":10}""",
        ApiGuild::class.java,
    )

    private fun joinedGuild(id: Long): ApiGuild = gson.fromJson(
        """{"id":$id,"name":"Joined","owner_id":9,"unavailable":false,"features":[],"joined_at":"2020-01-01T00:00:00"}""",
        ApiGuild::class.java,
    )

    private fun guildWithChannel(): ApiGuild = gson.fromJson(
        """{"id":1,"name":"Guild","owner_id":9,"unavailable":false,"features":[],"channels":[{"id":10,"type":0,"guild_id":1,"name":"general"}]}""",
        ApiGuild::class.java,
    )

    private fun twoChannelGuild(): ApiGuild = gson.fromJson(
        """{"id":1,"name":"Guild","owner_id":9,"unavailable":false,"features":[],"roles":[{"id":1,"name":"@everyone","permissions":3072,"position":0,"color":0,"hoist":false,"managed":false,"mentionable":false}],"members":[{"guild_id":1,"user":{"id":7,"username":"me","discriminator":"0000"},"roles":[],"pending":false,"joined_at":"2020-01-01T00:00:00"}],"channels":[{"id":10,"type":0,"guild_id":1,"name":"general"},{"id":11,"type":0,"guild_id":1,"name":"other"}]}""",
        ApiGuild::class.java,
    )

    /** A stage channel whose only member row has never joined, i.e. a lurker. */
    private fun lurkerGuild(): ApiGuild = gson.fromJson(
        """{"id":1,"name":"Guild","owner_id":9,"unavailable":false,"features":[],"roles":[{"id":1,"name":"@everyone","permissions":4294968320,"position":0,"color":0,"hoist":false,"managed":false,"mentionable":false}],"members":[{"guild_id":1,"user":{"id":7,"username":"me","discriminator":"0000"},"roles":[],"pending":false}],"channels":[{"id":10,"type":13,"guild_id":1,"name":"stage"}]}""",
        ApiGuild::class.java,
    )

    private fun stageInstance() =
        StageInstance(50, 1, 10, "topic", StageInstancePrivacyLevel.PUBLIC, null)

    private fun channel(id: Long): Channel = gson.fromJson(
        """{"id":$id,"type":0,"guild_id":1,"name":"channel$id"}""",
        Channel::class.java,
    )

    private fun message(id: Long, author: Long) = Message(
        id = id,
        channelId = 10,
        author = User(id = author, username = "user$author"),
    )

    private fun userPayload() = gson.fromJson(
        """{"user":{"id":7,"username":"me","discriminator":"0000"},"guilds":[],"private_channels":[]}""",
        ModelPayload::class.java,
    )

    companion object {
        private val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters().create()
    }
}

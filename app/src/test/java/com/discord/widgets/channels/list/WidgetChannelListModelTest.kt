package com.discord.widgets.channels.list

import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.nullserializable.NullSerializable
import com.discord.api.user.Collectibles
import com.discord.api.user.Nameplate
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.api.voice.state.VoiceState
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.domain.ModelPayload
import com.discord.models.member.GuildMember
import com.discord.models.user.CoreUser
import com.discord.stores.Dispatcher
import com.discord.stores.StoreChannels
import com.discord.stores.StoreThreadsActiveJoined.ActiveJoinedThread
import com.discord.stores.StoreUser
import com.discord.stores.StoreV2DispatchHandler
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.discord.widgets.channels.list.items.ChannelListItem
import com.discord.widgets.channels.list.items.ChannelListItemCategory
import com.discord.widgets.channels.list.items.ChannelListItemPrivate
import com.discord.widgets.channels.list.items.ChannelListItemTextChannel
import com.discord.widgets.channels.list.items.ChannelListItemThread
import com.discord.widgets.channels.list.items.ChannelListItemVoiceChannel
import com.discord.widgets.channels.list.items.ChannelListItemVoiceUser
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rx.Observable
import rx.schedulers.TestScheduler
import rx.subjects.BehaviorSubject

/**
 * Covers the row-building rules that are easy to break and impossible to see without a device:
 * ordering, the collapse/mute hide rules, and the thread spine.
 */
class WidgetChannelListModelTest {

    @Test
    fun `voice members load in batches before rows exist and stay subscribed until released`() {
        val template = voiceUser(channel(10, Channel.GUILD_VOICE))
        val initial = (1L..205L).associateWith { template.voiceState.copy(userId = it) }
        val states = BehaviorSubject.create(initial)
        val ready = BehaviorSubject.create(false)
        val requests = mutableListOf<List<Long>>()
        val changes = mutableListOf<Pair<Set<Long>, Set<Long>>>()
        val observed = mutableListOf<Map<Long, VoiceState>>()
        val subscription = WidgetChannelListModel.VoiceStates.observeWithMembers(
            states, ready,
            updateSubscriptions = { added, removed -> changes += added to removed },
            requestMembers = { requests += it; true },
        ).subscribe { observed += it }

        assertEquals(initial, observed.last())
        assertEquals(initial.keys to emptySet<Long>(), changes.single())
        assertTrue(requests.isEmpty())
        ready.onNext(true)
        assertEquals(listOf(100, 100, 5), requests.map { it.size })
        assertEquals(initial.keys, requests.flatten().toSet())

        // Muting or another cache publication must not re-request every voice participant.
        states.onNext(initial.mapValues { (_, state) -> state.copy(selfMute = true) })
        assertEquals(3, requests.size)
        assertEquals(1, changes.size)
        val joined = initial + (206L to template.voiceState.copy(userId = 206L))
        states.onNext(joined)
        assertEquals(listOf(206L), requests.last())
        assertEquals(setOf(206L) to emptySet<Long>(), changes.last())
        states.onNext(joined - 1L)
        assertEquals(emptySet<Long>() to setOf(1L), changes.last())

        requests.clear()
        ready.onNext(false)
        assertTrue(requests.isEmpty())
        ready.onNext(true)
        assertEquals(listOf(100, 100, 5), requests.map { it.size })
        assertEquals(joined.keys - 1L, requests.flatten().toSet())
        subscription.unsubscribe()
        assertEquals(emptySet<Long>() to (joined.keys - 1L), changes.last())
        val count = requests.size
        states.onNext(initial)
        assertEquals(count, requests.size)
    }

    @Test
    fun `rejected voice member requests retry and hydrated users produce rows`() {
        val member = voiceUser(channel(10, Channel.GUILD_VOICE))
        val states = BehaviorSubject.create(mapOf(1L to member.voiceState))
        var accepted = false
        var attempts = 0
        var observed = emptyMap<Long, VoiceState>()
        val subscription = WidgetChannelListModel.VoiceStates.observeWithMembers(
            states, Observable.just(true),
            updateSubscriptions = { _, _ -> },
            requestMembers = { attempts++; accepted },
        ).subscribe { observed = it }
        assertEquals(1, attempts)
        accepted = true
        states.onNext(states.value)
        assertEquals(2, attempts)
        states.onNext(states.value)
        assertEquals(2, attempts)
        val channels = mapOf(10L to member.channel)
        val permissions = mapOf(10L to (Permission.VIEW_CHANNEL or Permission.CONNECT))
        assertTrue(WidgetChannelListModel.VoiceStates.createVoiceStates(
            observed, emptyMap(), emptyMap(), channels, emptySet(), permissions,
        ).isEmpty())
        assertEquals(listOf(member), WidgetChannelListModel.VoiceStates.createVoiceStates(
            observed, mapOf(1L to member.user), mapOf(1L to member.computed), channels, emptySet(), permissions,
        )[10L])
        subscription.unsubscribe()
    }

    @Test
    fun `cached thread connectors still match their singleton cases`() {
        val spines = listOf(
            WidgetChannelListModel.ThreadSpineType.Single,
            WidgetChannelListModel.ThreadSpineType.Start,
            WidgetChannelListModel.ThreadSpineType.Middle,
            WidgetChannelListModel.ThreadSpineType.End,
        )
        val rows = spines.mapIndexed { index, spine ->
            ChannelListItemThread(channel(100L + index, Channel.PUBLIC_THREAD),
                false, 0, false, false, false, false, false).apply { spineType = spine }
        }
        val empty = WidgetChannelListModel(null, emptyList())
        Persister("thread-spine-fixture", empty, preload = false)
            .set(WidgetChannelListModel(null, rows), persist = true)
        val restored = Persister("thread-spine-fixture", empty, preload = false).get()
            .items.filterIsInstance<ChannelListItemThread>()
        assertEquals(rows.size, restored.size)
        rows.zip(restored).forEach { (original, decoded) ->
            assertNotSame(original.spineType, decoded.spineType)
            assertEquals(original.spineType, decoded.spineType)
            assertEquals(original.key, decoded.key)
        }
    }

    @Test
    fun `emits categories ahead of their children and uncategorised channels first`() {
        val channels = listOf(
            channel(30, Channel.CATEGORY, position = 2, name = "later"),
            channel(31, Channel.GUILD_TEXT, position = 2, parentId = 30),
            channel(20, Channel.CATEGORY, position = 1, name = "first"),
            channel(22, Channel.GUILD_TEXT, position = 2, parentId = 20),
            channel(21, Channel.GUILD_TEXT, position = 1, parentId = 20),
            channel(10, Channel.GUILD_TEXT, position = 9),
        )

        assertEquals(listOf(10L, 20L, 21L, 22L, 30L, 31L), build(channels).channelIds())
    }

    @Test
    fun `drops channels the user cannot view and categories left empty`() {
        val channels = listOf(
            channel(1, Channel.CATEGORY),
            channel(2, Channel.GUILD_TEXT, parentId = 1),
            channel(3, Channel.GUILD_VOICE, position = 1),
        )
        val permissions = mapOf(
            1L to Permission.VIEW_CHANNEL,
            2L to Permission.NONE,
            3L to (Permission.VIEW_CHANNEL or Permission.CONNECT),
        )

        assertEquals(listOf(3L), build(channels, permissions).channelIds())
    }

    @Test
    fun `keeps a category the user can manage even when it has no visible children`() {
        val channels = listOf(
            channel(1, Channel.CATEGORY),
            channel(2, Channel.GUILD_TEXT, parentId = 1),
        )
        val permissions = mapOf(
            1L to (Permission.VIEW_CHANNEL or Permission.MANAGE_CHANNELS),
            2L to Permission.NONE,
        )

        val items = build(channels, permissions)
        assertEquals(listOf(1L), items.channelIds())
        assertTrue((items.single() as ChannelListItemCategory).canManageChannels)
    }

    @Test
    fun `a collapsed category keeps only mentioned, unread or selected children`() {
        val channels = listOf(
            channel(1, Channel.CATEGORY),
            channel(2, Channel.GUILD_TEXT, parentId = 1),
            channel(3, Channel.GUILD_TEXT, parentId = 1, position = 1),
            channel(4, Channel.GUILD_TEXT, parentId = 1, position = 2),
        )

        val items = build(
            channels,
            collapsedCategories = setOf(1L),
            unreadChannelIds = setOf(3L),
            selectedChannel = channels[1],
        )

        assertEquals(listOf(1L, 2L, 3L), items.channelIds())
    }

    @Test
    fun `a collapsed category keeps a voice channel that somebody is in`() {
        val channels = listOf(
            channel(1, Channel.CATEGORY),
            channel(2, Channel.GUILD_VOICE, parentId = 1),
            channel(3, Channel.GUILD_VOICE, parentId = 1, position = 1),
        )

        val items = build(
            channels,
            collapsedCategories = setOf(1L),
            voiceStates = mapOf(3L to listOf(voiceUser(channels[2]))),
        )

        assertEquals(listOf(1L, 3L), items.channelIds())
        val voiceRow = items.filterIsInstance<ChannelListItemVoiceChannel>().single()
        assertEquals(1, voiceRow.numUsersConnected)
        // The participant row is emitted directly beneath its channel.
        assertEquals(ChannelListItem.TYPE_CHANNEL_VOICE_USER, items.last().type)
    }

    @Test
    fun `muted channels are hidden only when the guild hides muted channels`() {
        val channels = listOf(channel(2, Channel.GUILD_TEXT))
        val muted = ModelNotificationSettings(
            guildId = GUILD_ID,
            channelOverrides = listOf(
                ModelNotificationSettings.ChannelOverride(channelId = 2L, muted = true),
            ),
        )

        assertEquals(
            listOf(2L),
            build(channels, notificationSettings = muted, hideMutedChannels = false).channelIds(),
        )
        assertEquals(
            emptyList<Long>(),
            build(channels, notificationSettings = muted, hideMutedChannels = true).channelIds(),
        )
    }

    @Test
    fun `the mfa notice is the first row`() {
        val items = build(
            listOf(channel(2, Channel.GUILD_TEXT)),
            unelevated = true,
            ableToInstantInvite = true,
        )

        assertEquals(
            listOf(ChannelListItem.TYPE_MFA_NOTICE, ChannelListItem.TYPE_CHANNEL),
            items.map { it.type },
        )
    }

    @Test
    fun `spine type describes a thread's position among its siblings`() {
        assertEquals(
            WidgetChannelListModel.ThreadSpineType.Single,
            WidgetChannelListModel.spineTypeFor(position = 0, threadCount = 1),
        )
        assertEquals(
            WidgetChannelListModel.ThreadSpineType.Start,
            WidgetChannelListModel.spineTypeFor(position = 0, threadCount = 3),
        )
        assertEquals(
            WidgetChannelListModel.ThreadSpineType.Middle,
            WidgetChannelListModel.spineTypeFor(position = 1, threadCount = 3),
        )
        assertEquals(
            WidgetChannelListModel.ThreadSpineType.End,
            WidgetChannelListModel.spineTypeFor(position = 2, threadCount = 3),
        )
    }

    @Test
    fun `private rows carry mute and streaming state and end with the nav spacer`() {
        val dm = channel(5, Channel.DM, name = "dm")
        val model = WidgetChannelListModel.privateListBuilder(
            channels = listOf(dm),
            selectedChannelId = 5L,
            presences = emptyMap(),
            mentionCounts = mapOf(5L to 3),
            streamingUserIds = emptySet(),
            notificationSettings = ModelNotificationSettings(
                guildId = 0L,
                channelOverrides = listOf(
                    ModelNotificationSettings.ChannelOverride(channelId = 5L, muted = true),
                ),
            ),
        )

        assertEquals(
            listOf(ChannelListItem.TYPE_CHANNEL_PRIVATE, ChannelListItem.TYPE_BOTTOM_NAV_SPACE),
            model.items.map { it.type },
        )
        // The DM list has no jump bars, so every private row reports itself as read.
        assertFalse(model.showEmptyState)
    }

    @Test
    fun `message requests are grouped above regular direct messages`() {
        val dm = channel(5, Channel.DM, name = "dm")
        val request = channel(6, Channel.DM, name = "request").copy(isMessageRequest = true)
        val spam = channel(7, Channel.DM, name = "spam").copy(isMessageRequest = true, isSpam = true)

        val model = WidgetChannelListModel.privateListBuilder(
            channels = listOf(dm, request, spam),
            selectedChannelId = 0,
            presences = emptyMap(),
            mentionCounts = emptyMap(),
            streamingUserIds = emptySet(),
            notificationSettings = ModelNotificationSettings(),
        )

        assertEquals(2, model.messageRequestCount)
        assertEquals(listOf(5L), model.items.filterIsInstance<ChannelListItemPrivate>().map { it.channel.id })
    }

    @Test
    fun `ready users hydrate the shipping direct message row nameplate`() {
        val scheduler = TestScheduler()
        val deck = ObservationDeck()
        val dispatcher = Dispatcher(scheduler)
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val channels = StoreChannels(dispatcher, deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(users, channels), deck))
        val nameplate = Nameplate("legacy", 9, "plate", "sky")
        val payload = ModelPayload().apply {
            me = com.discord.api.user.User(1, "me")
            privateChannels = listOf(channel(5, Channel.DM, name = null, recipientIds = listOf(7)))
            this.users = listOf(
                com.discord.api.user.User(
                    7,
                    "recipient",
                    collectibles = NullSerializable.Value(Collectibles(nameplate)),
                    globalName = "Recipient Display",
                ),
            )
        }
        dispatcher.schedule {
            users.handleConnectionOpen(payload)
            channels.handleConnectionOpen(payload)
        }
        scheduler.triggerActions()

        val model = WidgetChannelListModel.privateListBuilder(
            channels.getChannelsForGuild(0).values.toList(),
            selectedChannelId = 0,
            presences = emptyMap(),
            mentionCounts = emptyMap(),
            streamingUserIds = emptySet(),
            notificationSettings = ModelNotificationSettings(),
            users = users.getUsers(),
        )

        val row = model.items.filterIsInstance<ChannelListItemPrivate>().single()
        assertEquals(nameplate, row.nameplate)
        assertEquals("Recipient Display", row.displayName)
        assertEquals(
            "https://cdn.discordapp.com/media/v1/collectibles-shop/9/static",
            row.nameplate?.staticImageUrl(),
        )

        dispatcher.schedule { users.handleUserUpdated(com.discord.api.user.User(7, "renamed")) }
        scheduler.triggerActions()
        assertEquals(nameplate, users.getUsers()[7]?.collectibles?.nameplate)
    }

    @Test
    fun `a thread is dropped when its parent channel is hidden`() {
        val parent = channel(2, Channel.GUILD_TEXT)
        val thread = channel(3, Channel.PUBLIC_THREAD, parentId = 2)
        val items = build(
            listOf(parent),
            permissions = mapOf(2L to Permission.NONE, 3L to Permission.VIEW_CHANNEL),
            activeJoinedThreads = mapOf(2L to mapOf(3L to thread)),
        )

        assertTrue(items.none { it is ChannelListItemThread })
    }

    @Test
    fun `followed forum post uses parent permission and appears under forum`() {
        val forum = channel(30, Channel.GUILD_FORUM)
        val post = channel(31, Channel.PUBLIC_THREAD, parentId = forum.id)
        val items = build(
            listOf(forum),
            permissions = mapOf(forum.id to Permission.VIEW_CHANNEL),
            activeJoinedThreads = mapOf(forum.id to mapOf(post.id to post)),
        )

        assertTrue(items.filterIsInstance<ChannelListItemThread>().any { it.channel.id == post.id })
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun List<ChannelListItem>.channelIds(): List<Long> = mapNotNull { item ->
        when (item) {
            is ChannelListItemCategory -> item.channel.id
            is ChannelListItemTextChannel -> item.channel.id
            is ChannelListItemVoiceChannel -> item.channel.id
            is ChannelListItemThread -> item.channel.id
            else -> null
        }
    }

    private fun build(
        channels: List<Channel>,
        permissions: Map<Long, Long> = channels.associate {
            it.id to (Permission.VIEW_CHANNEL or Permission.CONNECT)
        },
        collapsedCategories: Set<Long> = emptySet(),
        unreadChannelIds: Set<Long> = emptySet(),
        selectedChannel: Channel? = null,
        voiceStates: Map<Long, List<ChannelListItemVoiceUser>> = emptyMap(),
        notificationSettings: ModelNotificationSettings = ModelNotificationSettings(guildId = GUILD_ID),
        hideMutedChannels: Boolean = false,
        unelevated: Boolean = false,
        ableToInstantInvite: Boolean = false,
        activeJoinedThreads: Map<Long, Map<Long, Channel>> = emptyMap(),
    ): List<ChannelListItem> = WidgetChannelListModel.guildListBuilder(
        selectedGuildId = GUILD_ID,
        guild = WidgetChannelListModel.GuildInfo(
            guild = null,
            everyoneRole = null,
            guildRoles = null,
            notificationSettings = notificationSettings,
            hideMutedChannels = hideMutedChannels,
            channelPermissions = permissions,
            ableToInstantInvite = ableToInstantInvite,
            unelevated = unelevated,
        ),
        guildChannels = channels.associateBy(Channel::id),
        activeJoinedGuildThreads = activeJoinedThreads.mapValues { (_, threads) ->
            threads.mapValues { (_, thread) ->
                ActiveJoinedThread(
                    channel = thread,
                    joinTimestamp = UtcDateTime(0L),
                    muted = false,
                )
            }
        },
        joinedThreads = emptyMap(),
        channelsWithActiveThreads = emptySet(),
        selectedChannel = selectedChannel,
        selectedVoiceChannelId = 0L,
        voiceStates = voiceStates,
        mentionCounts = emptyMap(),
        unreadChannelIds = unreadChannelIds,
        collapsedCategories = collapsedCategories,
    )

    private fun voiceUser(channel: Channel) =
        ChannelListItemVoiceUser(
            channel = channel,
            voiceState = VoiceState(
                userId = 1L,
                suppress = false,
                sessionId = null,
                selfMute = false,
                selfDeaf = false,
                selfVideo = false,
                selfStream = false,
                mute = false,
                guildId = GUILD_ID,
                deaf = false,
                member = null,
                requestToSpeakTimestamp = null,
                channelId = channel.id,
            ),
            user = CoreUser(id = 1L, username = "user", discriminator = 1),
            computed = GuildMember(
                color = 0,
                hoistRoleId = 0L,
                roles = null,
                nick = null,
                premiumSince = null,
                pending = false,
                joinedAt = null,
                guildId = GUILD_ID,
                userId = 1L,
            ),
            isApplicationStreaming = false,
            hasChannelConnectPermission = true,
        )

    private fun channel(
        id: Long,
        type: Int,
        position: Int = 0,
        parentId: Long = 0,
        name: String? = "channel-$id",
        lastMessageId: Long = 0,
        recipients: List<com.discord.api.user.User>? = null,
        recipientIds: List<Long>? = null,
    ) = Channel(
        topic = null, type = type, guildId = GUILD_ID, name = name,
        lastMessageId = lastMessageId,
        id = id, ownerId = 0, recipientIds = recipientIds, recipients = recipients, position = position,
        permissionOverwrites = null, userLimit = 0, bitrate = 0, icon = null,
        originChannelId = 0, applicationId = 0, nicks = null, nsfw = false,
        parentId = parentId, memberListId = null, rateLimitPerUser = 0,
        defaultAutoArchiveDuration = null, rtcRegion = null, flags = null, guildHashes = null,
        availableTags = null, appliedTags = null, threadMetadata = null, messageCount = null,
        memberCount = null, memberIdsPreview = null, member = null,
    )

    private companion object {
        private const val GUILD_ID = 1L

        @JvmStatic
        @BeforeClass
        fun initializePersister() {
            Persister.init(Files.createTempDirectory("channel-list-model").toFile(), Observable.never())
        }
    }
}

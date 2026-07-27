package com.discord.stores

import co.discord.media_engine.VideoInputDeviceDescription
import com.discord.api.channel.Channel
import com.discord.api.guild.GuildFeature
import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionOverwrite
import com.discord.api.science.Science
import com.discord.api.thread.ThreadMetadata
import com.discord.models.guild.Guild
import com.discord.utilities.analytics.AnalyticsSink
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.time.Clock
import com.discord.widgets.home.WidgetHome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.schedulers.Schedulers
import rx.plugins.RxJavaHooks
import rx.schedulers.TestScheduler
import java.util.concurrent.TimeUnit

/**
 * Covers the snapshots `StoreAnalytics` takes on the store thread, since those are what every
 * reference call site is really asking for.
 */
class StoreAnalyticsTest {

    private class RecordingSink : AnalyticsSink {
        val events = mutableListOf<Science.Event>()
        override fun deliver(token: String?, events: List<Science.Event>, onFailure: () -> Unit) {
            this.events += events
        }
    }

    private val guild = Guild(
        id = GUILD_ID,
        ownerId = ME_ID,
        features = setOf(GuildFeature.VIP_REGIONS),
    )

    private val textChannel = channel(id = CHANNEL_ID, type = Channel.GUILD_TEXT)
    private val voiceChannel = channel(id = 300L, type = Channel.GUILD_VOICE)
    private val thread = channel(
        id = 400L,
        type = Channel.PUBLIC_THREAD,
        parentId = CHANNEL_ID,
        threadMetadata = ThreadMetadata(
            archived = true,
            archiveTimestamp = null,
            autoArchiveDuration = 1440,
            locked = false,
        ),
    )

    private val stores = object : StoreAnalytics.Stores {
        override fun getChannel(channelId: Long): Channel? =
            listOf(textChannel, voiceChannel, thread).firstOrNull { it.id == channelId }

        override fun getChannelsForGuild(guildId: Long) = listOf(textChannel, voiceChannel)
        override fun getSelectedChannelId() = CHANNEL_ID
        override fun getGuild(guildId: Long) = guild.takeIf { it.id == guildId }
        override fun getApproximateMemberCount(guildId: Long) = 42
        override fun getGuildRoleCount(guildId: Long) = 3
        override fun getGuildMemberRoleCount(guildId: Long, userId: Long) = 2
        override fun getGuildPermissions(guildId: Long) = 8L
        override fun getChannelPermissions(channelId: Long) = 16L
        override fun getMeId() = ME_ID
        override fun getThreadMessageCount(channelId: Long) = 120
    }

    private val sink = RecordingSink()
    private val store = StoreAnalytics(stores, Dispatcher(Schedulers.immediate()), Clock { 1_000L })

    @Before
    fun setUp() {
        AnalyticsSink.install(sink)
        AnalyticsTracker.tracker.clear()
        // A fingerprinted session is enough for the tracker to deliver; no auth token is needed.
        store.handleFingerprint("fingerprint")
    }

    @After
    fun tearDown() {
        AnalyticsSink.reset()
        AnalyticsTracker.tracker.clear()
        AnalyticsTracker.tracker.setTrackingData(null, fingerprinted = false)
    }

    @Test
    fun `guild viewed carries the guild snapshot`() {
        store.trackGuildViewed(GUILD_ID)

        val properties = deliveredProperties("guild_viewed")
        assertEquals(GUILD_ID, properties["guild_id"])
        assertEquals(42, properties["guild_size_total"])
        assertEquals(2, properties["guild_num_channels"])
        assertEquals(1, properties["guild_num_text_channels"])
        assertEquals(1, properties["guild_num_voice_channels"])
        assertEquals(3, properties["guild_num_roles"])
        assertEquals(2, properties["guild_member_num_roles"])
        assertEquals(8L, properties["guild_member_perms"])
        assertEquals(true, properties["guild_is_vip"])
        assertEquals(false, properties["is_pending"])
        assertEquals(false, properties["preview_enabled"])
        assertTrue("guild_is_preview_enabled" !in properties)
    }

    @Test
    fun `channel opened carries the view type and the location`() {
        store.trackChannelOpened(
            CHANNEL_ID,
            ChannelAnalyticsViewType.PEEK_VIEW,
            SelectedChannelAnalyticsLocation.THREAD_BROWSER,
        )

        val properties = deliveredProperties("channel_opened")
        assertEquals(CHANNEL_ID, properties["channel_id"])
        assertEquals(Channel.GUILD_TEXT, properties["channel_type"])
        assertEquals(16L, properties["channel_member_perms"])
        assertEquals("Peek View", properties["channel_view"])
        assertEquals("Thread Browser", properties["location"])
        assertEquals(false, properties["channel_is_nsfw"])
    }

    @Test
    fun `channel opened omits the location when the selection had none`() {
        store.trackChannelOpened(CHANNEL_ID, ChannelAnalyticsViewType.FULL_VIEW, null)

        val properties = deliveredProperties("channel_opened")
        assertEquals("Full View", properties["channel_view"])
        assertTrue("location" !in properties)
    }

    @Test
    fun `a channel that denies everyone VIEW_CHANNEL reports as hidden`() {
        val hidden = channel(
            id = 500L,
            type = Channel.GUILD_TEXT,
            overwrites = listOf(
                PermissionOverwrite(GUILD_ID, PermissionOverwrite.Type.ROLE, 0L, Permission.VIEW_CHANNEL),
            ),
        )
        val hiddenStores = object : StoreAnalytics.Stores {
            override fun getChannel(channelId: Long) = hidden
            override fun getSelectedChannelId() = hidden.id
        }
        val hiddenStore = StoreAnalytics(hiddenStores, Dispatcher(Schedulers.immediate()), Clock { 0L })

        hiddenStore.handleFingerprint("fingerprint")
        hiddenStore.trackChannelOpened(hidden.id, ChannelAnalyticsViewType.FULL_VIEW, null)

        assertEquals(true, deliveredProperties("channel_opened")["channel_hidden"])
    }

    @Test
    fun `a thread reports its own shape and caps its message count`() {
        store.trackChannelOpened(thread.id, ChannelAnalyticsViewType.FULL_VIEW, null)

        val properties = deliveredProperties("channel_opened")
        assertEquals(CHANNEL_ID, properties["parent_id"])
        assertEquals(50, properties["thread_approximate_message_count"])
        assertEquals(true, properties["thread_archived"])
        assertEquals(false, properties["thread_locked"])
        assertEquals(1440, properties["thread_auto_archive_duration_minutes"])
    }

    @Test
    fun `an unknown guild or channel produces no event`() {
        store.trackGuildViewed(999L)
        store.trackChannelOpened(999L, ChannelAnalyticsViewType.FULL_VIEW, null)

        assertTrue(delivered().isEmpty())
    }

    @Test
    fun `an authenticated session without an analytics token holds events back`() {
        // Auth token but no analytics token yet: neither the fingerprinted nor the authenticated
        // branch applies, so the backend could not attribute the event and it stays queued.
        val queued = StoreAnalytics(stores, Dispatcher(Schedulers.immediate()), Clock { 0L })
        queued.handleAuthToken("auth-token")
        queued.trackGuildViewed(GUILD_ID)
        queued.handleAuthToken("auth-token")

        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `camera callback uses the media engine DTO`() {
        val parameter = StoreAnalytics::class.java
            .getDeclaredMethod("handleVideoInputDeviceSelected", VideoInputDeviceDescription::class.java)
            .parameterTypes.single()

        assertEquals(VideoInputDeviceDescription::class.java, parameter)
    }

    @Test
    fun `home waits two seconds before tracking the first screen`() {
        val scheduler = TestScheduler()
        RxJavaHooks.setOnComputationScheduler { scheduler }
        try {
            store.init(testContext())
            store.appUiViewed(WidgetHome::class.java)

            scheduler.advanceTimeBy(1999, TimeUnit.MILLISECONDS)
            store.handleFingerprint("fingerprint")
            assertTrue(sink.events.isEmpty())

            scheduler.advanceTimeBy(1, TimeUnit.MILLISECONDS)
            store.handleFingerprint("fingerprint")
            assertTrue(sink.events.isNotEmpty())
        } finally {
            RxJavaHooks.reset()
        }
    }

    private fun delivered(): List<Science.Event> {
        // setTrackingData drains synchronously, which is how a test avoids the 1.5 s batch timer.
        store.handleFingerprint("fingerprint")
        return sink.events
    }

    private fun deliveredProperties(type: String): Map<String, Any> =
        delivered()
            .filterIsInstance<Science.Event.MapObject>()
            .first { it.type == type }
            .properties

    private fun channel(
        id: Long,
        type: Int,
        parentId: Long = 0,
        overwrites: List<PermissionOverwrite>? = null,
        threadMetadata: ThreadMetadata? = null,
    ) = Channel(
        topic = null, type = type, guildId = GUILD_ID, name = "channel", lastMessageId = 0, id = id,
        ownerId = 0, recipientIds = null, recipients = null, position = 0,
        permissionOverwrites = overwrites, userLimit = 0, bitrate = 0, icon = null,
        originChannelId = 0, applicationId = 0, nicks = null, nsfw = false,
        parentId = parentId, memberListId = null, rateLimitPerUser = 0, defaultAutoArchiveDuration = null,
        rtcRegion = null, flags = null, guildHashes = null, availableTags = null,
        appliedTags = null, threadMetadata = threadMetadata, messageCount = null,
        memberCount = null, memberIdsPreview = null, member = null,
    )

    private companion object {
        const val GUILD_ID = 100L
        const val CHANNEL_ID = 200L
        const val ME_ID = 7L
    }
}

package com.discord.utilities.analytics

import com.discord.analytics.generated.traits.TrackGuild
import com.discord.analytics.generated.traits.TrackNetworkMetadata
import com.discord.analytics.generated.events.network_action.TrackNetworkActionUserLogin
import com.discord.api.science.Science
import com.discord.api.activity.ActivityPlatform
import com.discord.models.member.GuildMember
import com.discord.utilities.time.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the batching/throttling pipeline and, most importantly, that **nothing is transmitted
 * unless a sink is explicitly installed**.
 */
class AnalyticsPipelineTest {

    private class RecordingSink : AnalyticsSink {
        val batches = mutableListOf<List<Science.Event>>()
        val tokens = mutableListOf<String?>()
        var failNext = false

        val events: List<Science.Event> get() = batches.flatten()

        override fun deliver(token: String?, events: List<Science.Event>, onFailure: () -> Unit) {
            batches += events
            tokens += token
            if (failNext) {
                failNext = false
                onFailure()
            }
        }
    }

    private var now = 1_000L
    private val clock = Clock { now }

    @After
    fun tearDown() {
        AnalyticsSink.reset()
        AnalyticsUtils.guildTraitResolver = null
    }

    @Test
    fun `the default sink transmits nothing`() {
        assertSame(AnalyticsSink.Disabled, AnalyticsSink.current)

        val tracker = AnalyticsUtils.Tracker(clock)
        tracker.track("app_first_login", mapOf("platform" to "Android"))
        tracker.setTrackingData("token")

        // Drained into the disabled sink and dropped; the queue is empty and nothing left the device.
        assertEquals(0, tracker.queuedEventCount())
    }

    @Test
    fun `events are held until the session has tracking data`() {
        val sink = RecordingSink()
        AnalyticsSink.install(sink)
        val tracker = AnalyticsUtils.Tracker(clock)

        tracker.track("app_landing_viewed")
        assertEquals(1, tracker.queuedEventCount())
        assertTrue(sink.batches.isEmpty())

        tracker.setTrackingData(null, fingerprinted = true)

        assertEquals(listOf("app_landing_viewed"), sink.events.map(Science.Event::type))
        assertEquals(listOf<String?>(null), sink.tokens)
    }

    @Test
    fun `a blank analytics token does not count as authenticated`() {
        val sink = RecordingSink()
        AnalyticsSink.install(sink)
        val tracker = AnalyticsUtils.Tracker(clock)

        tracker.track("app_landing_viewed")
        tracker.setTrackingData("   ")

        assertTrue(sink.batches.isEmpty())
        assertEquals(1, tracker.queuedEventCount())
    }

    @Test
    fun `a failed delivery re-queues the batch`() {
        val sink = RecordingSink()
        AnalyticsSink.install(sink)
        val tracker = AnalyticsUtils.Tracker(clock)

        tracker.track("app_landing_viewed")
        sink.failNext = true
        tracker.setTrackingData("token")

        assertEquals(1, tracker.queuedEventCount())

        tracker.setTrackingData("token")
        assertEquals(0, tracker.queuedEventCount())
        assertEquals(2, sink.batches.size)
    }

    @Test
    fun `a throttled event fires once per window per key`() {
        val sink = RecordingSink()
        AnalyticsSink.install(sink)
        val tracker = AnalyticsUtils.Tracker(clock)
        tracker.setTrackingData("token")

        tracker.track("guild_viewed" to 1L, AnalyticsTracker.THROTTLE_LONG) { mapOf("guild_id" to 1L) }
        tracker.track("guild_viewed" to 1L, AnalyticsTracker.THROTTLE_LONG) { mapOf("guild_id" to 1L) }
        // A different guild is a different key and is not throttled.
        tracker.track("guild_viewed" to 2L, AnalyticsTracker.THROTTLE_LONG) { mapOf("guild_id" to 2L) }
        tracker.setTrackingData("token")

        assertEquals(2, sink.events.size)

        now += AnalyticsTracker.THROTTLE_LONG + 1
        tracker.track("guild_viewed" to 1L, AnalyticsTracker.THROTTLE_LONG) { mapOf("guild_id" to 1L) }
        tracker.setTrackingData("token")

        assertEquals(3, sink.events.size)
    }

    @Test
    fun `a throttled event does not build its properties while throttled`() {
        AnalyticsSink.install(RecordingSink())
        val tracker = AnalyticsUtils.Tracker(clock)
        tracker.setTrackingData("token")
        var built = 0

        repeat(3) {
            tracker.track("channel_opened" to 7L, AnalyticsTracker.THROTTLE_LONG) {
                built++
                emptyMap()
            }
        }

        assertEquals(1, built)
    }

    @Test
    fun `guild traits are filled from the store graph before delivery`() {
        val sink = RecordingSink()
        AnalyticsSink.install(sink)
        AnalyticsUtils.guildTraitResolver = { it.copy(isMember = true, isOwner = true) }
        val tracker = AnalyticsUtils.Tracker(clock)
        tracker.setTrackingData("token")

        val schema = TrackImpressionWithGuild().apply { trackGuild = TrackGuild(guildId = 5L) }
        tracker.track(schema)
        tracker.setTrackingData("token")

        assertEquals(TrackGuild(guildId = 5L, isMember = true, isOwner = true), schema.trackGuild)
    }

    @Test
    fun `a network action schema carries the response metadata`() {
        val schema = TrackNetworkActionUserLogin(inviteCode = "abc", isMultiAccount = false)
        assertNull(schema.trackNetworkMetadata)
        assertEquals("network_action_user_login", schema.analyticsSchemaTypeName)

        schema.trackNetworkMetadata = TrackNetworkMetadata(
            url = "/auth/login",
            requestMethod = "POST",
            statusCode = 200L,
        )

        assertEquals(200L, schema.trackNetworkMetadata?.statusCode)
    }

    @Test
    fun `user sheet analytics carries stage profile traits`() {
        val sink = RecordingSink()
        AnalyticsSink.install(sink)
        AnalyticsTracker.tracker.setTrackingData("token")

        AnalyticsTracker.openUserSheet(
            hasPremiumCustomization = true,
            gameName = "Game",
            gamePlatform = ActivityPlatform.XBOX,
            guildMember = GuildMember(
                color = 0,
                hoistRoleId = 0,
                roles = null,
                nick = "Nick",
                premiumSince = null,
                pending = false,
                joinedAt = null,
                guildId = 1,
                userId = 2,
                avatarHash = "avatar",
                bannerHash = "banner",
                bio = "bio",
            ),
        )
        AnalyticsTracker.tracker.setTrackingData("token")

        val event = sink.events.last() as Science.Event.MapObject
        assertEquals("open_popout", event.type)
        assertEquals("User Sheet", event.properties["type"])
        assertEquals("xbox", event.properties["game_platform"])
        assertEquals(true, event.properties["has_nickname"])
        assertEquals(true, event.properties["has_guild_member_avatar"])
        assertEquals(true, event.properties["has_guild_member_banner"])
        assertEquals(true, event.properties["has_guild_member_bio"])
    }

    @Test
    fun `guild boost promotion opened carries the system message location`() {
        val sink = RecordingSink()
        AnalyticsSink.install(sink)
        AnalyticsTracker.tracker.clear()
        AnalyticsTracker.tracker.setTrackingData("token")

        AnalyticsTracker.guildBoostPromotionOpened(
            guildId = 42L,
            locationTrait = Traits.Location(
                section = Traits.Location.Section.CHANNEL_TEXT_AREA,
                _object = Traits.Location.Obj.BOOST_GEM_ICON,
            ),
        )
        AnalyticsTracker.tracker.setTrackingData("token")

        val event = sink.events.single() as Science.Event.MapObject
        assertEquals("premium_guild_promotion_opened", event.type)
        assertEquals(42L, event.properties["guild_id"])
        assertEquals("Channel Text Area", event.properties["location_section"])
        assertEquals("Boost Gem Icon", event.properties["location_object"])
        assertFalse(event.properties.containsKey("channel_id"))
    }

    private class TrackImpressionWithGuild :
        com.discord.api.science.AnalyticsSchema,
        com.discord.analytics.generated.traits.TrackGuildReceiver {
        override val analyticsSchemaTypeName: String = "impression_test"
        override var trackGuild: TrackGuild? = null
    }
}

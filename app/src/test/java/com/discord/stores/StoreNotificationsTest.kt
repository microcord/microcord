package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.api.user.User
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.guild.Guild
import com.discord.models.message.Message
import com.discord.models.notification.NotificationSettingsV2
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.NotificationPolicy.Delivery
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.time.Clock
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreNotificationsTest {
    private val directory = Files.createTempDirectory("notifications").toFile()

    @Before fun setUp() = Persister.init(directory)
    @After fun tearDown() {
        RestAPI.reset()
        directory.deleteRecursively()
    }

    @Test fun suppressionMatrix() {
        val foreground = setOf(Delivery.FOREGROUND)
        assertEquals(foreground, evaluate())
        assertTrue(evaluate(message = message(authorId = ME)).isEmpty())
        assertTrue(evaluate(blockedOrIgnored = true).isEmpty())
        assertTrue(evaluate(selected = CHANNEL).isEmpty())
        assertEquals(foreground, evaluate(permissions = 0))
        assertTrue(evaluate(dnd = true).isEmpty())
        assertTrue(evaluate(settings = setting(muted = true)).isEmpty())
        assertEquals(foreground, evaluate(settings = setting(mobilePush = false)))
        assertTrue(evaluate(settings = setting(frequency = ModelNotificationSettings.FREQUENCY_NOTHING)).isEmpty())
        assertTrue(evaluate(settings = setting(frequency = ModelNotificationSettings.FREQUENCY_MENTIONS)).isEmpty())
        assertEquals(foreground, evaluate(
            message = message(mentions = listOf(User(ME, "me"))),
            settings = setting(frequency = ModelNotificationSettings.FREQUENCY_MENTIONS),
        ))
        assertTrue(evaluate(
            message = message(everyone = true),
            settings = setting(frequency = ModelNotificationSettings.FREQUENCY_MENTIONS, suppressEveryone = true),
        ).isEmpty())
        assertTrue(evaluate(
            message = message(roleIds = listOf(7)),
            roles = setOf(7),
            settings = setting(frequency = ModelNotificationSettings.FREQUENCY_MENTIONS, suppressRoles = true),
        ).isEmpty())
    }

    @Test fun threadParentOverrideAndVoiceConnectAreApplied() {
        val parent = channel(PARENT)
        val thread = channel(CHANNEL, Channel.PUBLIC_THREAD, parentId = PARENT)
        val settings = setting(overrides = listOf(ModelNotificationSettings.ChannelOverride(
            messageNotifications = ModelNotificationSettings.FREQUENCY_NOTHING,
            channelId = PARENT,
        )))
        assertTrue(NotificationPolicy.evaluate(input(channel = thread, parent = parent, settings = settings)).isEmpty())
        assertTrue(NotificationPolicy.evaluate(input(
            channel = channel(CHANNEL, Channel.GUILD_VOICE),
            permissions = Permission.VIEW_CHANNEL,
        )).isEmpty())
    }

    @Test fun storePersistsOnlyTogglesDeduplicatesTokenAndEnforcesFreshness() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        var now = 2_000_000L
        val candidates = mutableListOf<Pair<Long, Delivery>>()
        val registrations = mutableListOf<Pair<String, String?>>()
        val cleared = mutableListOf<Long>()
        val store = StoreNotifications(
            dispatcher, Clock { now }, deck, cleared::add,
            { message, delivery -> candidates += message.id to delivery },
            { auth, push, done -> registrations += auth to push; done(true) },
        )
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))

        dispatcher.schedule { store.restore() }
        scheduler.triggerActions()
        store.setNotificationSoundDisabled(true)
        scheduler.triggerActions()
        store.handleAuthToken("secret")
        store.handleRegistrationToken("push")
        store.handleRegistrationToken("push")
        dispatcher.schedule {
            store.handleChannelSelected(CHANNEL)
            store.handleMessageCreate(input(message = message(timestamp = now - 9_999)), true)
            store.handleMessageCreate(input(message = message(timestamp = now - 10_000)), true)
            store.handleMessageCreate(input(message = message(timestamp = now - 1)), false)
        }
        scheduler.triggerActions()

        assertEquals(listOf("secret" to "push"), registrations)
        assertEquals(listOf(CHANNEL), cleared)
        // The fresh foregrounded message banners, the stale one is dropped, and the fresh

        // backgrounded one goes to the system tray rather than being discarded.

        assertEquals(2, candidates.size)
        assertTrue(store.getSettings().isDisableSound)
        assertTrue(store.toString().contains("secret").not())

        Persister.persistAll()
        Persister.init(directory)
        val restored = StoreNotifications(dispatcher, Clock { now }, deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(restored), deck))
        dispatcher.schedule { restored.restore() }
        scheduler.triggerActions()
        assertTrue(restored.getSettings().isDisableSound)

        dispatcher.schedule { restored.handlePreLogout() }
        scheduler.triggerActions()
        assertEquals(NotificationSettingsV2(), restored.getSettings())
    }

    @Test fun defaultTokenRegistrationUsesDeviceEndpointAndUnregistersNullToken() {
        val requests = CopyOnWriteArrayList<RestAPIParams.UserDevices>()
        val api = Proxy.newProxyInstance(
            RestAPIInterface::class.java.classLoader,
            arrayOf(RestAPIInterface::class.java),
        ) { _, method, args ->
            when (method.name) {
                "userCreateDevice" -> {
                    requests += args!![0] as RestAPIParams.UserDevices
                    rx.Observable.empty<Void>()
                }
                "toString" -> "NotificationRestApi"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as RestAPIInterface
        RestAPI.install(api)
        val store = StoreNotifications(Dispatcher(TestScheduler()), Clock { 0 }, ObservationDeck())

        store.handleAuthToken("secret")
        store.handleRegistrationToken("push")
        await { requests.size == 1 }
        val persisted = StoreNotifications::class.java.getDeclaredField("pushTokenPersisted").apply {
            isAccessible = true
        }
        await { persisted.get(store) == "push" }
        store.handleRegistrationToken(null)
        await { requests.size == 2 }

        assertEquals(listOf("push", null), requests.map { it.token })
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("Timed out waiting for notification registration")
    }

    private fun evaluate(
        message: Message = message(),
        blockedOrIgnored: Boolean = false,
        selected: Long? = null,
        permissions: Long = Permission.VIEW_CHANNEL,
        roles: Set<Long> = emptySet(),
        dnd: Boolean = false,
        settings: ModelNotificationSettings = setting(),
    ) = NotificationPolicy.evaluate(input(message, blockedOrIgnored = blockedOrIgnored, selected = selected,
        permissions = permissions, roles = roles, dnd = dnd, settings = settings))

    private fun input(
        message: Message = message(),
        channel: Channel = channel(),
        parent: Channel? = null,
        blockedOrIgnored: Boolean = false,
        selected: Long? = null,
        permissions: Long = Permission.VIEW_CHANNEL,
        roles: Set<Long> = emptySet(),
        dnd: Boolean = false,
        settings: ModelNotificationSettings = setting(),
    ) = NotificationPolicy.Input(message, channel, parent, Guild(id = GUILD), ME, blockedOrIgnored,
        permissions, roles, settings, selected, dnd)

    private fun message(
        authorId: Long = 2,
        mentions: List<User>? = null,
        everyone: Boolean = false,
        roleIds: List<Long>? = null,
        timestamp: Long = 2_000_000L,
    ) = Message(1, CHANNEL, GUILD, User(authorId, "author"), timestamp = UtcDateTime(timestamp),
        mentionEveryone = everyone, mentions = mentions, mentionRoles = roleIds)

    private fun setting(
        muted: Boolean = false,
        mobilePush: Boolean = true,
        frequency: Int = ModelNotificationSettings.FREQUENCY_ALL,
        suppressEveryone: Boolean = false,
        suppressRoles: Boolean = false,
        overrides: List<ModelNotificationSettings.ChannelOverride> = emptyList(),
    ) = ModelNotificationSettings(suppressEveryone, suppressRoles, muted, derivedMute = muted,
        isMobilePush = mobilePush, messageNotifications = frequency, guildId = GUILD, channelOverrides = overrides)

    private fun channel(id: Long = CHANNEL, type: Int = Channel.GUILD_TEXT, parentId: Long = 0) = Channel(
        null, type, GUILD, "channel", 0, id, 0, null, null, 0, null, 0, 0, null, 0, 0,
        null, false, parentId, null, 0, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null,
    )

    companion object {
        private const val ME = 1L
        private const val GUILD = 10L
        private const val CHANNEL = 20L
        private const val PARENT = 30L
    }
}

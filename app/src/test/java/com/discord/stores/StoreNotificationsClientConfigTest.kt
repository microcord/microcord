package com.discord.stores

import com.discord.api.permission.Permission
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.fcm.NotificationClient
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.Clock
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.schedulers.TestScheduler
import rx.subjects.BehaviorSubject

/**
 * Covers the half of [StoreNotifications] that makes the store more than dead code: the fold that
 * completes the notification client's settings, and the backgrounded gate that decides whether the
 * client may reach the system tray at all.
 */
class StoreNotificationsClientConfigTest {
    private val directory = Files.createTempDirectory("notifications-client").toFile()
    private val scheduler = TestScheduler()
    private val dispatcher = Dispatcher(scheduler)
    private val deck = ObservationDeck()

    // `create<String?>(null)` would bind the inherited static `Observable.create(OnSubscribe)`
    // overload instead of `BehaviorSubject.create(T)`; casting the argument pins the right one.
    private val token = BehaviorSubject.create(null as String?)
    private val locale = BehaviorSubject.create("en-US")

    /**
     * Seeded rather than pushed later: the permissions branch is leading-edge throttled to one
     * emission per second, exactly as the reference throttles it, so a second emission inside the
     * window would be dropped.
     */
    private val permissions = BehaviorSubject.create(
        mapOf(
            READ_ONLY_CHANNEL to Permission.VIEW_CHANNEL,
            WRITABLE_CHANNEL to (Permission.VIEW_CHANNEL or Permission.SEND_MESSAGES),
        ),
    )
    private val backgrounded = BehaviorSubject.create(true)

    private val pushed = mutableListOf<Pair<NotificationClient.SettingsV2, Boolean>>()
    private var tokenSink: ((String?) -> Unit)? = null

    private lateinit var store: StoreNotifications

    @Before fun setUp() {
        Persister.init(directory)
        store = StoreNotifications(
            dispatcher = dispatcher,
            clock = Clock { 0 },
            observationDeck = deck,
            observeAuthedToken = { token },
            observeLocale = { locale },
            observeChannelPermissions = { permissions },
            observeBackgrounded = { backgrounded },
            setRegistrationIdReceived = { tokenSink = it },
            updateNotificationClient = { settings, isBackgrounded ->
                synchronized(pushed) { pushed += settings to isBackgrounded }
            },
        )
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        dispatcher.schedule { store.restore() }
        scheduler.triggerActions()
    }

    @After fun tearDown() { directory.deleteRecursively() }

    @Test fun completedSettingsCarryTokenLocaleAndSendBlockedChannels() {
        val completed = mutableListOf<NotificationClient.SettingsV2>()
        store.observeCompletedSettings().subscribe(completed::add)

        // No auth token yet: the client must refuse to display anything.
        assertEquals(1, completed.size)
        assertFalse(completed.last().isAuthed)
        assertNull(completed.last().token)
        assertEquals("en-US", completed.last().locale)

        // Only the channel without SEND_MESSAGES loses its direct-reply action.
        assertEquals(setOf(READ_ONLY_CHANNEL), completed.last().sendBlockedChannels)
        assertFalse(completed.last().canReplyTo(READ_ONLY_CHANNEL))
        assertTrue(completed.last().canReplyTo(WRITABLE_CHANNEL))

        token.onNext("secret")
        assertTrue(completed.last().isAuthed)

        locale.onNext("de")
        assertEquals("de", completed.last().locale)

        // User toggles reach the client through the same fold.
        store.setNotificationSoundDisabled(true)
        scheduler.triggerActions()
        assertTrue(completed.last().isDisableSound)
    }

    @Test fun tokenIsNeverPrintedByTheSettingsSnapshot() {
        val rendered = NotificationClient.SettingsV2(token = "super-secret-registration-id").toString()
        assertFalse(rendered.contains("super-secret-registration-id"))
        assertTrue(rendered.contains("<redacted>"))
    }

    @Test fun configureNotificationClientRegistersTheTokenSinkAndPushesTheBackgroundedState() {
        store.configureNotificationClient()
        assertNotNull(tokenSink)

        // The registration-id callback is the store's only route to the push token.
        tokenSink!!("push-token")
        assertEquals("push-token", store.getPushToken())

        token.onNext("secret")
        await("an authed snapshot while backgrounded") { it.first.isAuthed && it.second }

        backgrounded.onNext(false)
        await("a foregrounded snapshot") { !it.second }
    }

    /**
     * The client pipeline hops to the computation pool (`computationBuffered`), so assertions have
     * to wait for it rather than assume the emission already landed.
     */
    private fun await(what: String, condition: (Pair<NotificationClient.SettingsV2, Boolean>) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val last = synchronized(pushed) { pushed.lastOrNull() }
            if (last != null && condition(last)) return
            Thread.sleep(5)
        }
        throw AssertionError("Notification client never reached $what; pushed=${synchronized(pushed) { pushed.toList() }}")
    }

    private companion object {
        const val READ_ONLY_CHANNEL = 11L
        const val WRITABLE_CHANNEL = 22L
    }
}

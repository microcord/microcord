package com.discord.stores

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelPayload
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.Clock
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.schedulers.TestScheduler

class NoticeStoresTest {

    private val directory = Files.createTempDirectory("notice-stores-test").toFile()
    private val scheduler = TestScheduler()
    private val dispatcher = Dispatcher(scheduler)
    private val preferences = memoryPreferences()
    private var now = START_TIME_MS
    private val clock = Clock { now }

    @Before fun setUp() = Persister.init(directory)
    @After fun tearDown() { directory.deleteRecursively() }

    // region StoreNotices

    @Test fun theQueueShowsTheOldestNonPassiveNoticeFirst() {
        val notices = newNoticeStore()
        val published = mutableListOf<StoreNotices.Notice?>()
        notices.getNotices().subscribe { published += it }

        notices.requestToShow(passiveNotice("PASSIVE"))
        notices.requestToShow(notice("MODAL", requestedShowTimestamp = 2))
        notices.requestToShow(notice("OLDER_MODAL", requestedShowTimestamp = 1))
        // Nothing has ever been shown, so every delay window has already elapsed.
        now += DAY_MS
        notices.processNextNotice()

        assertEquals("OLDER_MODAL", published.last()?.name)
    }

    @Test fun requestingTheSameNoticeNameTwiceQueuesItOnce() {
        val notices = newNoticeStore()
        notices.requestToShow(notice("MODAL", requestedShowTimestamp = 1))
        notices.requestToShow(notice("MODAL", requestedShowTimestamp = 2))

        now += DAY_MS
        notices.processNextNotice()
        notices.markSeen("MODAL")

        // If it had been queued twice, the second copy would still be publishable.
        notices.processNextNotice()
        assertNull(latestNotice(notices))
    }

    @Test fun aPersistedNoticeIsNotRequeuedInsideItsCooldownButIsAfterwards() {
        val notices = newNoticeStore()
        notices.requestToShow(notice("MODAL", persist = true, sinceLastPeriodMs = WEEK_MS))
        notices.markSeen("MODAL")
        assertTrue(notices.hasSeen("MODAL"))
        assertTrue(notices.hasBeenSeen("MODAL"))

        notices.requestToShow(notice("MODAL", persist = true, sinceLastPeriodMs = WEEK_MS))
        now += DAY_MS
        notices.processNextNotice()
        assertNull(latestNotice(notices))

        now += WEEK_MS
        notices.requestToShow(notice("MODAL", persist = true, sinceLastPeriodMs = WEEK_MS))
        notices.processNextNotice()
        assertEquals("MODAL", latestNotice(notices)?.name)
    }

    @Test fun clearSeenReArmsAPersistedNotice() {
        val notices = newNoticeStore()
        notices.requestToShow(notice("MODAL", persist = true, sinceLastPeriodMs = WEEK_MS))
        notices.markSeen("MODAL")
        assertTrue(notices.hasSeen("MODAL"))

        notices.clearSeen("MODAL")

        assertFalse(notices.hasSeen("MODAL"))
        notices.requestToShow(notice("MODAL", persist = true, sinceLastPeriodMs = WEEK_MS))
        now += DAY_MS
        notices.processNextNotice()
        assertEquals("MODAL", latestNotice(notices)?.name)
    }

    @Test fun aPassiveNoticeWaitsOutAMoreUrgentNoticeShownRecently() {
        val notices = newNoticeStore()
        notices.requestToShow(notice("MODAL", priority = StoreNotices.PRIORITY_HIGH))
        now += DAY_MS
        // Showing the high-priority modal stamps priority 0's slot.
        notices.markSeen("MODAL")

        notices.requestToShow(passiveNotice("PASSIVE"))
        notices.processNextNotice()
        assertNull(latestNotice(notices))

        // A plain notice at the same priority only consults its own slot, so it is unaffected.
        notices.requestToShow(notice("OTHER_MODAL", priority = StoreNotices.PRIORITY_INAPP_NOTIFICATION))
        notices.processNextNotice()
        assertEquals("OTHER_MODAL", latestNotice(notices)?.name)
    }

    @Test fun markInAppSeenClearsEveryQueuedInAppNotification() {
        val notices = newNoticeStore()
        notices.requestToShow(notice("InAppNotif-1"))
        notices.requestToShow(notice("InAppNotif-2"))
        notices.requestToShow(notice("MODAL"))

        notices.markInAppSeen()
        now += DAY_MS
        notices.processNextNotice()

        assertEquals("MODAL", latestNotice(notices)?.name)
    }

    @Test fun noticeNameTagsClassifyInAppNotificationsAndPopups() {
        assertTrue(notice("SomethingInAppNotif").isInAppNotification())
        assertFalse(notice("SomethingInAppNotif").isPopup())
        assertTrue(notice("SomePopup").isPopup())
    }

    @Test fun firstUseTimestampIsMintedOnceAndPersisted() {
        val first = newNoticeStore().firstUseTimestamp
        assertEquals(START_TIME_MS, first)

        now += WEEK_MS
        // A second store instance — i.e. a later process — reads the stored value.
        assertEquals(first, newNoticeStore().firstUseTimestamp)
    }

    @Test fun markDialogSeenFallsBackToTreatingTheStringAsAPlainNoticeName() {
        val notices = newNoticeStore()
        notices.requestToShow(notice(StoreNotices.Dialog.Type.REQUEST_RATING_MODAL.name, persist = true))
        notices.requestToShow(notice("https://example.invalid/masked", persist = true))

        notices.markDialogSeen(StoreNotices.Dialog.Type.REQUEST_RATING_MODAL.name)
        notices.markDialogSeen("https://example.invalid/masked")

        assertTrue(notices.hasSeen(StoreNotices.Dialog.Type.REQUEST_RATING_MODAL.name))
        assertTrue(notices.hasSeen("https://example.invalid/masked"))
    }

    // endregion

    // region StoreNux

    @Test fun nuxFlagsPublishOnEveryChangeAndClearTogether() {
        val store = StoreNux(dispatcher)
        val states = mutableListOf<StoreNux.NuxState>()
        store.getNuxState().subscribe { states += it }

        store.setPostRegister(true)
        store.setFirstOpen(true)
        scheduler.triggerActions()

        assertTrue(states.last().postRegister)
        assertTrue(states.last().firstOpen)

        store.clearNux()
        scheduler.triggerActions()
        assertEquals(StoreNux.NuxState(), states.last())
    }

    @Test fun selectingADifferentGuildRetiresAPendingBoostHint() {
        val store = StoreNux(dispatcher)
        dispatcher.registerDispatchHandlers(DispatchHandler { })

        store.setPremiumGuildHintGuildId(GUILD_ID)
        scheduler.triggerActions()

        dispatcher.schedule { store.handleGuildSelected(GUILD_ID) }
        scheduler.triggerActions()
        assertEquals(GUILD_ID, currentNux(store).guildBoostHintGuildId)

        dispatcher.schedule { store.handleGuildSelected(OTHER_GUILD_ID) }
        scheduler.triggerActions()
        assertNull(currentNux(store).guildBoostHintGuildId)
    }

    // endregion

    // region StoreReviewRequest

    @Test fun theReviewPromptWaitsForAVerifiedAccountInARealGuildOnAnOldInstall() {
        val notices = newNoticeStore()
        val store = StoreReviewRequest(clock, notices, preferences)
        // Mint the install timestamp now, so "ten days old" is measured from here.
        assertEquals(START_TIME_MS, notices.firstUseTimestamp)

        store.handleConnectionOpen(readyPayload(verified = true, memberCount = 50))
        assertNull(queuedReviewNotice(notices))

        now += ELEVEN_DAYS_MS
        store.handleConnectionOpen(readyPayload(verified = false, memberCount = 50))
        assertNull(queuedReviewNotice(notices))

        store.handleConnectionOpen(readyPayload(verified = true, memberCount = 2))
        assertNull(queuedReviewNotice(notices))

        store.handleConnectionOpen(readyPayload(verified = true, memberCount = 50))
        assertNotNull(queuedReviewNotice(notices))
    }

    @Test fun acceptingTheReviewRequestSilencesItForever() {
        val notices = newNoticeStore()
        val accepted = mutableListOf<Unit>()
        val store = StoreReviewRequest(
            clock, notices, preferences,
            trackReviewRequestAccepted = { accepted += Unit },
        )
        assertEquals(START_TIME_MS, notices.firstUseTimestamp)
        now += ELEVEN_DAYS_MS

        store.onUserAcceptedRequest()
        store.handleConnectionOpen(readyPayload(verified = true, memberCount = 50))

        assertEquals(1, accepted.size)
        assertNull(queuedReviewNotice(notices))
        // A fresh process reads the same durable preference and stays silent.
        val reloaded = StoreReviewRequest(clock, notices, preferences)
        reloaded.init(STUB_CONTEXT)
        reloaded.handleConnectionOpen(readyPayload(verified = true, memberCount = 50))
        assertNull(queuedReviewNotice(notices))
    }

    // endregion

    // region fixtures

    private fun newNoticeStore() = StoreNotices(clock, preferences)

    private fun notice(
        name: String,
        priority: Int = StoreNotices.PRIORITY_PASSIVE_NOTICE,
        persist: Boolean = false,
        requestedShowTimestamp: Long = now,
        sinceLastPeriodMs: Long = WEEK_MS,
    ) = StoreNotices.Notice(
        name = name,
        clock = clock,
        requestedShowTimestamp = requestedShowTimestamp,
        priority = priority,
        persist = persist,
        delayPeriodMs = 0L,
        sinceLastPeriodMs = sinceLastPeriodMs,
        show = { true },
    )

    private fun passiveNotice(name: String) = StoreNotices.PassiveNotice(
        name = name,
        priority = StoreNotices.PRIORITY_PASSIVE_NOTICE,
        delayPeriodMs = 0L,
        clock = clock,
        show = { true },
    )

    private fun latestNotice(notices: StoreNotices): StoreNotices.Notice? {
        var latest: StoreNotices.Notice? = null
        notices.getNotices().take(1).subscribe { latest = it }
        return latest
    }

    private fun queuedReviewNotice(notices: StoreNotices): StoreNotices.Notice? {
        notices.processNextNotice()
        return latestNotice(notices)
            ?.takeIf { it.name == StoreNotices.Dialog.Type.REQUEST_RATING_MODAL.name }
    }

    private fun currentNux(store: StoreNux): StoreNux.NuxState {
        lateinit var state: StoreNux.NuxState
        store.getNuxState().take(1).subscribe { state = it }
        return state
    }

    private fun readyPayload(verified: Boolean, memberCount: Int): ModelPayload = gson.fromJson(
        """
        {
          "user": {"id": 7, "username": "me", "discriminator": "0000", "verified": $verified},
          "private_channels": [],
          "guilds": [{
            "id": $GUILD_ID, "name": "Guild", "owner_id": 9, "unavailable": false,
            "features": [], "member_count": $memberCount
          }]
        }
        """.trimIndent(),
        ModelPayload::class.java,
    )

    private fun memoryPreferences(): SharedPreferences {
        val values = linkedMapOf<String, Any?>()
        lateinit var editor: Any
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putBoolean", "putInt", "putLong", "putFloat", "putString", "putStringSet" ->
                    editor.also { values[args[0] as String] = args[1] }
                "remove" -> editor.also { values.remove(args[0] as String) }
                "clear" -> editor.also { values.clear() }
                "commit" -> true
                "apply" -> null
                else -> editor
            }
        }
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> values.toMap()
                "getBoolean", "getInt", "getLong", "getFloat", "getString", "getStringSet" ->
                    values[args[0]] ?: args[1]
                "contains" -> values.containsKey(args[0])
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences
    }

    // endregion

    companion object {
        private const val GUILD_ID = 10L
        private const val OTHER_GUILD_ID = 11L
        private const val START_TIME_MS = 1_700_000_000_000L
        private const val DAY_MS = 86_400_000L
        private const val WEEK_MS = 604_800_000L
        private const val ELEVEN_DAYS_MS = 11 * DAY_MS

        /**
         * `Store.init` ignores its context entirely and these stores read their preferences from
         * the constructor, so an unwrapped stub is enough to drive the reload path.
         */
        private val STUB_CONTEXT: Context = ContextWrapper(null)

        private val gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .create()
    }
}

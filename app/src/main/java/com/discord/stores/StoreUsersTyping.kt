package com.discord.stores

import com.discord.api.user.TypingUser
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.permissions.SlowModeType
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import rx.Observable

fun interface TypingCancellable { fun cancel() }

fun interface TypingScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): TypingCancellable
}

class StoreUsersTyping(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    private val currentUserId: () -> Long,
    private val scheduler: TypingScheduler = DEFAULT_SCHEDULER,
    private val standalone: Boolean = false,
    private val api: () -> RestAPIInterface? = { null },
    /**
     * `StoreSlowMode.onCooldown`. `POST /channels/{id}/typing` is the only endpoint that reports
     * the *remaining* slow-mode window, so the typing ping doubles as the cooldown probe.
     * `StoreSlowMode` is not reconstructed yet; until it is, the cooldown is discarded and the
     * chat input will not pre-emptively disable itself.
     */
    private val onCooldown: (channelId: Long, cooldownMs: Long, type: SlowModeType) -> Unit =
        { _, _, _ -> },
) : StoreV2() {
    private val typingUsers = HashMap<Long, MutableSet<Long>>()
    private val removalTasks = HashMap<Long, MutableMap<Long, TypingCancellable>>()
    private var typingUsersSnapshot: Map<Long, Set<Long>> = emptyMap()

    // Kept until StoreStream wiring can be changed in its own slice.
    constructor(@Suppress("UNUSED_PARAMETER") clock: Clock) : this(
        Dispatcher(StoreThreadScheduler.create()), ObservationDeckProvider.get(), { 0L }, standalone = true,
    )

    @StoreThread
    fun handleTypingStart(event: TypingUser) {
        StoreThreadScheduler.assertCurrentThread()
        if (event.userId == currentUserId()) return
        val channelTasks = removalTasks.getOrPut(event.channelId, ::HashMap)
        channelTasks.remove(event.userId)?.cancel()
        lateinit var task: TypingCancellable
        task = scheduler.schedule(TIMEOUT_MS) {
            dispatcher.schedule {
                if (removalTasks[event.channelId]?.get(event.userId) === task) {
                    removalTasks[event.channelId]?.remove(event.userId)
                    remove(event.channelId, event.userId)
                    publishStandalone()
                }
            }
        }
        channelTasks[event.userId] = task
        if (typingUsers.getOrPut(event.channelId, ::HashSet).add(event.userId)) markChanged()
        publishStandalone()
    }

    @StoreThread
    fun handleMessageCreate(channelId: Long, userId: Long?) {
        StoreThreadScheduler.assertCurrentThread()
        if (userId != null) {
            removalTasks[channelId]?.remove(userId)?.cancel()
            remove(channelId, userId)
            publishStandalone()
        }
    }

    /**
     * Tells the server this user is typing in [channelId].
     *
     * The caller is responsible for throttling: 126.21 pings once per keystroke burst, and the
     * server-side indicator lasts ~10s. A zero channel id is dropped rather than sent, because
     * "no channel selected" reaches here through the same input path.
     */
    fun setUserTyping(channelId: Long) {
        if (channelId == 0L) return
        val api = api() ?: return
        api.setUserTyping(channelId, RestAPIParams.EmptyBody())
            .restSubscribeOn()
            .appSubscribe(errorTag = TYPING_EVENT_TAG) { response ->
                // Despite the reference's local name ("...CooldownSecs") the value forwarded is the
                // raw millisecond field.
                response.messageSendCooldownMs?.takeIf { it > 0L }?.let { cooldownMs ->
                    dispatcher.schedule { onCooldown(channelId, cooldownMs, SlowModeType.MESSAGE_SEND) }
                }
                response.threadCreateCooldownMs?.takeIf { it > 0L }?.let { cooldownMs ->
                    dispatcher.schedule { onCooldown(channelId, cooldownMs, SlowModeType.THREAD_CREATE) }
                }
            }
    }

    fun observeTypingUsers(channelId: Long): Observable<Set<Long>> =
        observationDeck.connectRx(this) { typingUsersSnapshot[channelId].orEmpty() }.distinctUntilChanged()

    fun observeTypingUsersForChannels(channelIds: Set<Long>): Observable<Map<Long, Set<Long>>> =
        observationDeck.connectRx(this) { typingUsersSnapshot.filterKeys(channelIds::contains) }.distinctUntilChanged()

    @StoreThread
    fun clear() {
        StoreThreadScheduler.assertCurrentThread()
        removalTasks.values.forEach { it.values.forEach(TypingCancellable::cancel) }
        removalTasks.clear()
        if (typingUsers.isNotEmpty()) {
            typingUsers.clear()
            markChanged()
        }
        publishStandalone()
    }

    @StoreThread fun reset() = clear()

    override fun snapshotData() {
        typingUsersSnapshot = typingUsers.mapValues { (_, users) -> users.toSet() }
    }

    @StoreThread
    private fun remove(channelId: Long, userId: Long) {
        val users = typingUsers[channelId] ?: return
        if (!users.remove(userId)) return
        if (users.isEmpty()) typingUsers.remove(channelId)
        markChanged()
    }

    private fun publishStandalone() {
        if (!standalone || updateSources.isEmpty()) return
        snapshotData()
        onDispatchEnded()
        observationDeck.notify(setOf(this))
    }

    companion object {
        private const val TIMEOUT_MS = 10_000L
        private const val TYPING_EVENT_TAG = "typingEvent"
        private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "TypingExpiry").apply { isDaemon = true }
        }
        private val DEFAULT_SCHEDULER = TypingScheduler { delayMs, action ->
            val future = executor.schedule(action, delayMs, TimeUnit.MILLISECONDS)
            TypingCancellable { future.cancel(false) }
        }
    }
}

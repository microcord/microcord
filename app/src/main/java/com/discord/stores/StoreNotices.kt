package com.discord.stores

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.MainThread
import androidx.fragment.app.FragmentActivity
import com.discord.app.AppComponent
import com.discord.app.AppLog
import com.discord.utilities.persister.Persister
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import com.discord.widgets.home.WidgetHome
import java.util.PriorityQueue
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * The single queue for anything that interrupts the user: modals, popups, in-app notifications,
 * the masked-link confirmation, the review prompt, the changelog.
 *
 * Nothing shows itself. A caller [requestToShow]s a [Notice] and this store decides *when* — the
 * queue is polled, the highest-ranked notice that is currently allowed is published on
 * [getNotices], and the navigation layer is what actually calls [Notice.show] once the user is on
 * one of the notice's [Notice.validScreens]. That indirection is the whole point: it is why two
 * dialogs never stack, and why a notice requested while the app is on a settings screen waits
 * rather than being dropped.
 *
 * Two independent cooldowns apply:
 *  - [Notice.sinceLastPeriodMs] — how long before this *named* notice may be shown again, checked
 *    against the persisted seen-cache, so it survives reinstall-free process death.
 *  - [Notice.delayPeriodMs] — how long after *any* notice of the same priority before this one may
 *    appear, so a burst does not become a wall of dialogs.
 */
class StoreNotices(
    private val clock: Clock,
    private val sessionDurablePreferences: SharedPreferences,
    private val noticesSeenCache: Persister<HashMap<String, Long>> =
        Persister(NOTICES_SEEN_CACHE_KEY, HashMap()),
) : Store() {

    /**
     * Ordered: real notices before passive ones, then by descending [Notice.priority] *value*, then
     * oldest request first.
     *
     * The second key is `-priority`, which reads backwards — `PRIORITY_HIGH` is 0 and
     * `PRIORITY_PASSIVE_NOTICE` is 10, so a numerically higher (i.e. less urgent) priority sorts
     * first among plain notices. That is what the reference compiles to; verified from the three
     * synthesized `compareBy` selector classes. The `is PassiveNotice` primary key is what keeps it
     * from mattering in practice, since passive notices are the ones that use priority 10.
     */
    private val noticeQueue = PriorityQueue(
        INITIAL_QUEUE_CAPACITY,
        compareBy<Notice>({ it is PassiveNotice }, { -it.priority }, { it.requestedShowTimestamp }),
    )

    /** Last time a notice of each priority was shown, driving [Notice.shouldShow]. */
    private var lastShownTimes = HashMap<Int, Long>()

    private val processTrigger = BehaviorSubject.create<Unit>()
    private val noticePublisher = BehaviorSubject.create<Notice?>(null)

    /**
     * Poll interval, tightened to the shortest queued [Notice.delayPeriodMs] so a notice that only
     * has to wait a few seconds is not stuck behind the 30s default. Floored at 500 ms.
     */
    private var pollRateMs: Long = PROCESS_PERIOD_MS
        set(value) {
            field = maxOf(MIN_POLL_RATE_MS, value)
        }

    /**
     * When this install first ran, minted on first read. Notices that must not fire at an
     * inopportune moment — the review request, the changelog — gate on the account being at least
     * a few days old.
     */
    val firstUseTimestamp: Long by lazy {
        val cached = sessionDurablePreferences.getLong(CACHE_KEY_FIRST_USE, -1L)
        if (cached != -1L) return@lazy cached
        clock.currentTimeMillis().also {
            sessionDurablePreferences.edit().putLong(CACHE_KEY_FIRST_USE, it).apply()
        }
    }

    override fun init(context: Context) {
        super.init(context)
        AppLog.d("Notices init")
        AppLog.d("Notices prev seen: ${noticesSeenCache.get().entries}")
        // switchMap, so re-triggering restarts the interval at the *current* poll rate; the 50 ms
        // debounce collapses the burst of triggers a batch of requestToShow calls produces.
        processTrigger
            .switchMap { Observable.interval(0L, pollRateMs, TimeUnit.MILLISECONDS) }
            .debounce(PROCESS_THROTTLE_MS, TimeUnit.MILLISECONDS)
            .computationLatest()
            .appSubscribe(
                errorClass = StoreNotices::class.java,
                errorHandler = { error ->
                    AppLog.e("Could not process next notice: $noticeQueue", error.cause)
                },
            ) { processNextNotice() }
    }

    /**
     * The notice the UI should be showing, or `null` when nothing is due.
     *
     * `null` is meaningful and must not be filtered: it is how a dismissed notice tells the
     * navigation layer to stop trying to show it.
     */
    fun getNotices(): Observable<Notice?> = noticePublisher.distinctUntilChanged()

    fun observeNoticesSeen(): Observable<HashMap<String, Long>> = noticesSeenCache.getObservable()

    @Synchronized
    fun hasSeen(noticeName: String): Boolean = noticesSeenCache.get().containsKey(noticeName)

    @Synchronized
    fun hasBeenSeen(noticeName: String): Boolean = noticesSeenCache.get()[noticeName] != null

    @Synchronized
    fun requestToShow(notice: Notice) {
        if (!notice.canShow(noticesSeenCache.get()[notice.name])) return
        // Named identity, not object identity: re-requesting the same notice while it is queued
        // must not double it up.
        if (noticeQueue.any { it.name == notice.name }) return

        noticeQueue.add(notice)
        AppLog.i("Notice Request: ${notice.name}")
        AppLog.d("Notice queues: $noticeQueue")
        if (notice.delayPeriodMs < pollRateMs) pollRateMs = notice.delayPeriodMs
        processTrigger.onNext(Unit)
    }

    @JvmOverloads
    @Synchronized
    fun markSeen(noticeName: String, seenAtMs: Long = clock.currentTimeMillis()) {
        AppLog.i("Notice seen: $noticeName @ $seenAtMs")
        val notice = noticeQueue.firstOrNull { it.name == noticeName }
        if (notice != null) {
            noticeQueue.remove(notice)
            // This notice was the reason the poll rate was tightened; relax it to the shortest
            // delay still queued, or back to the default when nothing is left.
            if (notice.delayPeriodMs <= pollRateMs) {
                pollRateMs = noticeQueue.minByOrNull { it.delayPeriodMs }?.delayPeriodMs
                    ?: PROCESS_PERIOD_MS
            }
            lastShownTimes[notice.priority] = seenAtMs
            if (notice.persist) {
                val seen = noticesSeenCache.get()
                seen[notice.name] = seenAtMs
                noticesSeenCache.set(seen, persist = true)
                AppLog.v("Notice seen saved: $noticeName @ $seenAtMs")
            }
            noticePublisher.onNext(null)
        }
        processTrigger.onNext(Unit)
    }

    fun markSeen(type: Dialog.Type) = markSeen(type.name)

    /**
     * Marks a dialog seen by name. Falls back to treating [noticeDialogType] as a plain notice name
     * when it does not name a [Dialog.Type], which is how dialogs the store does not know about
     * (masked links, captcha) clear themselves.
     */
    fun markDialogSeen(noticeDialogType: String) {
        try {
            markSeen(Dialog.Type.valueOf(noticeDialogType))
        } catch (_: IllegalArgumentException) {
            markSeen(noticeDialogType)
        }
    }

    /** Dismisses every queued in-app notification at once, e.g. when the user navigates away. */
    @Synchronized
    fun markInAppSeen() {
        noticeQueue.filter { it.name.contains(IN_APP_NOTICE_TAG) }
            .forEach { markSeen(it.name) }
    }

    @Synchronized
    fun clearSeen(noticeName: String) {
        noticesSeenCache.getAndSet(persist = true) { cache -> cache.apply { remove(noticeName) } }
    }

    /**
     * Note the iteration: `PriorityQueue.iterator()` walks the heap array, not sorted order, so
     * only the head is guaranteed to be the highest-ranked notice. The reference scans it exactly
     * this way, and it is why the ordering above matters less than it looks.
     */
    @Synchronized
    internal fun processNextNotice() {
        noticePublisher.onNext(noticeQueue.firstOrNull { it.shouldShow(lastShownTimes) })
    }

    /**
     * One thing the user should be shown, and the rules for when.
     *
     * @param validScreens the screens this notice may appear over. `null` means "anywhere".
     * @param autoMarkSeen mark seen the moment it is shown rather than waiting for the UI to
     *   confirm — used by notices that cannot be dismissed in a way the store would hear about.
     * @param sinceLastPeriodMs minimum gap before this same notice may be shown again.
     * @param show performs the actual presentation and returns whether it happened.
     */
    open class Notice(
        val name: String,
        val clock: Clock = ClockFactory.get(),
        val requestedShowTimestamp: Long = clock.currentTimeMillis(),
        val priority: Int = PRIORITY_PASSIVE_NOTICE,
        val persist: Boolean = false,
        val validScreens: List<KClass<out AppComponent>>? = DEFAULT_VALID_SCREENS,
        val delayPeriodMs: Long = DEFAULT_DELAY_PERIOD_MS,
        val autoMarkSeen: Boolean = false,
        protected val sinceLastPeriodMs: Long = ONE_YEAR_MS,
        protected val show: (FragmentActivity) -> Boolean,
    ) {
        /** Guards against a notice being presented twice if the screen is recreated. */
        protected var hasShown: Boolean = false

        /** @param lastSeenMs when this notice was last shown, or `null` if never. */
        open fun canShow(lastSeenMs: Long?): Boolean =
            lastSeenMs == null || clock.currentTimeMillis() - lastSeenMs > sinceLastPeriodMs

        open fun shouldShow(lastShownTimes: Map<Int, Long>): Boolean =
            delayPeriodMs < clock.currentTimeMillis() - (lastShownTimes[priority] ?: 0L)

        fun isInAppNotification(): Boolean = name.contains(IN_APP_NOTICE_TAG, ignoreCase = true)

        fun isPopup(): Boolean = name.contains(NOTICE_POPUP_TAG, ignoreCase = true)

        @MainThread
        fun show(activity: FragmentActivity): Boolean {
            if (hasShown || activity.isFinishing) return false
            hasShown = show.invoke(activity)
            return hasShown
        }

        override fun toString(): String = "Notice<$name>(pri=$priority, ts=$requestedShowTimestamp)"
    }

    /**
     * A notice that yields to anything more urgent.
     *
     * Its [shouldShow] looks at every priority at least as urgent as its own rather than only its
     * own bucket, so a passive notice waits out a high-priority modal that was shown moments ago.
     */
    open class PassiveNotice(
        name: String,
        priority: Int,
        persist: Boolean = false,
        sinceLastPeriodMs: Long = ONE_YEAR_MS,
        delayPeriodMs: Long = PASSIVE_DELAY_PERIOD_MS,
        /**
         * Addition over the reference, which always takes the global clock here. Threaded through
         * so the cooldown arithmetic is testable without touching process-wide time.
         */
        clock: Clock = ClockFactory.get(),
        show: (FragmentActivity) -> Boolean,
    ) : Notice(
        name = name,
        clock = clock,
        priority = priority,
        persist = persist,
        delayPeriodMs = delayPeriodMs,
        sinceLastPeriodMs = sinceLastPeriodMs,
        show = show,
    ) {
        override fun shouldShow(lastShownTimes: Map<Int, Long>): Boolean {
            val lastComparableShow = lastShownTimes.filterKeys { it <= priority }
                .values.maxOrNull() ?: 0L
            return delayPeriodMs < clock.currentTimeMillis() - lastComparableShow
        }
    }

    /** A modal the app knows how to build from its [Type] alone. */
    data class Dialog(val type: Type, val metadata: Map<String, Any>? = null) {

        enum class Type {
            REQUEST_RATING_MODAL,
            DELETE_CONNECTION_MODAL,
            ;

            @JvmOverloads
            fun buildPassiveNotice(
                priority: Int = PRIORITY_PASSIVE_NOTICE,
                sinceLastPeriodMs: Long = ONE_YEAR_MS,
                delayPeriodMs: Long = PASSIVE_DELAY_PERIOD_MS,
                persist: Boolean = false,
            ): PassiveNotice = PassiveNotice(
                name = name,
                priority = priority,
                persist = persist,
                sinceLastPeriodMs = sinceLastPeriodMs,
                delayPeriodMs = delayPeriodMs,
                show = { activity ->
                    StoreNotices.dialogPresenter?.invoke(activity, Dialog(this)) ?: false
                },
            )
        }
    }

    /** The two text-input modals the app can raise, distinguished by IME behaviour. */
    data class InputDialog(val type: Type) {
        enum class Type { PASSWORD, TEXT_NO_SUGGESTIONS }
    }

    companion object {
        const val IN_APP_NOTICE_TAG = "InAppNotif"
        const val NOTICE_POPUP_TAG = "Popup"

        const val PRIORITY_HIGH = 0
        const val PRIORITY_INAPP_NOTIFICATION = 1
        const val PRIORITY_USER_SURVEY = 5
        const val PRIORITY_PASSIVE_NOTICE = 10

        const val PROCESS_PERIOD_MS = 30_000L
        const val PROCESS_THROTTLE_MS = 50L

        const val NOTICES_SEEN_CACHE_KEY = "NOTICES_SHOWN_V2"

        private const val CACHE_KEY_FIRST_USE = "CACHE_KEY_FIRST_USE"
        private const val INITIAL_QUEUE_CAPACITY = 11
        private const val MIN_POLL_RATE_MS = 500L
        private const val DEFAULT_DELAY_PERIOD_MS = 15_000L
        private const val PASSIVE_DELAY_PERIOD_MS = 21_600_000L
        private const val ONE_YEAR_MS = 31_536_000_000L

        /**
         * Where an un-scoped notice is allowed to appear.
         *
         * The reference also lists `WidgetCallFullscreen`, which this project has not
         * reconstructed; add it here when it lands or notices will be suppressed during a call
         * instead of shown over it.
         */
        private val DEFAULT_VALID_SCREENS: List<KClass<out AppComponent>> = listOf(WidgetHome::class)

        /**
         * Builds and shows a [Dialog]. Stands in for the reference's
         * `utilities/notices/NoticeBuilders`, which has not been reconstructed; until the UI layer
         * installs one, a `Dialog`-backed notice reports that it did not show and stays queued.
         */
        @JvmStatic
        var dialogPresenter: ((FragmentActivity, Dialog) -> Boolean)? = null
    }
}

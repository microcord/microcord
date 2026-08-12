package com.discord.stores

import com.discord.models.user.MeUser
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Scheduler
import rx.Subscription
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject
import rx.subjects.SerializedSubject

/**
 * The switch currently in flight, if any.
 *
 * A switch is only finished once the gateway hands back a READY for the account that was asked for,
 * so the store holds the request open across the whole teardown-and-reconnect and reports how it
 * ended. The UI blocks on [observeIsSwitching] and reacts to [observeResults].
 */
class StoreMultiAccountSwitch(
    private val dispatcher: Dispatcher,
    private val timeoutScheduler: Scheduler = Schedulers.computation(),
    private val timeoutMillis: Long = SWITCH_TIMEOUT_MS,
) : Store() {

    /** Where the switch was asked for, which the reference reports and the UI uses for routing. */
    enum class Location {
        ACCOUNT_ACTION_SHEET,
        CHOOSE_ACCOUNT,
        MANAGE_ACCOUNTS,
        PUSH_NOTIFICATION,
    }

    data class Pending(val targetUserId: Long, val previousUserId: Long?, val location: Location)

    sealed class Result {
        /**
         * @param username Carried rather than looked up: the result is emitted from inside the
         *   dispatch that applied READY, and the user store's snapshot only catches up at the end
         *   of it, so a subscriber reading it would still see the account being left.
         */
        data class Success(val userId: Long, val username: String, val location: Location) : Result()
        data class Failure(val targetUserId: Long, val location: Location) : Result()
    }

    private var pending: Pending? = null
    private var timeout: Subscription? = null
    private val switching = SerializedSubject<Boolean, Boolean>(BehaviorSubject.create(false))
    private val results = SerializedSubject<Result, Result>(PublishSubject.create())

    fun isSwitching(): Boolean = pending != null
    fun observeIsSwitching(): Observable<Boolean> = switching.distinctUntilChanged()
    fun observeResults(): Observable<Result> = results

    fun getPending(): Pending? = pending

    @StoreThread
    fun handleSwitchStart(targetUserId: Long, previousUserId: Long?, location: Location) {
        timeout?.unsubscribe()
        pending = Pending(targetUserId, previousUserId, location)
        switching.onNext(true)
        timeout = Observable.timer(timeoutMillis, TimeUnit.MILLISECONDS, timeoutScheduler)
            .subscribe({ dispatcher.schedule { handleSwitchFailedInternal() } }, {})
    }

    /**
     * A READY arriving for anyone other than the requested account means the switch did not take —
     * a stale session resumed, or the token belonged to someone else than the list claimed.
     */
    @StoreThread
    fun handleConnectionOpen(user: MeUser) {
        val request = pending ?: return
        if (request.targetUserId != user.id) return handleSwitchFailedInternal()
        finish(Result.Success(user.id, user.username, request.location))
    }

    fun handleSwitchFailed() = dispatcher.schedule { handleSwitchFailedInternal() }

    @StoreThread
    fun handleSwitchFailedInternal() {
        val request = pending ?: return
        finish(Result.Failure(request.targetUserId, request.location))
    }

    @StoreThread
    private fun finish(result: Result) {
        timeout?.unsubscribe()
        timeout = null
        pending = null
        switching.onNext(false)
        results.onNext(result)
    }

    companion object {
        const val SWITCH_TIMEOUT_MS = 15_000L
    }
}

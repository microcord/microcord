package com.discord.stores

import com.discord.app.AppLog
import rx.Scheduler
import rx.schedulers.Schedulers
import java.util.concurrent.Executors

object StoreThreadScheduler {
    const val STORE_THREAD_NAME = "Main-StoreThread"

    @Volatile
    var storeThreadId: Long = -1
        private set

    fun create(): Scheduler = Schedulers.from(
        Executors.newSingleThreadExecutor(::newThread),
    )

    internal fun newThread(runnable: Runnable) = Thread(runnable, STORE_THREAD_NAME).apply {
        priority = 8
        storeThreadId = id
    }

    internal fun bindCurrentThread() {
        storeThreadId = Thread.currentThread().id
    }

    /**
     * Reports, but does not throw, when a `@StoreThread` member runs off the store thread.
     *
     * The reference only logs here — `Logger.e(AppLog, …)` in `StoreV2.assertStoreThread`, verified
     * against the DEX — and [StoreV2.assertStoreThread] already matches that. This used to `check`,
     * which turned any wiring mistake into a fatal crash at startup rather than a reported one.
     */
    internal fun assertCurrentThread() {
        if (Thread.currentThread().id != storeThreadId) {
            AppLog.e(
                "Store mutation should be run from the Store Dispatcher",
                IllegalStateException("Dispatch not run on store thread"),
            )
        }
    }
}

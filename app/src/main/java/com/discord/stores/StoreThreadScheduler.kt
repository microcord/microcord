package com.discord.stores

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

    internal fun assertCurrentThread() {
        check(Thread.currentThread().id == storeThreadId) { "Dispatch not run on store thread" }
    }
}

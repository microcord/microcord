package com.discord.stores

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import rx.Scheduler
import rx.schedulers.Schedulers

/**
 * The dispatcher scheduler every [StoreStream]-backed test shares.
 *
 * It has to satisfy three constraints that no stock scheduler does at once:
 *
 * 1. **One thread, reused.** [Dispatcher.schedule] rebinds `StoreThreadScheduler.storeThreadId` on
 *    entry, so a thread-per-action executor lets a dispatch scheduled from elsewhere (the throttled
 *    `StoreGuildsSorted` rebuild, the `computationLatest` channel-selection resolution) steal the
 *    binding from the dispatch already in flight, which then dies on its next `assertCurrentThread`
 *    — silently, because rx routes that to the uncaught-exception handler.
 * 2. **Run to completion before returning**, so assertions can be written straight after the call
 *    that schedules the work.
 * 3. **Re-entrant schedules queue rather than nest.** The real dispatcher is asynchronous: a
 *    `dispatcher.schedule` issued *from inside* a dispatch is appended and runs after the current
 *    one finishes. Running it inline instead would end a dispatch in the middle of the outer one —
 *    `snapshotData()` publishing a half-applied state and `ObservationDeck.notify` broadcasting it.
 *    `StoreStream.resetForLogout` is exactly that case: it is one dispatch, and observers must see
 *    one transition, not a torn intermediate. Queueing also removes the deadlock that inline
 *    nesting creates when the nested action's `onDispatchEnded` needs the deck monitor its caller
 *    is still holding.
 *
 * The queue is only ever touched from the store thread, so it needs no synchronisation.
 */
internal fun storeTestScheduler(threadName: String): Scheduler {
    val storeThread = AtomicReference<Thread>()
    val queue = ArrayDeque<Runnable>()
    val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }.also(storeThread::set)
    }
    return Schedulers.from(
        Executor { command ->
            if (Thread.currentThread() === storeThread.get()) {
                queue.addLast(command)
            } else {
                try {
                    executor.submit {
                        queue.addLast(command)
                        while (queue.isNotEmpty()) queue.removeFirst().run()
                    }.get()
                } catch (failure: ExecutionException) {
                    // Surface what the dispatch actually threw; an ExecutionException wrapper turns
                    // every assertion failure inside a store into an unreadable one.
                    throw failure.cause ?: failure
                }
            }
        },
    )
}

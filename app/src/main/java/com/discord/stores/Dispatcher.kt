package com.discord.stores

import android.util.Log
import rx.Scheduler

class Dispatcher(
    val scheduler: Scheduler,
    private val assertCleanDispatches: Boolean = false,
) {
    private val dispatchHandlers = ArrayList<DispatchHandler>()
    private var dispatcherThread: Thread? = null

    fun schedule(action: () -> Unit) {
        if (dispatchHandlers.isEmpty()) {
            Log.e(javaClass.name, "scheduled an action without registering DispatchHandlers")
        }
        if (assertCleanDispatches && Thread.currentThread() === dispatcherThread) {
            Log.w(javaClass.name, "dirty dispatch", RuntimeException())
        }
        scheduler.createWorker().schedule {
            if (dispatcherThread == null) dispatcherThread = Thread.currentThread()
            StoreThreadScheduler.bindCurrentThread()
            action()
            onDispatchEnded()
        }
    }

    @StoreThread
    fun registerDispatchHandlers(vararg dispatchHandlerArgs: DispatchHandler) {
        dispatchHandlers.addAll(dispatchHandlerArgs)
    }

    @StoreThread
    fun unregisterDispatchHandlers(vararg dispatchHandlerArgs: DispatchHandler) {
        dispatchHandlerArgs.forEach(dispatchHandlers::remove)
    }

    @StoreThread
    fun onDispatchEnded() {
        dispatchHandlers.forEach(DispatchHandler::onDispatchEnded)
    }
}

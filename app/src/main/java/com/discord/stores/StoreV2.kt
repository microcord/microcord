package com.discord.stores

import com.discord.app.AppLog
import com.discord.stores.updates.ObservationDeck
import kotlin.reflect.KProperty

abstract class StoreV2 : Store(), DispatchHandler, ObservationDeck.UpdateSource {
    private val mutableUpdateSources = HashSet<ObservationDeck.UpdateSource>()
    val updateSources: Set<ObservationDeck.UpdateSource>
        get() = mutableUpdateSources

    /**
     * Reports, but does not enforce, that a mutation ran on the store thread.
     *
     * Marking state changed off the dispatcher is a bug, yet failing the process for it is
     * worse than the inconsistent snapshot it produces, so this records the offending stack
     * instead of throwing. The reference does the same:
     * `Logger.e(AppLog, "markChanged() should be run from the Store Dispatcher", IllegalStateException(...))`.
     */
    private fun assertStoreThread() {
        if (Thread.currentThread().id != StoreThreadScheduler.storeThreadId) {
            AppLog.e(
                "markChanged() should be run from the Store Dispatcher",
                IllegalStateException("Dispatch not run on store thread"),
            )
        }
    }

    protected fun markChanged() {
        assertStoreThread()
        mutableUpdateSources.add(this)
    }

    protected fun markChanged(vararg updates: ObservationDeck.UpdateSource) {
        assertStoreThread()
        markChanged()
        mutableUpdateSources.addAll(updates)
    }

    protected fun markUnchanged(updateSource: ObservationDeck.UpdateSource) {
        assertStoreThread()
        mutableUpdateSources.remove(updateSource)
    }

    open fun snapshotData() = Unit

    override fun onDispatchEnded() {
        mutableUpdateSources.clear()
    }

    class MarkChangedDelegate<T>(
        initialValue: T,
        private val overrideUpdateSource: ObservationDeck.UpdateSource? = null,
    ) {
        private var value = initialValue

        operator fun getValue(thisRef: StoreV2?, property: KProperty<*>): T = value

        operator fun setValue(thisRef: StoreV2?, property: KProperty<*>, value: T) {
            if (this.value == value) return
            this.value = value
            if (thisRef != null) {
                overrideUpdateSource?.let { thisRef.markChanged(it) } ?: thisRef.markChanged()
            }
        }
    }
}

package com.discord.stores

import com.discord.stores.updates.ObservationDeck
import kotlin.reflect.KProperty

abstract class StoreV2 : Store(), DispatchHandler, ObservationDeck.UpdateSource {
    private val mutableUpdateSources = HashSet<ObservationDeck.UpdateSource>()
    val updateSources: Set<ObservationDeck.UpdateSource>
        get() = mutableUpdateSources

    protected fun markChanged() {
        StoreThreadScheduler.assertCurrentThread()
        mutableUpdateSources.add(this)
    }

    protected fun markChanged(vararg updates: ObservationDeck.UpdateSource) {
        markChanged()
        mutableUpdateSources.addAll(updates)
    }

    protected fun markUnchanged(updateSource: ObservationDeck.UpdateSource) {
        StoreThreadScheduler.assertCurrentThread()
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
            if (thisRef != null) StoreThreadScheduler.assertCurrentThread()
            this.value = value
            if (thisRef != null) {
                overrideUpdateSource?.let { thisRef.markChanged(it) } ?: thisRef.markChanged()
            }
        }
    }
}

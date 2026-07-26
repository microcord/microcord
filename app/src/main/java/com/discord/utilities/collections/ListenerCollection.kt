package com.discord.utilities.collections

import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet

/** Write-only view over a listener registry. */
interface ListenerCollection<T> {
    fun add(listener: T)
    fun remove(listener: T): Boolean
}

/**
 * Copy-on-write listener registry that isolates each listener from failures in its peers: a
 * throwing listener is logged and the remaining listeners still receive the notification.
 */
class ListenerCollectionSubject<T> : ListenerCollection<T> {

    private val listeners = CopyOnWriteArraySet<T>()

    override fun add(listener: T) {
        listeners.add(listener)
    }

    override fun remove(listener: T): Boolean = listeners.remove(listener)

    fun clear() {
        listeners.clear()
    }

    fun notify(action: (T) -> Unit) {
        for (listener in listeners) {
            try {
                action(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Exception calling handler: $e")
            }
        }
    }

    private companion object {
        private const val TAG = "ListenerCollection"
    }
}

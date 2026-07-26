package com.discord.app

import java.util.TreeMap

/**
 * Priority ordered back-press dispatch used by [AppActivity].
 *
 * Screens register a handler with a priority; lower numbers run first. Within one priority the
 * handlers run in registration order. Dispatch stops at the first handler that returns `true`,
 * which means "I consumed this back press". When nothing consumes it the activity falls back to
 * the platform behaviour.
 *
 * Handlers are keyed so a screen can replace its own handler on every rebind and remove it when
 * its view is destroyed, without leaking into the next screen.
 */
class BackPressHandlerRegistry {

    private val handlersByPriority = TreeMap<Int, LinkedHashMap<String, () -> Boolean>>()

    /** Registers (or replaces) the handler stored under [key] at [priority]. */
    fun set(key: String, priority: Int = DEFAULT_PRIORITY, handler: () -> Boolean) {
        handlersByPriority.getOrPut(priority) { LinkedHashMap() }[key] = handler
    }

    /** Removes the handler stored under [key] from every priority bucket. */
    fun remove(key: String) {
        handlersByPriority.values.forEach { it.remove(key) }
    }

    fun clear() {
        handlersByPriority.clear()
    }

    val isEmpty: Boolean
        get() = handlersByPriority.values.all { it.isEmpty() }

    /** Returns `true` when one of the registered handlers consumed the back press. */
    fun dispatchBackPressed(): Boolean =
        handlersByPriority.values.any { handlers -> handlers.values.toList().any { it() } }

    companion object {
        /** Handlers registered without an explicit priority run last. */
        const val DEFAULT_PRIORITY: Int = Int.MAX_VALUE
    }
}

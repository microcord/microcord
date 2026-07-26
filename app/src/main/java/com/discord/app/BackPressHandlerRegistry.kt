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
 *
 * Reference note: every reference screen registers at [DEFAULT_PRIORITY], so the reference's whole
 * ordering falls out of `HashMap` bucket order within that single bucket. For the two screens the
 * order actually matters for — `WidgetTabsHost` and `WidgetHome` — that order is deterministically
 * tabs-host-first, which is also what registration order produces here because a parent fragment's
 * view is always bound before its child's. `WidgetHome` consumes every back press it sees, so the
 * tabs host has to get the first look or a back press on a non-home tab would drive the (hidden)
 * home panels instead of returning to the home tab.
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
    fun dispatchBackPressed(): Boolean {
        // Snapshot both levels: a handler is allowed to register or drop handlers while it runs,
        // which a screen that swaps fragments on back does.
        val buckets = handlersByPriority.values.map { it.values.toList() }
        return buckets.any { handlers -> handlers.any { it() } }
    }

    companion object {
        /** Handlers registered without an explicit priority run last. */
        const val DEFAULT_PRIORITY: Int = Int.MAX_VALUE
    }
}

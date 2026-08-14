package com.discord.api.application

/** Bits on [Application.flags]. Numbered as the reference numbers them, so bit n is `1 shl n`. */
object ApplicationFlags {
    const val CONTEXTLESS_ACTIVITY = 1L shl 9
    const val EMBEDDED = 1L shl 17
}

/**
 * Whether an activity this application publishes stands on its own.
 *
 * The reference calls this `canLaunchFrame`: an application whose activity can be launched without
 * the context that started it has to say so with both bits, and only then is its activity marked
 * `CONTEXTLESS`.
 */
fun Application?.canLaunchFrame(): Boolean {
    val flags = this?.flags ?: return false
    return flags and ApplicationFlags.EMBEDDED != 0L &&
        flags and ApplicationFlags.CONTEXTLESS_ACTIVITY != 0L
}

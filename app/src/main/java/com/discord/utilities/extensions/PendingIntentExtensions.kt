@file:JvmName("PendingIntentExtensionsKt")

package com.discord.utilities.extensions

import android.app.PendingIntent
import android.os.Build

/**
 * `PendingIntent` flag helpers.
 *
 * From API 31 a `PendingIntent` must declare mutability explicitly or its creation throws, so every
 * construction site has to make a choice. These two make that choice legible at the call site and
 * keep the version gate in one place.
 */

/**
 * Adds `FLAG_IMMUTABLE` where the platform supports it.
 *
 * Immutable is the correct default: a third party that receives the intent cannot rewrite its
 * extras or its component, so it cannot be redirected at the app's own private components.
 */
fun immutablePendingIntentFlag(flags: Int = 0): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        flags or PendingIntent.FLAG_IMMUTABLE
    } else {
        flags
    }

/**
 * Leaves the intent mutable.
 *
 * Deliberately does *not* set `FLAG_MUTABLE`, matching the reference: only a receiver that fills
 * in the intent — a notification `RemoteInput` reply — needs mutability, and on those platforms the
 * absence of `FLAG_IMMUTABLE` already means mutable. Kept as a named function so a mutable
 * `PendingIntent` is a visible, greppable decision rather than an omission.
 */
fun mutablePendingIntentFlag(flags: Int = 0): Int = flags

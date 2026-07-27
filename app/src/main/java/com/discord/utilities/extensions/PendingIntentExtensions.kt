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

/** Adds `FLAG_MUTABLE` where the platform requires an explicit mutability choice. */
fun mutablePendingIntentFlag(flags: Int = 0): Int =
    mutablePendingIntentFlag(flags, Build.VERSION.SDK_INT)

internal fun mutablePendingIntentFlag(flags: Int, sdkInt: Int): Int =
    if (sdkInt >= Build.VERSION_CODES.S) flags or PendingIntent.FLAG_MUTABLE else flags

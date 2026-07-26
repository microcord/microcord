@file:JvmName("ScrollViewExtensions")

package com.discord.utilities.view.extensions

import android.widget.ScrollView

/**
 * Briefly shows the scrollbars so the user can tell the content is scrollable.
 *
 * A one pixel scroll and back is the only reliable way to trigger the platform's fade-in without
 * moving the visible content.
 */
fun ScrollView.flashScrollBars() {
    scrollBy(0, 1)
    scrollBy(0, -1)
}

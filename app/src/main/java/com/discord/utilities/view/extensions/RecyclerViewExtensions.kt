@file:JvmName("RecyclerViewExtensions")

package com.discord.utilities.view.extensions

import androidx.recyclerview.widget.RecyclerView

/**
 * Cancels the touch gesture the recycler is currently tracking.
 *
 * Toggling layout suppression makes RecyclerView drop its in-flight touch, which is how a row that
 * handled a long press stops the same gesture from also scrolling or clicking.
 */
fun RecyclerView.ignoreCurrentTouch() {
    suppressLayout(true)
    suppressLayout(false)
}

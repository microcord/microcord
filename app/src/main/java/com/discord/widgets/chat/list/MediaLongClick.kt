package com.discord.widgets.chat.list

import android.view.View
import com.discord.R

/**
 * Wires [onLongClick] as this media view's long-press and marks it so a message row that hosts the
 * view does not replace it with its own forwarder (see `adoptAccessoryTouches`).
 */
internal fun View.setMediaLongClick(onLongClick: () -> Unit) {
    setTag(R.id.owns_long_click, true)
    setOnLongClickListener { onLongClick(); true }
}

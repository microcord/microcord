package com.discord.widgets.chat.list

/**
 * Implemented by chat rows that own something which must stop while the host screen is away —
 * an inline video player, an animated sticker, a countdown.
 *
 * `WidgetChatListAdapter.onPause`/`onResume` walk the currently attached rows and forward to every
 * holder implementing this. Rows that are recycled while paused simply never receive the callback,
 * which is correct: they are torn down anyway.
 */
interface FragmentLifecycleListener {
    fun onPause() = Unit
    fun onResume() = Unit
}

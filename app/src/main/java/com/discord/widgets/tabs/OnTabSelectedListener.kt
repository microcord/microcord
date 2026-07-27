package com.discord.widgets.tabs

/**
 * Implemented by a tab's root fragment so it is told when its tab becomes the visible one.
 *
 * The tab fragments are never destroyed — [WidgetTabsHost] only toggles visibility — so there is no
 * lifecycle callback that fires on a tab switch. Screens that must re-apply something on becoming
 * visible (the home surface re-binds the action bar, which is shared with whatever tab was showing
 * before) register through [WidgetTabsHost.registerTabSelectionListener].
 */
fun interface OnTabSelectedListener {
    fun onTabSelected()
}

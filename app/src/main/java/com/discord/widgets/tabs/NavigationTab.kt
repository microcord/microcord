package com.discord.widgets.tabs

/**
 * The destinations the bottom navigation bar can select.
 *
 * Declaration order is load bearing: [TabsHostViewModel.selectTab] branches on the ordinal
 * (`0` → home, `1` → friends) and [TabsHostViewModel.TAB_DESTINATIONS] is `values().toList()`.
 * Verified against the reference enum's `$VALUES` array — `HOME, FRIENDS, SEARCH, MENTIONS,
 * SETTINGS, DISCOVERY`.
 *
 * [SEARCH] and [DISCOVERY] are members of the enum but never fragment destinations: search opens a
 * dialog over whatever tab is showing, and discovery is reached through the guild list.
 */
enum class NavigationTab { HOME, FRIENDS, SEARCH, MENTIONS, SETTINGS, DISCOVERY }

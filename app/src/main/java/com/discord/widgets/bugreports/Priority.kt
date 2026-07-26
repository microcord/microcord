package com.discord.widgets.bugreports

import androidx.annotation.StringRes

/**
 * One severity level offered by the in-app bug reporter.
 *
 * [emojiId] is a custom emoji from Discord's own internal server; it is rendered through
 * `IconUtils.getForEmoji` in the picker. The list itself (P0…P3, in that order) lives in
 * [WidgetBugReport.priorityLevels] — the index into that list *is* the `priority` field sent to the
 * API, so the order is part of the wire contract.
 */
data class Priority(
    @StringRes val title: Int,
    @StringRes val description: Int,
    val emojiId: Long,
)

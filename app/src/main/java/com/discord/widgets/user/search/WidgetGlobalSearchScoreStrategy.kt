package com.discord.widgets.user.search

import com.discord.api.presence.ClientStatus
import com.discord.widgets.user.search.WidgetGlobalSearchModel.ItemChannel
import com.discord.widgets.user.search.WidgetGlobalSearchModel.ItemDataPayload
import com.discord.widgets.user.search.WidgetGlobalSearchModel.ItemUser
import com.discord.widgets.user.search.WidgetGlobalSearchModel.MatchedResult

/**
 * Ranks quick-switcher results.
 *
 * The total is four independent contributions added together: how recently the channel was used,
 * a per-kind adjustment (online friends up, huge servers down), unread mentions, and how early and
 * cleanly the typed text matched. Nothing here is normalised — the magnitudes *are* the priority
 * order, which is why an unread mention (>100) outranks anything frecency can produce (max 60), and
 * why a result that only fuzzy-matched (about -100) sinks below everything that matched literally.
 *
 * Every constant below is transcribed from the reference; see the KDoc on each function.
 */
object WidgetGlobalSearchScoreStrategy {

    /** How deep into the frecency list still earns a meaningful score. */
    const val FRECENCY_PRI_LIMIT = 50

    private const val FUZZY_MATCH_SCORE_BASE = -100
    private const val FUZZY_MATCH_SCORE_MAX_DEDUCTION = 20
    private const val MENTIONS_MATCH_SCORE = 100

    /**
     * How much a friendship and an existing DM are worth, which depends on whether the user
     * explicitly asked for people (`@` prefix) or is searching everything.
     */
    private enum class SearchType(val friendWeight: Int, val dmChannelWeight: Int) {
        USER(friendWeight = 10, dmChannelWeight = 5),
        NONE(friendWeight = 1, dmChannelWeight = 1),
    }

    fun ItemDataPayload.score(searchType: Int, frecencyChannels: Collection<Long>): Int {
        val channelId = channel?.id ?: 0L
        var score = frecencyChannels.scoreFrecency(channelId)
        score += when (this) {
            is ItemUser -> scoreUser(searchType)
            is ItemChannel -> scoreChannel()
            else -> 0
        }
        // An unread mention dominates every other signal: the count is added on top of a flat 100,
        // which is above the whole frecency range.
        if (mentions > 0) score += mentions + MENTIONS_MATCH_SCORE
        return score + matchedResult.scoreMatchedResult()
    }

    /**
     * Frecency position, front-loaded.
     *
     * The top five channels get 60 down to 56; positions 5..49 continue from 50 downwards, so
     * position 5 drops sharply from position 4. Everything past the limit gets a flat 2 — still
     * above an untracked channel's 0, but not enough to compete.
     */
    fun Collection<Long>.scoreFrecency(channelId: Long): Int =
        when (val index = indexOf(channelId)) {
            -1 -> 0
            in 0 until 5 -> 60 - index
            in 5 until FRECENCY_PRI_LIMIT -> 55 - index
            else -> 2
        }

    /** Large servers are penalised, on the assumption their channels are noisier and less wanted. */
    fun ItemChannel.scoreChannel(): Int = if ((guild?.memberCount ?: 0) > 200) -5 else 0

    /**
     * Online first, then friends, then people you already have a DM with, then an exact username
     * match.
     *
     * Presence is scored by raw status: online 4, idle 3, do-not-disturb 2, invisible/offline/absent
     * 0. The friend and DM weights come from [SearchType], so an `@` search values both far more
     * heavily than a general one.
     */
    fun ItemUser.scoreUser(searchType: Int): Int {
        val type = if (searchType == WidgetGlobalSearchModel.SEARCH_TYPE_USER) {
            SearchType.USER
        } else {
            SearchType.NONE
        }
        val presenceScore = when (presence?.status) {
            ClientStatus.ONLINE -> 4
            ClientStatus.IDLE -> 3
            ClientStatus.DND -> 2
            else -> 0
        }
        val friendScore = if (isFriend) type.friendWeight else 0
        val dmChannelScore = if (channel != null) type.dmChannelWeight else 0
        // Matching the username itself beats matching a nickname or an alias.
        val exactNameScore = if (matchedResult.value == user.username) 2 else 0
        return presenceScore + friendScore + dmChannelScore + exactNameScore
    }

    /**
     * Where and how the typed text landed.
     *
     * A match at the start is worth 15, decaying to 5 at index 10 and nothing beyond; a match that
     * only survived fuzzy matching (index -1) is pushed to about -100, further by the length of the
     * name, so long incidental matches sink furthest. On top of that, starting the name is worth 2
     * and starting a word within it is worth 1.
     */
    fun MatchedResult.scoreMatchedResult(): Int {
        val positionScore = when (val index = firstMatchIndex) {
            -1 -> FUZZY_MATCH_SCORE_BASE - minOf(value.length, FUZZY_MATCH_SCORE_MAX_DEDUCTION)
            in 0..10 -> 15 - index
            else -> 0
        }
        val boundaryScore = when {
            firstMatchIndex == 0 -> 2
            firstMatchIndex > 0 && !Character.isLetterOrDigit(value[firstMatchIndex - 1]) -> 1
            else -> 0
        }
        return positionScore + boundaryScore
    }
}

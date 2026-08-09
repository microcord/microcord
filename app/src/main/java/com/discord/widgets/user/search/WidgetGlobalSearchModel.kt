package com.discord.widgets.user.search

import androidx.annotation.StringRes
import com.discord.api.channel.Channel
import com.discord.models.guild.Guild
import com.discord.models.presence.Presence
import com.discord.models.user.User
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.search.SearchUtils
import com.discord.utilities.string.stripAccents
import java.util.Locale

/**
 * The quick switcher's results, and the row types they render as.
 *
 * [searchType] records which prefix the user typed (`@`, `#`, `!`, `*`) and is carried on the model
 * because it changes how results are *scored*, not only which ones survive — see
 * [WidgetGlobalSearchScoreStrategy].
 */
data class WidgetGlobalSearchModel(
    val filter: String,
    val searchType: Int,
    val data: List<ItemDataPayload>,
    val guildsList: List<WidgetGlobalSearchGuildsModel.Item>? = null,
) {

    /**
     * Where the filter text landed inside a candidate's name.
     *
     * [firstMatchIndex] is -1 for a fuzzy match — the characters are all present but not
     * contiguous — which the scorer treats as a heavy penalty rather than as "no match".
     */
    data class MatchedResult(
        val value: CharSequence,
        val firstMatchIndex: Int,
        val filterLength: Int,
    )

    interface ItemDataPayload : MGRecyclerDataPayload {
        val channel: Channel?
        val matchedResult: MatchedResult
        val mentions: Int
        val unread: Boolean
    }

    /** A section label. Carries no channel, so it never scores and is inserted after ranking. */
    data class ItemHeader(@StringRes val name: Int) : ItemDataPayload {
        override val type = TYPE_HEADER
        override val key = "header-$name"
        override val channel: Channel? = null
        override val matchedResult: MatchedResult = EMPTY_MATCH_RESULT
        override val mentions: Int = 0
        override val unread: Boolean = false
    }

    /**
     * "Search messages for …" — the switcher's hand-off to full message search across every DM.
     *
     * Carries no channel and never scores; it is prepended after ranking, like [ItemHeader].
     */
    data class ItemSearchMessages(val query: String) : ItemDataPayload {
        override val type = TYPE_SEARCH_MESSAGES
        override val key = "search-messages"
        override val channel: Channel? = null
        override val matchedResult: MatchedResult = EMPTY_MATCH_RESULT
        override val mentions: Int = 0
        override val unread: Boolean = false
    }

    data class ItemChannel(
        override val matchedResult: MatchedResult,
        override val channel: Channel,
        val parentChannel: Channel? = null,
        val guild: Guild? = null,
        override val mentions: Int = 0,
        override val unread: Boolean = false,
        val presence: Presence? = null,
    ) : ItemDataPayload {
        override val type = TYPE_CHANNEL
        override val key = channel.id.toString()
    }

    data class ItemGuild(
        override val matchedResult: MatchedResult,
        val guild: Guild,
        override val channel: Channel? = null,
        override val mentions: Int = 0,
        override val unread: Boolean = false,
    ) : ItemDataPayload {
        override val type = TYPE_GUILD
        override val key = guild.id.toString()
    }

    data class ItemUser(
        override val matchedResult: MatchedResult,
        val user: User,
        val aliases: List<CharSequence>,
        val isFriend: Boolean,
        val presence: Presence? = null,
        override val channel: Channel? = null,
        override val mentions: Int = 0,
        override val unread: Boolean = false,
    ) : ItemDataPayload {
        override val type = TYPE_USER
        override val key = user.id.toString()
    }

    companion object {
        const val TYPE_HEADER = -1
        const val TYPE_CHANNEL = 0
        const val TYPE_USER = 1
        const val TYPE_GUILD = 2
        const val TYPE_SEARCH_MESSAGES = 3

        /** No prefix: everything is searched. */
        const val SEARCH_TYPE_GENERAL = 0

        /** `@` — people only. */
        const val SEARCH_TYPE_USER = 1

        /** `#` — text channels only. */
        const val SEARCH_TYPE_TEXT_CHANNEL = 2

        /** `*` — servers only. */
        const val SEARCH_TYPE_GUILD = 3

        /** `!` — voice channels only. */
        const val SEARCH_TYPE_VOICE_CHANNEL = 4

        /** The most results the list will ever show, however many matched. */
        const val MAX_RESULTS = 50

        val EMPTY_MATCH_RESULT = MatchedResult("", -1, 0)

        /**
         * Which channels may appear at all.
         *
         * Navigation results must be reachable, so this is the same visibility test the channel list
         * uses — including the extra `CONNECT` requirement on voice channels.
         */
        val DEFAULT_PERMISSIONS_PREDICATE: (Channel, Map<Long, Long>) -> Boolean =
            { channel, permissions -> PermissionUtils.hasAccess(channel, permissions[channel.id]) }

        /** Literal, case-insensitive substring match. Null when the text does not occur at all. */
        fun String.toMatchedResult(filter: String): MatchedResult? {
            val index = indexOf(filter, ignoreCase = true)
            return if (index != -1) MatchedResult(this, index, filter.length) else null
        }

        /**
         * Fallback for names the filter does not literally occur in: accepts them when the typed
         * characters appear in order, and marks the result with `firstMatchIndex = -1` so the scorer
         * can push it below every literal match.
         */
        fun String.toFuzzyMatchedResult(filter: String): MatchedResult? {
            val matched = SearchUtils.fuzzyMatch(
                filter.lowercase(Locale.ROOT),
                lowercase(Locale.ROOT).stripAccents(),
            )
            return if (matched) MatchedResult(this, -1, filter.length) else null
        }
    }
}

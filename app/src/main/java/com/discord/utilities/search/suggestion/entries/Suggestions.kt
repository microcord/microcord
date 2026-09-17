package com.discord.utilities.search.suggestion.entries

import com.discord.utilities.user.UserUtils.getDisplayName
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.query.node.QueryNode
import com.discord.utilities.search.query.node.answer.HasAnswerOption
import com.discord.utilities.search.query.node.answer.AuthorTypeAnswerOption
import com.discord.utilities.search.strings.SearchStringProvider
import com.discord.utilities.user.UserUtils

/**
 * The concrete suggestion rows.
 *
 * Each type owns the `canComplete` test that decides whether it survives the text typed so far, so
 * the engine stays a dispatcher rather than a rulebook.
 */

/** "Type `from:` to filter by author" — offered while no filter is open. */
data class FilterSuggestion(val filterType: FilterType) : SearchSuggestion {
    override val category = SearchSuggestion.Category.FILTER

    companion object {
        fun canComplete(
            currentInput: CharSequence,
            filterType: FilterType,
            searchStringProvider: SearchStringProvider,
        ): Boolean = stringRepresentation(filterType, searchStringProvider).contains(currentInput)

        private fun stringRepresentation(
            filterType: FilterType,
            searchStringProvider: SearchStringProvider,
        ): String = when (filterType) {
            FilterType.FROM -> searchStringProvider.fromFilterString
            // Not a typo: the reference answers `mentions` for both MENTIONS and HAS here.
            FilterType.MENTIONS, FilterType.HAS -> searchStringProvider.mentionsFilterString
            FilterType.IN -> searchStringProvider.inFilterString
            FilterType.DURING -> searchStringProvider.duringFilterString
            FilterType.BEFORE -> searchStringProvider.beforeFilterString
            FilterType.AFTER -> searchStringProvider.afterFilterString
            FilterType.AUTHOR_TYPE -> searchStringProvider.authorTypeFilterString
            FilterType.LINK_FROM -> searchStringProvider.linkFromFilterString
            FilterType.FILE_TYPE -> searchStringProvider.fileTypeFilterString
            FilterType.FILE_NAME -> searchStringProvider.fileNameFilterString
            FilterType.PINNED -> searchStringProvider.pinnedFilterString
        } + ":"
    }
}

/** A media kind offered while `has:` is open. */
data class HasSuggestion(val hasAnswerOption: HasAnswerOption) : SearchSuggestion {
    override val category = SearchSuggestion.Category.HAS

    companion object {
        fun canComplete(
            currentInput: CharSequence,
            hasAnswerOption: HasAnswerOption,
            searchStringProvider: SearchStringProvider,
        ): Boolean = hasAnswerOption.getLocalizedInputText(searchStringProvider).contains(currentInput)
    }
}

data class AuthorTypeSuggestion(
    val option: AuthorTypeAnswerOption,
    val negated: Boolean,
    val text: String,
) : SearchSuggestion {
    override val category = SearchSuggestion.Category.AUTHOR_TYPE
}

data class DateSuggestion(
    val filterType: FilterType,
    val initialDateMillis: Long,
) : SearchSuggestion {
    override val category = SearchSuggestion.Category.BEFORE_DATE
}

/** A channel offered while `in:` is open. */
data class ChannelSuggestion(val channelName: String, val channelId: Long) : SearchSuggestion {
    override val category = SearchSuggestion.Category.IN_CHANNEL

    companion object {
        /** The leading `#` the user may already have typed is not part of the channel name. */
        fun canComplete(channelName: String, currentInput: CharSequence): Boolean {
            val typed = if (currentInput.isNotEmpty() && currentInput[0] == '#') {
                currentInput.drop(1)
            } else {
                currentInput
            }
            return channelName.contains(typed, ignoreCase = true)
        }
    }
}

/**
 * A user offered while `from:` or `mentions:` is open.
 *
 * Comparable so the engine can hold candidates in a sorted set: nickname first when there is one,
 * with the full `name#0000` as the tie-break, which is also the order the list renders in.
 */
data class UserSuggestion(
    val user: User,
    val targetType: TargetType,
    val guildMember: GuildMember?,
) : SearchSuggestion, Comparable<UserSuggestion> {

    enum class TargetType { FROM, MENTIONS }

    val userName: String = user.username
    val discriminator: Int = user.discriminator
    val userId: Long = user.id
    val nickname: String? = guildMember?.identityNick()
    /** Guild nickname, else the account display name, else the handle. */
    val displayName: String = user.getDisplayName(guildMember?.identityNick())
    val usernameWithDiscriminator: String = usernameWithDiscriminator(userName, discriminator)

    override val category: SearchSuggestion.Category = when (targetType) {
        TargetType.FROM -> SearchSuggestion.Category.FROM_USER
        TargetType.MENTIONS -> SearchSuggestion.Category.MENTIONS_USER
    }

    override fun compareTo(other: UserSuggestion): Int {
        val mine = displayName
        val theirs = other.displayName
        val byDisplayName = mine.compareTo(theirs)
        return if (byDisplayName != 0) {
            byDisplayName
        } else {
            usernameWithDiscriminator.compareTo(other.usernameWithDiscriminator)
        }
    }

    companion object {
        private fun usernameWithDiscriminator(userName: String, discriminator: Int): String =
            userName + UserUtils.discriminatorSuffix(discriminator)

        /** Matches on `name#0000` or on the guild nickname, either way case-insensitively. */
        fun canComplete(
            username: String,
            discriminator: Int,
            nickname: String?,
            currentInput: CharSequence,
        ): Boolean {
            val typed = currentInput.toString().lowercase()
            return usernameWithDiscriminator(username, discriminator).contains(typed, ignoreCase = true) ||
                nickname?.contains(typed, ignoreCase = true) == true
        }
    }
}

/** A query the user ran before, offered while the box is empty. */
data class RecentQuerySuggestion(val query: List<QueryNode>) : SearchSuggestion {
    override val category = SearchSuggestion.Category.RECENT_QUERY
}

package com.discord.utilities.search.suggestion

import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.api.permission.Permission
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.query.node.QueryNode
import com.discord.utilities.search.query.node.answer.HasAnswerOption
import com.discord.utilities.search.query.node.answer.AuthorTypeAnswerOption
import com.discord.utilities.search.query.node.answer.DateNode
import com.discord.utilities.search.query.node.content.ContentNode
import com.discord.utilities.search.query.node.filter.FilterNode
import com.discord.utilities.search.strings.SearchStringProvider
import com.discord.utilities.search.suggestion.entries.ChannelSuggestion
import com.discord.utilities.search.suggestion.entries.AuthorTypeSuggestion
import com.discord.utilities.search.suggestion.entries.DateSuggestion
import com.discord.utilities.search.suggestion.entries.FilterSuggestion
import com.discord.utilities.search.suggestion.entries.HasSuggestion
import com.discord.utilities.search.suggestion.entries.RecentQuerySuggestion
import com.discord.utilities.search.suggestion.entries.SearchSuggestion
import com.discord.utilities.search.suggestion.entries.UserSuggestion
import com.discord.utilities.search.validation.SearchData
import rx.subjects.PublishSubject
import rx.subjects.SerializedSubject
import java.util.TreeSet
import java.util.concurrent.TimeUnit

/**
 * Decides what to offer under the search box for the query typed so far.
 *
 * The rule is positional, not textual: whichever filter the caret currently sits inside determines
 * which *kind* of suggestion is offered, and the trailing free text then narrows it. With no filter
 * open the offer is the filter keywords themselves, plus — only when nothing at all has been typed —
 * the recent-query history.
 *
 * Typing inside `from:`/`mentions:` also asks the gateway for guild members matching the text, so
 * suggestions cover members the client has never loaded. That request is throttled and deduplicated
 * here rather than at the call site.
 */
object SearchSuggestionEngine {

    private const val MAX_ENTRY_TYPE_COUNT = 10

    /**
     * Above this many indexed users the engine stops sorting and takes the first ten matches it
     * finds. Sorting a large guild's whole member list on every keystroke is what this avoids.
     */
    private const val MAX_USER_SORTING_THRESHOLD = 100

    private val membersRequestSubject = SerializedSubject(PublishSubject.create<CharSequence>())

    /** Null while searching a private channel: there is no guild to request members from. */
    var targetGuildId: Long? = null

    init {
        setupMemberRequestSubscription()
    }

    fun getSuggestions(
        input: List<QueryNode>,
        searchData: SearchData,
        searchStringProvider: SearchStringProvider,
        recentQueries: Collection<List<QueryNode>>,
        inputEndsWithWhitespace: Boolean = false,
    ): List<SearchSuggestion> {
        val suggestions = ArrayList<SearchSuggestion>()
        val currentFilter = getCurrentFilterType(input, inputEndsWithWhitespace)
        val rawContent = if (inputEndsWithWhitespace) "" else getRawContent(input)

        if (currentFilter != null) {
            suggestions += getUserSuggestions(rawContent, currentFilter, searchData.users)
            suggestions += getChannelSuggestions(
                rawContent,
                currentFilter,
                searchData.channels,
                searchData.channelPermissions,
            )
            suggestions += getHasSuggestions(rawContent, currentFilter, searchStringProvider)
            suggestions += getAuthorTypeSuggestions(rawContent, currentFilter, searchStringProvider)
            suggestions += getDateSuggestions(rawContent, currentFilter)
        } else {
            suggestions += getFilterSuggestions(
                rawContent,
                searchStringProvider,
                includeInChannels = searchData.channels.isNotEmpty(),
            )
        }

        if (input.isEmpty()) suggestions += recentQueries.map(::RecentQuerySuggestion)
        return suggestions
    }

    /**
     * The filter the caret is inside: the last node when it is a filter, or the one before it when
     * the user has started typing an answer.
     */
    private fun getCurrentFilterType(input: List<QueryNode>, inputEndsWithWhitespace: Boolean): FilterType? {
        val last = input.lastOrNull() ?: return null
        if (last is FilterNode) return last.filterType
        if (input.size == 1) return null
        val previous = input[input.lastIndex - 1]
        return if (!inputEndsWithWhitespace && last is ContentNode && previous is FilterNode) {
            previous.filterType
        } else {
            null
        }
    }

    /** The trailing free text, which is what narrows the offered suggestions. */
    private fun getRawContent(input: List<QueryNode>): CharSequence =
        (input.lastOrNull() as? ContentNode)?.content?.trim() ?: ""

    private fun getFilterSuggestions(
        rawContent: CharSequence,
        searchStringProvider: SearchStringProvider,
        includeInChannels: Boolean,
    ): List<SearchSuggestion> =
        // `IN` is suppressed when the target has no channels to name (a DM); it stays reachable
        // through the `#` shorthand either way.
        FilterType.OFFERED
            .filter { includeInChannels || it != FilterType.IN }
            .filter { FilterSuggestion.canComplete(rawContent, it, searchStringProvider) }
            .map(::FilterSuggestion)

    private fun getHasSuggestions(
        rawContent: CharSequence,
        currentFilterType: FilterType,
        searchStringProvider: SearchStringProvider,
    ): List<SearchSuggestion> {
        if (currentFilterType != FilterType.HAS) return emptyList()
        return HasAnswerOption.OFFERED
            .filter { HasSuggestion.canComplete(rawContent, it, searchStringProvider) }
            .map(::HasSuggestion)
    }

    private fun getAuthorTypeSuggestions(
        rawContent: CharSequence,
        currentFilterType: FilterType,
        searchStringProvider: SearchStringProvider,
    ): List<AuthorTypeSuggestion> {
        if (currentFilterType != FilterType.AUTHOR_TYPE) return emptyList()
        val raw = rawContent.toString()
        val negated = raw.startsWith(HasAnswerOption.NEGATION_PREFIX)
        val typed = raw.removePrefix(HasAnswerOption.NEGATION_PREFIX)
        return AuthorTypeAnswerOption.entries.mapNotNull { option ->
            val label = option.getLocalizedInputText(searchStringProvider)
            if (!label.contains(typed, ignoreCase = true)) return@mapNotNull null
            val text = if (negated) HasAnswerOption.NEGATION_PREFIX + label else label
            AuthorTypeSuggestion(option, negated, text)
        }
    }

    private fun getDateSuggestions(
        rawContent: CharSequence,
        currentFilterType: FilterType,
        now: Long = System.currentTimeMillis(),
    ): List<DateSuggestion> {
        if (currentFilterType !in DATE_FILTERS) return emptyList()
        val typed = rawContent.toString()
        val initialDate = DateNode.parseRange(typed)?.first ?: now
        return listOf(DateSuggestion(currentFilterType, initialDate))
    }

    /** Only channels the viewer can actually read are offered, in the guild's own channel order. */
    private fun getChannelSuggestions(
        rawContent: CharSequence,
        currentFilter: FilterType,
        channels: Map<Long, Channel>,
        channelPermissions: Map<Long, Long>,
    ): List<ChannelSuggestion> {
        if (currentFilter != FilterType.IN) return emptyList()
        return channels.values
            .filter { PermissionUtils.can(Permission.VIEW_CHANNEL, channelPermissions[it.id]) }
            .filter { ChannelSuggestion.canComplete(it.getDisplayName(), rawContent) }
            .sortedWith(compareBy({ it.position }, { it.id }))
            .map { ChannelSuggestion(it.getDisplayName(), it.id) }
            .take(MAX_ENTRY_TYPE_COUNT)
    }

    private fun getUserSuggestions(
        rawContent: CharSequence,
        currentFilter: FilterType,
        users: Map<Long, com.discord.models.guild.UserGuildMember>,
    ): Collection<UserSuggestion> {
        val targetType = when (currentFilter) {
            FilterType.FROM -> UserSuggestion.TargetType.FROM
            FilterType.MENTIONS -> UserSuggestion.TargetType.MENTIONS
            else -> return emptyList()
        }

        membersRequestSubject.onNext(rawContent)

        val sortResults = users.size < MAX_USER_SORTING_THRESHOLD
        val matches = TreeSet<UserSuggestion>()
        users.values.forEach { userGuildMember ->
            val user = userGuildMember.user
            if (UserSuggestion.canComplete(
                    user.username,
                    user.discriminator,
                    userGuildMember.nickname,
                    rawContent,
                )
            ) {
                matches += UserSuggestion(user, targetType, userGuildMember.guildMember)
                if (!sortResults && matches.size >= MAX_ENTRY_TYPE_COUNT) return matches
            }
        }
        return matches.take(MAX_ENTRY_TYPE_COUNT)
    }

    private fun setupMemberRequestSubscription() {
        membersRequestSubject
            .leadingEdgeThrottle(750L, TimeUnit.MILLISECONDS)
            .map(CharSequence::toString)
            .filter(String::isNotEmpty)
            .distinctUntilChanged()
            .appSubscribe(SearchSuggestionEngine::class.java) { query ->
                targetGuildId?.let { guildId ->
                    StoreStream.getGatewayConnection().requestGuildMembers(guildId, query)
                }
            }
    }

    private val DATE_FILTERS = setOf(FilterType.DURING, FilterType.BEFORE, FilterType.AFTER)
}

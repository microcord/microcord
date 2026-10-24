package com.discord.utilities.search.query.parsing

import android.content.Context
import com.discord.simpleast.core.parser.ParseSpec
import com.discord.simpleast.core.parser.Parser
import com.discord.simpleast.core.parser.Rule
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.query.node.QueryNode
import com.discord.utilities.search.query.node.answer.AuthorTypeAnswerOption
import com.discord.utilities.search.query.node.answer.AuthorTypeNode
import com.discord.utilities.search.query.node.answer.ChannelNode
import com.discord.utilities.search.query.node.answer.DateNode
import com.discord.utilities.search.query.node.answer.PinnedNode
import com.discord.utilities.search.query.node.answer.HasAnswerOption
import com.discord.utilities.search.query.node.answer.HasNode
import com.discord.utilities.search.query.node.answer.UserNode
import com.discord.utilities.search.query.node.content.ContentNode
import com.discord.utilities.search.query.node.filter.FilterNode
import com.discord.utilities.search.strings.SearchStringProvider
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Turns the raw contents of the search box into a [QueryNode] AST.
 *
 * Rule order is the grammar. `alice#0001` is matched before any filter keyword so a username that
 * happens to contain one is not torn apart; each filter keyword is then matched before the answer
 * shapes that may follow it; free text is last and always matches, so parsing cannot stall.
 *
 * The keywords are localised, so a parser is only valid for the [SearchStringProvider] it was built
 * with and is rebuilt whenever the search target changes.
 */
class QueryParser(
    searchStringProvider: SearchStringProvider,
) : Parser<Context, QueryNode, Unit>(false) {

    init {
        addRule(userRule())
        addRule(filterRule(FilterType.FROM, searchStringProvider.fromFilterString))
        addRule(filterRule(FilterType.MENTIONS, searchStringProvider.mentionsFilterString))
        addRule(filterRule(FilterType.HAS, searchStringProvider.hasFilterString))
        addRule(hasAnswerRule(searchStringProvider))
        addRule(filterRule(FilterType.AUTHOR_TYPE, searchStringProvider.authorTypeFilterString))
        addRule(authorTypeAnswerRule(searchStringProvider))
        addRule(filterRule(FilterType.PINNED, searchStringProvider.pinnedFilterString))
        addRule(pinnedAnswerRule())
        // `during` and its `on` alias are one filter; both keywords have to be matched before the
        // date answer rule, or the date is consumed as free text.
        addRule(filterRule(FilterType.DURING, searchStringProvider.duringFilterString))
        addRule(filterRule(FilterType.DURING, searchStringProvider.onFilterString))
        addRule(filterRule(FilterType.BEFORE, searchStringProvider.beforeFilterString))
        addRule(filterRule(FilterType.AFTER, searchStringProvider.afterFilterString))
        addRule(dateAnswerRule())
        addRule(filterRule(FilterType.LINK_FROM, searchStringProvider.linkFromFilterString))
        addRule(filterRule(FilterType.FILE_TYPE, searchStringProvider.fileTypeFilterString))
        addRule(filterRule(FilterType.FILE_NAME, searchStringProvider.fileNameFilterString))
        addRule(filterRule(FilterType.IN, searchStringProvider.inFilterString))
        addRule(inAnswerRule())
        addRule(contentRule())
    }

    companion object {
        private const val ANY_TOKEN_REGEX = """^[\s]*[\S]*[\s]*"""
        /**
         * An answer value must be a whole word: without it `forwardmessages.zip` parsed as the
         * `forward` value followed by `messages.zip`, and was searched and saved to history split.
         */
        private const val WORD_END = """(?=\s|$)"""
        private const val DATE_ANSWER_REGEX = """(?:\s*([0-9]{4}(?:-[0-9]{1,2}){0,2}))$WORD_END"""
        private const val BOOLEAN_ANSWER_REGEX = """(?:\s*(true|false))$WORD_END"""
        private const val IN_ANSWER_REGEX = """(?:\s*#([^ ]+))"""
        private const val USER_REGEX = """(?:\s*([^@#:]+)#([0-9]{4}))"""

        private fun compile(regex: String): Pattern = Pattern.compile(regex, Pattern.UNICODE_CASE)

        private fun rule(
            pattern: Pattern,
            node: (Matcher) -> QueryNode,
        ): Rule<Context, QueryNode, Unit> = object : Rule<Context, QueryNode, Unit>(pattern) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<Context, in QueryNode, Unit>,
                state: Unit,
            ): ParseSpec<Context, Unit> = ParseSpec(node(matcher), state)
        }

        /** `word:` for one localised filter keyword. */
        fun filterRule(filterType: FilterType, localizedFilter: CharSequence): Rule<Context, QueryNode, Unit> =
            rule(compile("""^[\s]*?$localizedFilter:""")) { FilterNode(filterType, localizedFilter) }

        /** `alice#0001`. The four-digit discriminator is what makes this unambiguous. */
        fun userRule(): Rule<Context, QueryNode, Unit> =
            rule(compile("^$USER_REGEX")) { matcher ->
                UserNode(matcher.group(1)!!, matcher.group(2)!!.toInt())
            }

        /** `#channel-name`. */
        fun inAnswerRule(): Rule<Context, QueryNode, Unit> =
            rule(compile("^$IN_ANSWER_REGEX")) { matcher -> ChannelNode(matcher.group(1)!!) }

        /** One of the localised `has:` values. */
        fun hasAnswerRule(searchStringProvider: SearchStringProvider): Rule<Context, QueryNode, Unit> =
            rule(compile("^" + createHasAnswerRegex(searchStringProvider))) { matcher ->
                HasNode(matcher.group(1)!!, searchStringProvider)
            }

        /** Terminal rule: consumes one whitespace-delimited token and trims it. */
        fun contentRule(): Rule<Context, QueryNode, Unit> =
            rule(compile(ANY_TOKEN_REGEX)) { matcher -> ContentNode(matcher.group().trim()) }

        /** `authorType:bot`, negation included. */
        fun authorTypeAnswerRule(searchStringProvider: SearchStringProvider): Rule<Context, QueryNode, Unit> =
            rule(compile("^" + createNegatableOptionsRegex(
                AuthorTypeAnswerOption.values().map { it.getLocalizedInputText(searchStringProvider) },
            ))) { matcher -> AuthorTypeNode(matcher.group(1)!!, searchStringProvider) }

        /** `pinned:true`. A value that is not a boolean falls through to free text. */
        fun pinnedAnswerRule(): Rule<Context, QueryNode, Unit> =
            rule(compile("^$BOOLEAN_ANSWER_REGEX")) { matcher ->
                val raw = matcher.group(1)!!
                PinnedNode(PinnedNode.parse(raw) == true, raw)
            }

        /**
         * A bare `yyyy[-MM[-dd]]`. Which bound it becomes is decided by the filter it follows, so
         * one rule serves `before:`, `after:` and `during:` — [DateNode] carries the filter.
         */
        fun dateAnswerRule(): Rule<Context, QueryNode, Unit> =
            rule(compile("^$DATE_ANSWER_REGEX")) { matcher -> DateNode(matcher.group(1)!!) }

        private fun createHasAnswerRegex(searchStringProvider: SearchStringProvider): String =
            createNegatableOptionsRegex(
                HasAnswerOption.values().map { it.getLocalizedInputText(searchStringProvider) },
            )

        /** `(?:\s*(-?(?:a|b|c)))` — the reference's negation rides on the value, not the keyword. */
        private fun createNegatableOptionsRegex(options: List<String>): String =
            options.joinToString(
                separator = "|",
                prefix = """(?:\s*(-?(?:""",
                postfix = ")))$WORD_END",
            )
    }
}

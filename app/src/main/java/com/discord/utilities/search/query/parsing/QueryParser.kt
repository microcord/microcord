package com.discord.utilities.search.query.parsing

import android.content.Context
import com.discord.simpleast.core.parser.ParseSpec
import com.discord.simpleast.core.parser.Parser
import com.discord.simpleast.core.parser.Rule
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.query.node.QueryNode
import com.discord.utilities.search.query.node.answer.ChannelNode
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
        addRule(filterRule(FilterType.IN, searchStringProvider.inFilterString))
        addRule(inAnswerRule())
        addRule(contentRule())
    }

    companion object {
        private const val ANY_TOKEN_REGEX = """^[\s]*[\S]*[\s]*"""
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

        private fun createHasAnswerRegex(searchStringProvider: SearchStringProvider): String =
            HasAnswerOption.values().joinToString(
                separator = "|",
                prefix = """(?:\s*(""",
                postfix = "))",
            ) { it.getLocalizedInputText(searchStringProvider) }
    }
}

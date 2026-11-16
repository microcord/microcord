package com.discord.utilities.search

import com.discord.utilities.search.network.SearchQuery
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.query.node.QueryNode
import com.discord.utilities.search.query.node.QueryNode.Preprocessor.preprocess
import com.discord.utilities.search.query.node.answer.ChannelNode
import com.discord.utilities.search.query.node.answer.HasAnswerOption
import com.discord.utilities.search.query.node.answer.HasNode
import com.discord.utilities.search.query.node.answer.UserNode
import com.discord.utilities.search.query.node.answer.DateNode
import com.discord.utilities.search.query.node.content.ContentNode
import com.discord.utilities.search.query.node.filter.FilterNode
import com.discord.utilities.search.query.parsing.QueryParser
import com.discord.utilities.search.strings.SearchStringProvider
import com.discord.utilities.search.validation.SearchData
import com.discord.utilities.search.suggestion.SearchSuggestionEngine
import com.discord.utilities.search.suggestion.entries.FilterSuggestion
import com.discord.utilities.search.suggestion.entries.AuthorTypeSuggestion
import com.discord.utilities.search.suggestion.entries.DateSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** English keywords, so the grammar under test is the one a default-locale client compiles. */
private object EnglishSearchStrings : SearchStringProvider {
    override val fromFilterString = "from"
    override val inFilterString = "in"
    override val mentionsFilterString = "mentions"
    override val hasFilterString = "has"
    override val duringFilterString = "during"
    override val onFilterString = "on"
    override val beforeFilterString = "before"
    override val afterFilterString = "after"
    override val authorTypeFilterString = "authorType"
    override val linkFromFilterString = "linkFrom"
    override val fileTypeFilterString = "fileType"
    override val fileNameFilterString = "fileName"
    override val pinnedFilterString = "pinned"
    override val linkAnswerString = "link"
    override val embedAnswerString = "embed"
    override val fileAnswerString = "file"
    override val videoAnswerString = "video"
    override val imageAnswerString = "image"
    override val soundAnswerString = "sound"
    override val stickerAnswerString = "sticker"
    override val pollAnswerString = "poll"
    override val forwardAnswerString = "forward"
    override val userAuthorTypeAnswerString = "user"
    override val botAuthorTypeAnswerString = "bot"
    override val webhookAuthorTypeAnswerString = "webhook"
}

class QueryParserTest {

    private fun parse(text: String): List<QueryNode> =
        QueryParser(EnglishSearchStrings).parse(text, Unit)

    @Test
    fun parsesFilterAndAnswerPairs() {
        val nodes = parse("from:alice#0001 has:image hello")

        assertEquals(FilterType.FROM, (nodes[0] as FilterNode).filterType)
        assertEquals(UserNode("alice", 1), nodes[1])
        assertEquals(FilterType.HAS, (nodes[2] as FilterNode).filterType)
        assertEquals(HasAnswerOption.IMAGE, (nodes[3] as HasNode).hasAnswerOption)
        assertTrue(nodes.drop(4).all { it is ContentNode })
    }

    @Test
    fun answerValuesAreWholeWords() {
        assertEquals(listOf(ContentNode("forwardmessages.zip") as QueryNode), parse("forwardmessages.zip"))
        assertEquals(listOf(ContentNode("linkedin") as QueryNode), parse("linkedin"))
        assertEquals(HasAnswerOption.FORWARD, (parse("has:forward x")[1] as HasNode).hasAnswerOption)
    }

    @Test
    fun parsesChannelAnswers() {
        val nodes = parse("in:#general")
        assertEquals(FilterType.IN, (nodes[0] as FilterNode).filterType)
        assertEquals(ChannelNode("general"), nodes[1])
    }

    /** Free text is the terminal rule, so anything unrecognised still parses rather than stalling. */
    @Test
    fun unrecognisedTextBecomesContent() {
        val nodes = parse("hello world")
        nodes.toMutableList().let { mutable ->
            mutable.preprocess(SearchData())
            assertEquals(listOf(ContentNode("hello world") as QueryNode), mutable)
        }
    }

    @Test
    fun preprocessingDropsATrailingBlankToken() {
        val nodes = parse("hello ").toMutableList()
        nodes.preprocess(SearchData())
        assertEquals(1, nodes.size)
        assertEquals("hello", (nodes[0] as ContentNode).content)
    }

    @Test
    fun buildsRestParametersFromTheAst() {
        val searchData = SearchData(
            usernameAndDiscriminatorIndex = mapOf("alice1" to 7L),
            channelNameIndex = mapOf("general" to listOf(11L)),
        )
        val nodes = parse("from:alice#0001 in:#general has:image hello").toMutableList()
        nodes.preprocess(searchData)

        val query = SearchQuery.Builder().setIncludeNsfw(true).buildFrom(nodes, searchData)

        assertEquals(listOf("7"), query.params[SearchQuery.PARAM_AUTHOR_ID])
        assertEquals(listOf("11"), query.params[SearchQuery.PARAM_CHANNEL_ID])
        assertEquals(listOf("image"), query.params[SearchQuery.PARAM_HAS])
        assertEquals(listOf("hello"), query.params[SearchQuery.PARAM_CONTENT])
        assertTrue(query.includeNsfw)
        assertTrue(query.isValid)
    }

    @Test
    fun modernUsernameUnderFromBecomesAuthorId() {
        val searchData = SearchData(usernameIndex = mapOf("manti" to 7L))
        val nodes = parse("from:MANTI").toMutableList()
        nodes.preprocess(searchData)

        val query = SearchQuery.Builder().buildFrom(nodes, searchData)

        assertEquals(listOf("7"), query.params[SearchQuery.PARAM_AUTHOR_ID])
        assertEquals(null, query.params[SearchQuery.PARAM_CONTENT])

        // The answer is not merged into the words after it.
        val withText = parse("from:manti hello world").toMutableList()
        withText.preprocess(searchData)
        val textQuery = SearchQuery.Builder().buildFrom(withText, searchData)
        assertEquals(listOf("7"), textQuery.params[SearchQuery.PARAM_AUTHOR_ID])
        assertEquals(listOf("hello world"), textQuery.params[SearchQuery.PARAM_CONTENT])
    }

    @Test
    fun trailingSpaceAfterUserAnswerReturnsTopLevelFilters() {
        val suggestions = SearchSuggestionEngine.getSuggestions(
            parse("from:manti"),
            SearchData(),
            EnglishSearchStrings,
            emptyList(),
            inputEndsWithWhitespace = true,
        )

        assertTrue(suggestions.isNotEmpty())
        assertTrue(suggestions.all { it is FilterSuggestion })
    }

    @Test
    fun authorTypeAndDateFiltersOfferAnswers() {
        val authorTypes = SearchSuggestionEngine.getSuggestions(
            parse("authorType:"), SearchData(), EnglishSearchStrings, emptyList(),
        )
        val dates = SearchSuggestionEngine.getSuggestions(
            parse("before:"), SearchData(), EnglishSearchStrings, emptyList(),
        )

        assertEquals(3, authorTypes.filterIsInstance<AuthorTypeSuggestion>().size)
        assertEquals(1, dates.filterIsInstance<DateSuggestion>().size)
        assertTrue(dates.filterIsInstance<DateSuggestion>().single().initialDateMillis > 0L)
    }

    @Test
    fun beforeAndAfterCoexistAndPickerDateUsesLocalDay() {
        val query = SearchQuery.Builder().buildFrom(
            parse("before:2026-01-03 after:2026-01-01"),
            SearchData(),
        )
        assertTrue(query.params[SearchQuery.PARAM_MIN_ID]?.isNotEmpty() == true)
        assertTrue(query.params[SearchQuery.PARAM_MAX_ID]?.isNotEmpty() == true)

        val original = TimeZone.getDefault()
        try {
            val zone = TimeZone.getTimeZone("GMT+03:00")
            TimeZone.setDefault(zone)
            val selected = Calendar.getInstance(zone).apply {
                clear()
                set(2026, Calendar.JANUARY, 2)
            }.timeInMillis
            assertEquals("2026-01-02", DateNode.formatLocalDate(selected))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    /** An answer under a filter it does not answer degrades to searchable text, not to nothing. */
    @Test
    fun answersUnderTheWrongFilterBecomeContent() {
        val searchData = SearchData(usernameAndDiscriminatorIndex = mapOf("alice1" to 7L))
        val nodes = parse("has:alice#0001").toMutableList()
        nodes.preprocess(searchData)

        val query = SearchQuery.Builder().buildFrom(nodes, searchData)

        assertEquals(null, query.params[SearchQuery.PARAM_AUTHOR_ID])
        assertEquals(listOf("alice#0001"), query.params[SearchQuery.PARAM_CONTENT])
    }

    @Test
    fun userAnswersAcceptIdsAndMe() {
        val searchData = SearchData.Builder().buildForGuild(emptyMap(), emptyMap(), emptyMap(), emptyMap(), meId = 5L)
        val query = SearchQuery.Builder().buildFrom(parse("from:@me mentions:123456789012345678"), searchData)

        assertEquals(listOf("5"), query.params[SearchQuery.PARAM_AUTHOR_ID])
        assertEquals(listOf("123456789012345678"), query.params[SearchQuery.PARAM_MENTIONS])
    }

    @Test
    fun channelAnswersAcceptQuotesSharedNamesAndIds() {
        val searchData = SearchData(channelNameIndex = mapOf("voice chat" to listOf(1L), "general" to listOf(2L, 3L)))
        val nodes = parse("""in:"voice chat" in:general in:123456789012345678 "not a channel"""").toMutableList()
        nodes.preprocess(searchData)
        val query = SearchQuery.Builder().buildFrom(nodes, searchData)

        assertEquals(listOf("1", "2", "3", "123456789012345678"), query.params[SearchQuery.PARAM_CHANNEL_ID])
        assertEquals(listOf("\"not a channel\""), query.params[SearchQuery.PARAM_CONTENT])
        assertEquals("#\"voice chat\"", (nodes[1] as ChannelNode).text)
    }

    @Test
    fun dateWordsResolveOnlyAfterADateFilter() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.MARCH, 18, 15, 30) }
        val yesterday = DateNode.parseRange("yesterday", EnglishSearchStrings, now)!!
        val start = Calendar.getInstance().apply { timeInMillis = yesterday.first }
        assertEquals(17, start.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, start.get(Calendar.HOUR_OF_DAY))
        assertEquals(24 * 60 * 60 * 1000L, yesterday.second - yesterday.first)
        assertEquals(Calendar.JANUARY, Calendar.getInstance().apply {
            // Month names follow the locale, as RN's moment does.
            val january = java.text.DateFormatSymbols.getInstance().months[0]
            timeInMillis = DateNode.parseRange(january, EnglishSearchStrings, now)!!.first
        }.get(Calendar.MONTH))

        val query = SearchQuery.Builder().buildFrom(parse("after:Today today"), SearchData())
        assertTrue(query.params[SearchQuery.PARAM_MIN_ID]?.isNotEmpty() == true)
        assertEquals(listOf("today"), query.params[SearchQuery.PARAM_CONTENT])
    }

    @Test
    fun repeatedTextIsSearchedOnce() {
        val query = SearchQuery.Builder().buildFrom(parse("hi has:link hi"), SearchData())
        assertEquals(listOf("hi"), query.params[SearchQuery.PARAM_CONTENT])
    }

    @Test
    fun anEmptyQueryIsNotValid() {
        val nodes = parse("   ").toMutableList()
        nodes.preprocess(SearchData())
        assertTrue(!SearchQuery.Builder().buildFrom(nodes, SearchData()).isValid)
    }
}

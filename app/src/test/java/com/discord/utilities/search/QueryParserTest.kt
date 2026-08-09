package com.discord.utilities.search

import com.discord.utilities.search.network.SearchQuery
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.query.node.QueryNode
import com.discord.utilities.search.query.node.QueryNode.Preprocessor.preprocess
import com.discord.utilities.search.query.node.answer.ChannelNode
import com.discord.utilities.search.query.node.answer.HasAnswerOption
import com.discord.utilities.search.query.node.answer.HasNode
import com.discord.utilities.search.query.node.answer.UserNode
import com.discord.utilities.search.query.node.content.ContentNode
import com.discord.utilities.search.query.node.filter.FilterNode
import com.discord.utilities.search.query.parsing.QueryParser
import com.discord.utilities.search.strings.SearchStringProvider
import com.discord.utilities.search.validation.SearchData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
            channelNameIndex = mapOf("general" to 11L),
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
    fun anEmptyQueryIsNotValid() {
        val nodes = parse("   ").toMutableList()
        nodes.preprocess(SearchData())
        assertTrue(!SearchQuery.Builder().buildFrom(nodes, SearchData()).isValid)
    }
}

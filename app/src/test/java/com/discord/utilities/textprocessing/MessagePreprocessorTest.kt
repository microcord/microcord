package com.discord.utilities.textprocessing

import com.discord.api.message.embed.EmbedType
import com.discord.api.message.embed.MessageEmbed
import com.discord.simpleast.core.node.Node
import com.discord.stores.StoreMessageState
import com.discord.utilities.textprocessing.node.BlockQuoteNode
import com.discord.utilities.textprocessing.node.EmojiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the AST transformations applied between parsing and rendering. */
class MessagePreprocessorTest {

    @Test
    fun messagesMadeOnlyOfEmojiAreJumboified() {
        val ast = parse("<:a:1><:b:2>")

        preprocessor().process(ast)

        assertTrue(ast.emoji().isNotEmpty())
        assertTrue(ast.emoji().all { it.isJumbo })
    }

    @Test
    fun whitespaceBetweenEmojiDoesNotBreakJumboification() {
        val ast = parse("<:a:1> <:b:2>")

        preprocessor().process(ast)

        assertTrue(ast.emoji().all { it.isJumbo })
    }

    @Test
    fun anyRealTextDisqualifiesJumboEmoji() {
        val ast = parse("hey <:a:1>")

        preprocessor().process(ast)

        assertTrue(ast.emoji().none { it.isJumbo })
    }

    @Test
    fun tooManyEmojiDisqualifyJumbo() {
        val ast = parse((1..28).joinToString("") { "<:a$it:$it>" })

        preprocessor().process(ast)

        assertEquals(28, ast.emoji().size)
        assertTrue(ast.emoji().none { it.isJumbo })
    }

    @Test
    fun spoilersAreNumberedAndRevealedFromPersistedState() {
        val ast = parse("||one|| and ||two||")

        val preprocessor = preprocessor(visibleSpoilerNodeIndices = setOf(1))
        preprocessor.process(ast)

        val spoilers = preprocessor.spoilers.orEmpty()
        assertEquals(2, spoilers.size)
        assertEquals(listOf(0, 1), spoilers.map { it.id })
        assertFalse(spoilers[0].isRevealed)
        assertTrue(spoilers[1].isRevealed)
    }

    @Test
    fun hiddenSpoilersHideTheirNestedSpoilerableChildren() {
        val ast = parse("||<:a:1>||")

        preprocessor().process(ast)

        assertTrue(ast.emoji().none { it.isRevealed })
    }

    @Test
    fun revealedSpoilersLeaveTheirChildrenVisible() {
        val ast = parse("||<:a:1>||")

        preprocessor(visibleSpoilerNodeIndices = setOf(0)).process(ast)

        assertTrue(ast.emoji().all { it.isRevealed })
    }

    @Test
    fun spoilersAndLinksMarkTheMessageAsLinkifyConflicting() {
        assertFalse(preprocessor().also { it.process(parse("plain text")) }.isLinkifyConflicting)
        assertTrue(preprocessor().also { it.process(parse("||hidden||")) }.isLinkifyConflicting)
        assertTrue(
            preprocessor().also { it.process(parse("https://example.com")) }.isLinkifyConflicting,
        )
    }

    @Test
    fun consecutiveQuoteLinesMergeIntoOneQuote() {
        val ast = parse("> first\n> second\n")

        preprocessor().process(ast)

        val quotes = ast.filterIsInstance<BlockQuoteNode<MessageRenderContext>>()
        assertEquals(1, quotes.size)
    }

    @Test
    fun aMessageThatIsOnlyItsOwnImageEmbedLinkRendersNoText() {
        val ast = parse("https://example.com/cat.png")

        preprocessor(embeds = listOf(imageEmbed())).process(ast)

        assertTrue(ast.isEmpty())
    }

    @Test
    fun aLinkWithATitledEmbedKeepsItsText() {
        val ast = parse("https://example.com/cat.png")

        preprocessor(embeds = listOf(imageEmbed(title = "Cat"))).process(ast)

        assertEquals(1, ast.size)
    }

    @Test
    fun maxNodesTrimsThePreviewToTheRequestedBudget() {
        val ast = parse("||a|| ||b|| ||c|| ||d||")

        preprocessor(maxNodes = 2).process(ast)

        assertTrue(ast.size < 4)
    }

    @Test
    fun theMessageStateConstructorReadsRevealedSpoilerIndices() {
        val preprocessor = MessagePreprocessor(
            myUserId = 7L,
            messageState = StoreMessageState.State(visibleSpoilerNodeIndices = setOf(0)),
        )
        val ast = parse("||hidden||")

        preprocessor.process(ast)

        assertEquals(7L, preprocessor.myUserId)
        assertTrue(preprocessor.spoilers.orEmpty().single().isRevealed)
    }

    @Test
    fun aPreprocessorWithoutStateLeavesEverythingHidden() {
        val preprocessor = MessagePreprocessor(myUserId = 7L, messageState = null)
        val ast = parse("||hidden||")

        preprocessor.process(ast)

        assertEquals(0, preprocessor.spoilers.orEmpty().single().id)
        assertFalse(preprocessor.spoilers.orEmpty().single().isRevealed)
    }

    private fun parse(text: String): MutableList<Node<MessageRenderContext>> = DiscordParser.parse(text)

    private fun preprocessor(
        visibleSpoilerNodeIndices: Collection<Int>? = null,
        embeds: List<MessageEmbed>? = null,
        shouldJumboify: Boolean = true,
        maxNodes: Int? = null,
    ) = MessagePreprocessor(MY_USER_ID, visibleSpoilerNodeIndices, embeds, shouldJumboify, maxNodes)

    private companion object {
        const val MY_USER_ID = 42L

        fun imageEmbed(title: String? = null) = MessageEmbed(
            title = title,
            type = EmbedType.IMAGE,
            description = null,
            url = "https://example.com/cat.png",
            timestamp = null,
            color = null,
            footer = null,
            image = null,
            thumbnail = null,
            video = null,
            provider = null,
            author = null,
            fields = null,
        )

        fun List<Node<MessageRenderContext>>.emoji(): List<EmojiNode<*>> {
            val found = ArrayList<EmojiNode<*>>()
            fun visit(node: Node<*>) {
                if (node is EmojiNode<*>) found.add(node)
                node.getChildren()?.forEach(::visit)
            }
            forEach(::visit)
            return found
        }
    }
}

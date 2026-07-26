package com.discord.utilities.textprocessing

import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.node.TextNode
import com.discord.utilities.textprocessing.node.EmojiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Regression guard for the previous renderer, which appended Drawee image spans to the builder and
 * only afterwards applied markdown by running `replace()` over that same builder. Every span
 * anchored after an edited range silently shifted, so a bold or italic run next to a custom emoji
 * corrupted the emoji's image span offsets.
 *
 * The rewritten pipeline consumes markdown markers while parsing, so images and styled runs are
 * already at their final offsets when rendering starts and the builder is never edited afterwards.
 * The assertions below pin that property at the level a JVM test can observe: marker characters
 * never reach the rendered text, and the nodes keep their source order and content around a styled
 * run. Verifying the resulting span indices on a real `DraweeSpanStringBuilder` needs an
 * instrumented test, because Android span classes cannot be constructed on the JVM.
 */
class MarkdownSpanOffsetRegressionTest {

    @Test
    fun aStyledRunBetweenTwoImagesKeepsEveryNodeInSourceOrder() {
        val nodes = DiscordParser.parse("<:first:1>`code`<:second:2>")

        assertEquals(listOf("first", "code", "second"), textPieces(nodes))
        assertEquals(listOf("first", "second"), emojiNames(nodes))
    }

    @Test
    fun markersAreConsumedByTheParserSoRenderedTextNeverNeedsEditing() {
        assertFalse(textPieces(DiscordParser.parse("`code`")).any { it.contains('`') })
        assertFalse(textPieces(DiscordParser.parse("||hidden||")).any { it.contains('|') })
        assertFalse(
            textPieces(DiscordParser.parse("```\nblock\n```")).any { it.contains("```") },
        )
    }

    @Test
    fun escapedMarkersStayLiteralWithoutDisturbingNeighbouringImages() {
        val nodes = DiscordParser.parse("<:first:1>\\*x\\*<:second:2>")

        assertEquals("first*x*second", textPieces(nodes).joinToString(""))
        assertEquals(listOf("first", "second"), emojiNames(nodes))
    }

    @Test
    fun anImageInsideAnUnrevealedSpoilerStillOccupiesItsOwnPosition() {
        val nodes = DiscordParser.parse("a||<:hidden:1>||b")
        MessagePreprocessor(myUserId = 1L, messageState = null).process(nodes)

        assertEquals(listOf("a", "hidden", "b"), textPieces(nodes))
        assertFalse(emojiNodes(nodes).single().isRevealed)
    }

    private companion object {

        /** Pre-order list of the non-empty text each node contributes when rendered. */
        fun textPieces(nodes: Collection<Node<*>>): List<String> {
            val pieces = ArrayList<String>()
            fun visit(node: Node<*>) {
                if (node is TextNode<*> && node.content.isNotEmpty()) pieces.add(node.content)
                node.getChildren()?.forEach(::visit)
            }
            nodes.forEach(::visit)
            return pieces
        }

        fun emojiNodes(nodes: Collection<Node<*>>): List<EmojiNode<*>> {
            val found = ArrayList<EmojiNode<*>>()
            fun visit(node: Node<*>) {
                if (node is EmojiNode<*>) found.add(node)
                node.getChildren()?.forEach(::visit)
            }
            nodes.forEach(::visit)
            return found
        }

        fun emojiNames(nodes: Collection<Node<*>>): List<String> = emojiNodes(nodes).map { it.content }
    }
}

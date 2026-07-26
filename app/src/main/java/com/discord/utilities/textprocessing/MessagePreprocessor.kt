package com.discord.utilities.textprocessing

import com.discord.api.message.embed.EmbedType
import com.discord.api.message.embed.MessageEmbed
import com.discord.simpleast.code.CodeNode
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.node.StyleNode
import com.discord.simpleast.core.node.TextNode
import com.discord.stores.StoreMessageState
import com.discord.utilities.textprocessing.node.BasicRenderContext
import com.discord.utilities.textprocessing.node.BlockQuoteNode
import com.discord.utilities.textprocessing.node.EmojiNode
import com.discord.utilities.textprocessing.node.SpoilerNode
import com.discord.utilities.textprocessing.node.Spoilerable
import com.discord.utilities.textprocessing.node.UrlNode

/**
 * Post-processes a freshly parsed message AST before it is rendered.
 *
 * Responsibilities, in the order they are applied:
 *  - drop the link node of a message whose only content is the URL of its own simple embed,
 *  - number the spoilers so the message state store can remember which ones were revealed,
 *  - decide whether the message qualifies for jumbo emoji,
 *  - merge consecutive quote nodes into one visual quote,
 *  - and optionally cap the number of rendered nodes for previews.
 */
class MessagePreprocessor(
    val myUserId: Long,
    private val visibleSpoilerNodeIndices: Collection<Int>? = null,
    private val embeds: List<MessageEmbed>? = null,
    private var shouldJumboify: Boolean = true,
    private val maxNodes: Int? = null,
) {

    constructor(myUserId: Long, messageState: StoreMessageState.State?) : this(
        myUserId,
        messageState?.visibleSpoilerNodeIndices,
        null,
        false,
        null,
    )

    constructor(
        myUserId: Long,
        messageState: StoreMessageState.State?,
        embeds: List<MessageEmbed>?,
        shouldJumboify: Boolean,
        maxNodes: Int?,
    ) : this(myUserId, messageState?.visibleSpoilerNodeIndices, embeds, shouldJumboify, maxNodes)

    private var customEmojiCount = 0
    private var hasLinkConflictingNode = false

    var spoilers: MutableList<SpoilerNode<*>>? = null
        private set

    /**
     * True when the message contains something that would be corrupted by `Linkify`, so the view
     * must not run autolinking over the rendered text.
     */
    val isLinkifyConflicting: Boolean
        get() = hasLinkConflictingNode || !spoilers.isNullOrEmpty()

    fun <R : BasicRenderContext> process(ast: MutableCollection<Node<R>>) {
        stripSimpleEmbedLink(ast)
        traversePreOrder(ast, ::inspectNode)
        if (shouldJumboify) {
            traversePreOrder(ast) { node -> (node as? EmojiNode<*>)?.isJumbo = true }
        }
        processSpoilerChildren()
        processQuoteChildren(ast)
        constrainAst(ast)
    }

    /**
     * Runs for every node in the tree.
     *
     * Jumboification survives only while every node seen so far is an emoji (up to the cap), a
     * style wrapper, or blank text; the first piece of real text disqualifies the message.
     */
    private fun inspectNode(node: Node<*>) {
        if (shouldJumboify) {
            shouldJumboify = when {
                node is EmojiNode<*> -> ++customEmojiCount <= MAX_JUMBO_EMOJI_COUNT
                node is StyleNode<*, *> -> true
                node is TextNode<*> -> node.content.isBlank()
                else -> false
            }
        }

        when (node) {
            is CodeNode<*>, is UrlNode<*> -> hasLinkConflictingNode = true
            is SpoilerNode<*> -> {
                val spoilerNodes = spoilers ?: ArrayList<SpoilerNode<*>>().also { spoilers = it }
                val index = spoilerNodes.size
                spoilerNodes.add(node)
                node.updateState(index, visibleSpoilerNodeIndices?.contains(index) == true)
            }
            else -> Unit
        }
    }

    /**
     * A message whose whole body is the link that produced its only embed renders as the embed
     * alone, so the duplicated URL text is removed.
     */
    private fun <R : BasicRenderContext> stripSimpleEmbedLink(ast: MutableCollection<Node<R>>) {
        if (ast.size != 1) return
        val embed = embeds?.singleOrNull() ?: return
        if (ast.first() is UrlNode<*> && isSimpleEmbed(embed)) {
            ast.clear()
        }
    }

    private fun processSpoilerChildren() {
        spoilers
            ?.filterNot { it.isRevealed }
            ?.forEach { spoiler ->
                traversePreOrder(spoiler.getChildren().orEmpty()) { node ->
                    (node as? Spoilerable)?.isRevealed = false
                }
            }
    }

    private fun <R : BasicRenderContext> processQuoteChildren(ast: MutableCollection<Node<R>>) {
        mergeConsecutiveQuoteNodes(ast)
        traversePreOrder(ast) { node ->
            @Suppress("UNCHECKED_CAST")
            (node.getChildren() as? MutableCollection<Node<R>>)?.let(::mergeConsecutiveQuoteNodes)
        }
    }

    /**
     * `> a` on one line and `> b` on the next are one quote, not two stacked quotes: every run of
     * adjacent quote nodes is folded into the first node of the run.
     */
    fun <R : BasicRenderContext> mergeConsecutiveQuoteNodes(nodes: MutableCollection<Node<R>>) {
        val absorbed = ArrayList<Node<R>>()
        var head: BlockQuoteNode<R>? = null
        for (node in nodes) {
            if (node !is BlockQuoteNode<*>) {
                head = null
                continue
            }
            @Suppress("UNCHECKED_CAST")
            val quote = node as BlockQuoteNode<R>
            val currentHead = head
            if (currentHead == null) {
                head = quote
            } else {
                absorbed.add(quote)
                quote.getChildren()?.forEach { currentHead.addChild(it) }
            }
        }
        if (absorbed.isNotEmpty()) {
            nodes.removeAll(absorbed.toSet())
        }
    }

    private fun <R : BasicRenderContext> constrainAst(ast: MutableCollection<Node<R>>) {
        val limit = maxNodes ?: return
        constrainAst(ast, ConstrainState(limit))
    }

    private fun <R : BasicRenderContext> constrainAst(
        nodes: MutableCollection<Node<R>>,
        state: ConstrainState,
    ) {
        val iterator = nodes.iterator()
        while (iterator.hasNext()) {
            val node = iterator.next()
            // Plain text is free: only structural nodes count against the budget.
            if (node.javaClass != TextNode::class.java) {
                state.limit--
            }
            if (state.limit <= 0) {
                iterator.remove()
            } else if (node.hasChildren()) {
                @Suppress("UNCHECKED_CAST")
                constrainAst(node.getChildren() as MutableCollection<Node<R>>, state)
                if (!node.hasChildren()) {
                    iterator.remove()
                }
            }
        }
    }

    private data class ConstrainState(var limit: Int)

    private companion object {
        /** More emoji than this and the message stops being "just a few big emoji". */
        const val MAX_JUMBO_EMOJI_COUNT = 27

        /**
         * Mirrors `EmbedResourceUtils.isSimpleEmbed`: an image or GIF embed with no title and no
         * author, i.e. one that carries nothing the link text is not already showing.
         */
        fun isSimpleEmbed(embed: MessageEmbed): Boolean = when (embed.type) {
            EmbedType.IMAGE -> embed.author == null && embed.title == null
            EmbedType.GIFV -> true
            else -> false
        }

        fun traversePreOrder(nodes: Collection<Node<*>>, process: (Node<*>) -> Unit) {
            for (node in nodes) {
                process(node)
                node.getChildren()?.let { traversePreOrder(it, process) }
            }
        }
    }
}

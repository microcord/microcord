package com.discord.utilities.textprocessing

import android.content.Context
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.parser.Parser
import com.discord.simpleast.core.simple.SimpleMarkdownRules
import com.discord.utilities.textprocessing.node.EditedMessageNode
import com.discord.utilities.textprocessing.node.ZeroSpaceWidthNode
import com.facebook.drawee.span.DraweeSpanStringBuilder

/**
 * Entry point for turning message text into styled, span-annotated text.
 *
 * The parsers are built once and reused. Rule order is the specification: escapes first so nothing
 * downstream can be tricked, then the block constructs, then links and entities, and finally the
 * generic markdown rules with the text rule last as the terminal fallback.
 */
object DiscordParser {

    /** Selects which parser a surface gets, and therefore which rules are enabled. */
    enum class ParserOptions {
        /** Chat messages: no masked links, entity mentions enabled. */
        DEFAULT,

        /** Surfaces that are allowed to show `[label](url)`, such as system and bot content. */
        ALLOW_MASKED_LINKS,

        /** Inline reply previews: single line, so block quotes are disabled. */
        REPLY,

        /** The opening post of a forum thread, which may use headers and lists. */
        FORUM_POST_FIRST_MESSAGE,
    }

    private val SAFE_LINK_PARSER = createParser(allowMaskedLinks = false, supportEntityMentions = true)
    private val MASKED_LINK_PARSER = createParser(allowMaskedLinks = true, supportEntityMentions = true)
    private val REPLY_PARSER = createParser(
        allowMaskedLinks = false,
        supportEntityMentions = true,
        renderBlockQuotes = false,
    )
    private val FORUM_POST_FIRST_MESSAGE_PARSER = createParser(
        allowMaskedLinks = false,
        supportEntityMentions = true,
        renderHeaders = true,
        renderLists = true,
    )

    @JvmStatic
    @JvmOverloads
    fun createParser(
        allowMaskedLinks: Boolean,
        supportEntityMentions: Boolean,
        renderBlockQuotes: Boolean = true,
        renderHeaders: Boolean = false,
        renderLists: Boolean = false,
    ): Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> {
        val parser = Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState>(false)

        // Soft hyphens and escapes are stripped before anything can be smuggled through them.
        parser.addRule(Rules.createSoftHyphenRule())
        parser.addRule(SimpleMarkdownRules.createEscapeRule())

        if (renderBlockQuotes) {
            parser.addRule(Rules.createBlockQuoteRule())
        }
        parser.addRule(Rules.createCodeBlockRule())
        parser.addRule(Rules.createInlineCodeRule())
        parser.addRule(Rules.createSpoilerRule())

        if (allowMaskedLinks) {
            parser.addRule(Rules.createMaskedLinkRule())
        }
        parser.addRule(Rules.createUrlNoEmbedRule())
        parser.addRule(Rules.createUrlRule())

        parser.addRule(Rules.createCustomEmojiRule())
        parser.addRule(Rules.createNamedEmojiRule())
        parser.addRule(Rules.createUnescapeEmoticonRule())

        if (supportEntityMentions) {
            parser.addRule(Rules.createChannelMentionRule())
            parser.addRule(Rules.createRoleMentionRule())
            parser.addRule(Rules.createUserMentionRule())
        }

        parser.addRule(Rules.createUnicodeEmojiRule())
        parser.addRule(Rules.createTimestampRule())

        if (renderHeaders) {
            parser.addRule(Rules.createHeaderItemRule())
        }
        if (renderLists) {
            parser.addRule(Rules.createListItemRule())
        }

        // Bold, underline, italics, strikethrough and newlines; escapes and the text rule are
        // excluded because this parser installs its own at the two ends of the chain.
        parser.addRules(SimpleMarkdownRules.createSimpleMarkdownRules(false, false))
        parser.addRule(Rules.createTextReplacementRule())
        return parser
    }

    /**
     * Parses [messageText] and renders it with [messageRenderContext].
     *
     * [preprocessor] carries the per-message state (revealed spoilers, embeds, jumbo eligibility)
     * and is mutated during the call, so callers can read [MessagePreprocessor.isLinkifyConflicting]
     * and [MessagePreprocessor.spoilers] afterwards.
     */
    @JvmStatic
    @JvmOverloads
    fun parseChannelMessage(
        context: Context,
        messageText: String?,
        messageRenderContext: MessageRenderContext,
        preprocessor: MessagePreprocessor,
        parserOptions: ParserOptions = ParserOptions.DEFAULT,
        isEdited: Boolean = false,
    ): DraweeSpanStringBuilder {
        val parser = when (parserOptions) {
            ParserOptions.DEFAULT -> SAFE_LINK_PARSER
            ParserOptions.ALLOW_MASKED_LINKS -> MASKED_LINK_PARSER
            ParserOptions.REPLY -> REPLY_PARSER
            ParserOptions.FORUM_POST_FIRST_MESSAGE -> FORUM_POST_FIRST_MESSAGE_PARSER
        }

        val ast = parse(parser, messageText)
        preprocessor.process(ast)
        if (isEdited) {
            ast.add(EditedMessageNode(context))
        }
        ast.add(ZeroSpaceWidthNode())
        return AstRenderer.render(ast, messageRenderContext)
    }

    /** Parses without rendering, for callers that only need the tree (tests, accessibility). */
    @JvmStatic
    @JvmOverloads
    fun parse(
        messageText: String?,
        parserOptions: ParserOptions = ParserOptions.DEFAULT,
    ): MutableList<Node<MessageRenderContext>> = parse(
        when (parserOptions) {
            ParserOptions.DEFAULT -> SAFE_LINK_PARSER
            ParserOptions.ALLOW_MASKED_LINKS -> MASKED_LINK_PARSER
            ParserOptions.REPLY -> REPLY_PARSER
            ParserOptions.FORUM_POST_FIRST_MESSAGE -> FORUM_POST_FIRST_MESSAGE_PARSER
        },
        messageText,
    )

    private fun parse(
        parser: Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState>,
        messageText: String?,
    ): MutableList<Node<MessageRenderContext>> =
        ArrayList(parser.parse(messageText.orEmpty(), MessageParseState.initialState))
}

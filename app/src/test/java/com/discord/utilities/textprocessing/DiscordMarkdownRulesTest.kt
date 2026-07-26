package com.discord.utilities.textprocessing

import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.simpleast.code.CodeNode
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.node.TextNode
import com.discord.simpleast.core.parser.Parser
import com.discord.utilities.textprocessing.node.BlockBackgroundNode
import com.discord.utilities.textprocessing.node.BlockQuoteNode
import com.discord.utilities.textprocessing.node.BulletListNode
import com.discord.utilities.textprocessing.node.ChannelMentionNode
import com.discord.utilities.textprocessing.node.EmojiNode
import com.discord.utilities.textprocessing.node.HeaderNode
import com.discord.utilities.textprocessing.node.RoleMentionNode
import com.discord.utilities.textprocessing.node.SpoilerNode
import com.discord.utilities.textprocessing.node.TimestampNode
import com.discord.utilities.textprocessing.node.UrlNode
import com.discord.utilities.textprocessing.node.UserMentionNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * Structural coverage for the Discord markdown rule set.
 *
 * Assertions are made on the parsed AST rather than on rendered spans so the suite stays a plain
 * JVM test; span construction needs a real Android runtime.
 */
class DiscordMarkdownRulesTest {

    // region Escapes and invisible characters

    @Test
    fun escapeRuleKeepsEscapedMarkersAsLiteralText() {
        assertEquals("*not bold*", plainText(parse("\\*not bold\\*")))
    }

    @Test
    fun softHyphenRuleDropsTheHiddenCharacter() {
        assertEquals("ab", plainText(parse("a\u00ADb")))
    }

    @Test
    fun unescapeEmoticonRuleKeepsTheShrugIntact() {
        assertEquals("¯\\_(ツ)_/¯", plainText(parse("¯\\_(ツ)_/¯")))
    }

    // endregion

    // region Blocks

    @Test
    fun spoilerRuleCapturesContentAndStaysHiddenUntilPreprocessed() {
        val spoiler = parse("||secret||").filterIsInstance<SpoilerNode<*>>().single()

        assertEquals("secret", spoiler.content)
        assertEquals("secret", plainText(spoiler.getChildren().orEmpty()))
        assertFalse(spoiler.isRevealed)
        assertEquals(SpoilerNode.UNASSIGNED_ID, spoiler.id)
    }

    @Test
    fun codeBlockRuleWrapsFencedCodeInABlockBackground() {
        val block = parse("```kotlin\nval answer = 42\n```")
            .filterIsInstance<BlockBackgroundNode<*>>()
            .single()

        assertNotNull(block.getChildren().orEmpty().filterIsInstance<CodeNode<*>>().singleOrNull())
        assertTrue(plainText(listOf(block)).contains("val answer = 42"))
    }

    @Test
    fun codeBlockRuleKeepsUnknownLanguagesAsPlainCode() {
        val block = parse("```\nno language here\n```")
            .filterIsInstance<BlockBackgroundNode<*>>()
            .single()

        assertTrue(plainText(listOf(block)).contains("no language here"))
    }

    @Test
    fun inlineCodeRuleKeepsTheCodeTextWithoutTheBackticks() {
        val nodes = parse("`inline`")

        assertTrue(nodes.none { it is BlockBackgroundNode<*> })
        assertEquals("inline", plainText(nodes))
    }

    @Test
    fun blockQuoteRuleCapturesTheQuotedLineAndRefusesToNest() {
        val quote = parse("> quoted").filterIsInstance<BlockQuoteNode<*>>().single()
        assertEquals("quoted", plainText(quote.getChildren().orEmpty()))

        val nested = parse("> outer > inner").filterIsInstance<BlockQuoteNode<*>>().single()
        assertEquals("outer > inner", plainText(nested.getChildren().orEmpty()))
    }

    @Test
    fun blockQuoteRuleIsDisabledForReplyPreviews() {
        val nodes = parse("> quoted", DiscordParser.ParserOptions.REPLY)

        assertTrue(nodes.none { it is BlockQuoteNode<*> })
        assertEquals("> quoted", plainText(nodes))
    }

    @Test
    fun headerRuleIsOnlyEnabledForForumPosts() {
        assertTrue(parse("## Title\n").none { it is HeaderNode<*> })

        val header = parse("## Title\n", DiscordParser.ParserOptions.FORUM_POST_FIRST_MESSAGE)
            .filterIsInstance<HeaderNode<*>>()
            .single()
        assertEquals("Title", plainText(header.getChildren().orEmpty()))
    }

    @Test
    fun listRuleIsOnlyEnabledForForumPosts() {
        assertTrue(parse("- item\n").none { it is BulletListNode<*> })

        val item = parse("- item\n", DiscordParser.ParserOptions.FORUM_POST_FIRST_MESSAGE)
            .filterIsInstance<BulletListNode<*>>()
            .single()
        assertEquals("item", plainText(item.getChildren().orEmpty()))
    }

    // endregion

    // region Links

    @Test
    fun urlRuleLinksBareLinksWithoutConsumingTrailingPunctuation() {
        val url = parse("see https://example.com/a, ok").filterIsInstance<UrlNode<*>>().single()

        assertEquals("https://example.com/a", url.url)
    }

    @Test
    fun urlNoEmbedRuleStripsTheAngleBrackets() {
        val url = parse("<https://example.com/a>").filterIsInstance<UrlNode<*>>().single()

        assertEquals("https://example.com/a", url.url)
    }

    @Test
    fun maskedLinkRuleOnlyAppliesToSurfacesThatAllowIt() {
        // Without masked links the brackets stay as text and only the bare URL is linked.
        assertTrue(plainText(parse("[label](https://example.com)")).contains("[label]("))

        val url = parse("[label](https://example.com)", DiscordParser.ParserOptions.ALLOW_MASKED_LINKS)
            .filterIsInstance<UrlNode<*>>()
            .single()
        assertEquals("https://example.com", url.url)
    }

    @Test
    fun maskedLinkRuleRefusesBracketFloodedInput() {
        val attack = "[".repeat(40) + "](https://example.com)"

        val rendered = plainText(parse(attack, DiscordParser.ParserOptions.ALLOW_MASKED_LINKS))
        assertTrue(rendered.startsWith("[[["))
    }

    // endregion

    // region Entities

    @Test
    fun userMentionRuleHandlesPlainNicknameAndBroadcastForms() {
        assertEquals(
            UserMentionNode.Type.USER to 123L,
            parse("<@123>").filterIsInstance<UserMentionNode<*>>().single().let { it.type to it.userId },
        )
        assertEquals(
            UserMentionNode.Type.USER to 123L,
            parse("<@!123>").filterIsInstance<UserMentionNode<*>>().single().let { it.type to it.userId },
        )
        assertEquals(
            UserMentionNode.Type.EVERYONE,
            parse("@everyone").filterIsInstance<UserMentionNode<*>>().single().type,
        )
        assertEquals(
            UserMentionNode.Type.HERE,
            parse("@here").filterIsInstance<UserMentionNode<*>>().single().type,
        )
    }

    @Test
    fun channelMentionRuleCapturesTheChannelId() {
        assertEquals(456L, parse("<#456>").filterIsInstance<ChannelMentionNode<*>>().single().channelId)
    }

    @Test
    fun roleMentionRuleCapturesTheRoleId() {
        assertEquals(789L, parse("<@&789>").filterIsInstance<RoleMentionNode<*>>().single().roleId)
    }

    @Test
    fun entityMentionsAreLiteralTextWhenTheSurfaceDisablesThem() {
        val parser = DiscordParser.createParser(allowMaskedLinks = false, supportEntityMentions = false)
        val nodes = parser.parse("<@123> <#456> <@&789>", MessageParseState.initialState)

        assertTrue(
            nodes.none { it is UserMentionNode<*> || it is ChannelMentionNode<*> || it is RoleMentionNode<*> },
        )
        assertEquals("<@123> <#456> <@&789>", plainText(nodes))
    }

    @Test
    fun textAroundEntitiesIsPreserved() {
        val nodes = parse("hi <@1> there")

        assertEquals(1, nodes.filterIsInstance<UserMentionNode<*>>().size)
        assertEquals("hi  there", plainText(nodes))
    }

    // endregion

    // region Emoji

    @Test
    fun customEmojiRuleAcceptsAnimatedAndStillForms() {
        assertEquals("party", parse("<a:party:42>").filterIsInstance<EmojiNode<*>>().single().content)
        assertEquals("wave", parse("<:wave:7>").filterIsInstance<EmojiNode<*>>().single().content)
    }

    @Test
    fun namedEmojiRuleResolvesKnownNamesAndLeavesUnknownOnesAsText() {
        withEmojiCatalog { parser ->
            val known = parser.parse(":wave:", MessageParseState.initialState)
            assertEquals("wave", known.filterIsInstance<EmojiNode<*>>().single().content)

            val unknown = parser.parse(":definitely_not_an_emoji:", MessageParseState.initialState)
            assertTrue(unknown.flatten().none { it is EmojiNode<*> })
            assertEquals(":definitely_not_an_emoji:", plainText(unknown))
        }
    }

    @Test
    fun unicodeEmojiSurrogatesBecomeEmojiNodes() {
        withEmojiCatalog { parser ->
            val nodes = parser.parse("hi 👋", MessageParseState.initialState)

            assertEquals("wave", nodes.flatten().filterIsInstance<EmojiNode<*>>().single().content)
        }
    }

    // endregion

    // region Timestamps

    @Test
    fun timestampRuleFormatsBothTheInlineAndAbsoluteForms() {
        val timestamp = parse("<t:1600000000:d>").filterIsInstance<TimestampNode<*>>().single()

        assertTrue(timestamp.full.isNotEmpty())
        assertTrue(timestamp.formatted.isNotEmpty())
        assertTrue(timestamp.formatted.length < timestamp.full.length)
    }

    @Test
    fun timestampRuleAcceptsTheSuffixLessFormAndRejectsUnknownStyles() {
        assertNotNull(parse("<t:1600000000>").filterIsInstance<TimestampNode<*>>().singleOrNull())
        assertTrue(parse("<t:1600000000:z>").none { it is TimestampNode<*> })
    }

    // endregion

    private fun parse(
        text: String,
        options: DiscordParser.ParserOptions = DiscordParser.ParserOptions.DEFAULT,
    ): List<Node<MessageRenderContext>> = DiscordParser.parse(text, options)

    private companion object {

        val NO_EMOJI_DATA = object : Rules.EmojiDataProvider {
            override val unicodeEmojisNamesMap = emptyMap<String, ModelEmojiUnicode>()
            override val unicodeEmojiSurrogateMap = emptyMap<String, ModelEmojiUnicode>()
            override val unicodeEmojisPattern: Pattern = Pattern.compile("(?!)")
        }

        /**
         * Installs a one-entry emoji catalog and hands back a parser built against it.
         *
         * A fresh parser is required because the unicode emoji rule captures the catalog pattern
         * at the moment the rule is created.
         */
        fun withEmojiCatalog(
            block: (Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState>) -> Unit,
        ) {
            val wave = ModelEmojiUnicode(names = listOf("wave"), surrogates = "👋")
            Rules.setEmojiDataProvider(
                object : Rules.EmojiDataProvider {
                    override val unicodeEmojisNamesMap = mapOf("wave" to wave)
                    override val unicodeEmojiSurrogateMap = mapOf(wave.surrogates to wave)
                    override val unicodeEmojisPattern: Pattern =
                        Pattern.compile(Pattern.quote(wave.surrogates))
                },
            )
            try {
                block(DiscordParser.createParser(allowMaskedLinks = false, supportEntityMentions = true))
            } finally {
                Rules.setEmojiDataProvider(NO_EMOJI_DATA)
            }
        }

        fun List<Node<*>>.flatten(): List<Node<*>> =
            flatMap { listOf(it) + it.getChildren().orEmpty().toList().flatten() }

        /** Text projection of an AST: everything a [TextNode] contributes when rendered. */
        fun plainText(nodes: Collection<Node<*>>): String = buildString {
            fun visit(node: Node<*>) {
                if (node is TextNode<*>) append(node.content)
                node.getChildren()?.forEach(::visit)
            }
            nodes.forEach(::visit)
        }
    }
}

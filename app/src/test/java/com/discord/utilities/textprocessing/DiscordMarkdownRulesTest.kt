package com.discord.utilities.textprocessing

import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.simpleast.code.CodeNode
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.node.TextNode
import com.discord.simpleast.core.parser.Parser
import com.discord.utilities.textprocessing.node.BlockBackgroundNode
import com.discord.utilities.textprocessing.node.BlockQuoteNode
import com.discord.utilities.textprocessing.node.ChannelMentionNode
import com.discord.utilities.textprocessing.node.CommandMentionNode
import com.discord.utilities.textprocessing.node.EmojiNode
import com.discord.utilities.textprocessing.node.HeaderNode
import com.discord.utilities.textprocessing.node.ListItemNode
import com.discord.utilities.textprocessing.node.ListNode
import com.discord.utilities.textprocessing.node.RoleMentionNode
import com.discord.utilities.textprocessing.node.SpoilerNode
import com.discord.utilities.textprocessing.node.SubtextNode
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
    fun headerRuleIsEnabledForCurrentChatMessages() {
        val header = parse("## Title\n")
            .filterIsInstance<HeaderNode<*>>()
            .single()
        assertEquals("Title", plainText(header.getChildren().orEmpty()))
        assertTrue(parse("#### not-a-heading").none { it is HeaderNode<*> })
    }

    /** A message predating the feature wrote `#` and `-` as punctuation and has to keep them. */
    @Test
    fun legacyMessagesGetNoHeadersListsOrMaskedLinks() {
        val legacy = DiscordParser.ParserOptions.LEGACY_MESSAGE
        assertTrue(parse("# Title\n", legacy).none { it is HeaderNode<*> })
        assertTrue(parse("- item\n", legacy).none { it is ListNode<*> })

        // The bare URL is still a link; what legacy loses is the label standing in for it, so the
        // brackets stay as literal text instead of being absorbed into the link.
        val source = "[label](https://example.com)"
        assertEquals("[label]()", plainText(parse(source, legacy)))
        assertEquals("", plainText(parse(source, DiscordParser.ParserOptions.DEFAULT)))
    }

    /**
     * SimpleAST keeps one "last capture" for the whole traversal, so a block rule that hands back a
     * range has its trailing newline overwritten by its own children and the next header on the
     * following line stops matching.
     */
    @Test
    fun consecutiveHeadersAllParse() {
        val headers = parse("# One\n## Two\n### Three\n").filterIsInstance<HeaderNode<*>>()
        assertEquals(
            listOf("One", "Two", "Three"),
            headers.map { plainText(it.getChildren().orEmpty()) },
        )
    }

    @Test
    fun aBlockAfterAListStillParses() {
        assertEquals(1, parse("- item\n# After\n").filterIsInstance<HeaderNode<*>>().size)
    }

    @Test
    fun listRuleGroupsSiblingItemsUnderOneList() {
        val items = parse("- one\n- two\n").listItems()
        assertEquals(listOf("one", "two"), items.map { plainText(it.getChildren().orEmpty()) })
        assertEquals(listOf(0, 0), items.map { it.level })
    }

    /** The reference numbers from the first bullet onwards rather than echoing each marker. */
    @Test
    fun orderedListRenumbersFromItsFirstMarker() {
        val items = parse("5. five\n5. six\n").listItems()
        assertEquals(listOf("5. five", "6. six"), items.map(::renderedItem))
    }

    @Test
    fun indentedItemsBecomeADeeperListNextToTheItemAboveThem() {
        val items = parse("- outer\n  - inner\n").listItems()
        assertEquals(listOf("outer", "inner"), items.map { plainText(it.getChildren().orEmpty()) })
        assertEquals(listOf(0, 1), items.map { it.level })
    }

    /**
     * An item strips only its own bullet's worth of indentation from the lines below it, so a
     * sub-list indented further keeps the leftover spaces in front of its bullet.
     */
    @Test
    fun anyIndentDeepEnoughToNestNests() {
        listOf(2, 3, 4, 8).forEach { indent ->
            val items = parse("- outer\n${" ".repeat(indent)}- inner\n").listItems()
            assertEquals("indent of $indent", listOf(0, 1), items.map { it.level })
        }
        // One space still reads as the same level, as it does on desktop.
        assertEquals(listOf(0, 0), parse("- outer\n - inner\n").listItems().map { it.level })
    }

    /** Headers inside an item would change its line height, so the reference turns them off. */
    @Test
    fun headersAreInertInsideAListItem() {
        val items = parse("- # not a header\n").listItems()
        assertEquals("# not a header", plainText(items.single().getChildren().orEmpty()))
    }

    @Test
    fun subtextIsAlwaysEnabledAndRequiresTheExactLinePrefix() {
        val subtext = parse("-# muted\n").filterIsInstance<SubtextNode<*>>().single()
        assertEquals("muted", plainText(subtext.getChildren().orEmpty()))
        assertTrue(parse("text -# muted").none { it is SubtextNode<*> })
    }

    // endregion

    // region Links

    @Test
    fun urlRuleLinksBareLinksWithoutConsumingTrailingPunctuation() {
        val url = parse("see https://example.com/a, ok").filterIsInstance<UrlNode<*>>().single()

        assertEquals("https://example.com/a", url.url)
        assertEquals(
            "https://example.com/a(b)",
            parse("https://example.com/a(b))").filterIsInstance<UrlNode<*>>().single().url,
        )
        assertEquals(
            "https://example.com/ü",
            parse("https://example.com/ü)").filterIsInstance<UrlNode<*>>().single().url,
        )
    }

    @Test
    fun urlNoEmbedRuleStripsTheAngleBrackets() {
        val url = parse("<https://example.com/a>").filterIsInstance<UrlNode<*>>().single()

        assertEquals("https://example.com/a", url.url)
    }

    @Test
    fun currentAngleAutolinksCoverMailAndTelephoneSchemes() {
        val fixtures = listOf(
            "<person@example.com>" to "mailto:person@example.com",
            "<+1 555 0100>" to "tel:+1-555-0100",
            "steam://join/123" to "steam://join/123",
        )
        fixtures.forEach { (source, target) ->
            assertEquals(source, target, parse(source).filterIsInstance<UrlNode<*>>().single().url)
        }
    }

    @Test
    fun maskedLinkRuleAppliesToCurrentChatMessages() {
        val url = parse("[label](https://example.com)")
            .filterIsInstance<UrlNode<*>>()
            .single()
        assertEquals("https://example.com", url.url)
        assertEquals(
            "https://example.com/a_(b)",
            parse("[label](https://example.com/a_(b))").filterIsInstance<UrlNode<*>>().single().url,
        )
        val unsafe = "[unsafe](javascript:alert(1))"
        assertTrue(parse(unsafe).none { it is UrlNode<*> })
        assertEquals(unsafe, plainText(parse(unsafe)))
    }

    @Test
    fun maskedLinkRuleRefusesBracketFloodedInput() {
        val attack = "[".repeat(40) + "](https://example.com)"

        val rendered = plainText(parse(attack, DiscordParser.ParserOptions.DEFAULT))
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

    @Test
    fun commandMentionsUseUnicodeNamesAndPreserveMalformedForms() {
        val command = parse("</hava durumu:123>").filterIsInstance<CommandMentionNode<*>>().single()
        assertEquals("hava durumu", command.commandName)
        assertEquals(123L, command.commandId)

        assertTrue(parse("</four word command now:123>").none { it is CommandMentionNode<*> })
        assertEquals("</missing-id>", plainText(parse("</missing-id>")))
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

    @Test
    fun desktopPrecedenceAndMalformedFixturesStayLiteralWhereRequired() {
        val fixtures = listOf(
            "`**raw**`" to "**raw**",
            "\\*escaped*" to "*escaped*",
            "**unclosed" to "**unclosed",
            "||unclosed" to "||unclosed",
            "```js\nunclosed" to "```js\nunclosed",
            "#no-space" to "#no-space",
        )
        fixtures.forEach { (source, expected) -> assertEquals(source, expected, plainText(parse(source))) }
        assertTrue(parse("||**bold**||").single() is SpoilerNode<*>)
    }

    @Test(timeout = 2_000)
    fun bracketFloodAtUserdoccersMessageLimitIsBounded() {
        val source = "[".repeat(1_978) + "](https://example.com)"
        val nodes = parse(source)
        assertEquals("https://example.com", nodes.filterIsInstance<UrlNode<*>>().single().url)
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

        /** Every item of every list, outer first, in the order they render. */
        fun List<Node<*>>.listItems(): List<ListItemNode<*>> =
            filterIsInstance<ListNode<*>>().flatMap { list ->
                list.getChildren().orEmpty().flatMap { child ->
                    when (child) {
                        is ListItemNode<*> -> listOf(child)
                        else -> listOf(child).listItems()
                    }
                }
            }

        fun renderedItem(item: ListItemNode<*>): String =
            item.marker.orEmpty() + plainText(item.getChildren().orEmpty())

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

package com.discord.utilities.textprocessing

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TextAppearanceSpan
import androidx.annotation.ColorInt
import com.discord.R
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.simpleast.code.CodeNode
import com.discord.simpleast.code.CodeRules
import com.discord.simpleast.code.CodeStyleProviders
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.node.StyleNode
import com.discord.simpleast.core.node.TextNode
import com.discord.simpleast.core.parser.ParseSpec
import com.discord.simpleast.core.parser.Parser
import com.discord.simpleast.core.parser.Rule
import com.discord.simpleast.core.simple.SimpleMarkdownRules
import com.discord.simpleast.markdown.MarkdownRules
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.textprocessing.node.BasicRenderContext
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
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * The Discord flavoured markdown rule set layered on top of SimpleAST's generic markdown rules.
 *
 * Every rule is anchored with `^` because SimpleAST feeds each rule the remaining source and takes
 * the first rule that matches at the current position; rule *order* (see [DiscordParser]) is what
 * gives, for example, code blocks precedence over emphasis.
 */
object Rules {

    // region Patterns

    /** Unanchored form, shared with the composer so it can find custom emoji anywhere in a draft. */
    const val REGEX_CUSTOM_EMOJI = "<(a)?:([a-zA-Z_0-9]+):(\\d+)>"

    private const val REGEX_URL = "(https?://[^\\s<]+[^<.,:;\"')\\]\\s])"
    private const val REGEX_LINK_INSIDE = "(?:\\[[^]]*]|[^]]|](?=[^\\[]*]))*"
    private const val REGEX_LINK_HREF_AND_TITLE =
        "\\s*<?((?:[^\\s\\\\]|\\\\.)*?)>?(?:\\s+['\"]([\\s\\S]*?)['\"])?\\s*"
    private const val LINK = "^\\[($REGEX_LINK_INSIDE)]\\($REGEX_LINK_HREF_AND_TITLE\\)"
    private const val HOOKED_LINK = "^\\$\\[($REGEX_LINK_INSIDE)?]\\($REGEX_LINK_HREF_AND_TITLE\\)"

    private val PATTERN_BLOCK_QUOTE: Pattern =
        Pattern.compile("^(?: *>>> +(.*)| *>(?!>>) +([^\\n]*\\n?))", Pattern.DOTALL)
    private val PATTERN_CHANNEL_MENTION: Pattern = Pattern.compile("^<#(\\d+)>")
    private val PATTERN_ROLE_MENTION: Pattern = Pattern.compile("^<@&(\\d+)>")
    private val PATTERN_MENTION: Pattern = Pattern.compile("^<@!?(\\d+)>|^@(everyone|here)")
    private val PATTERN_CUSTOM_EMOJI: Pattern = Pattern.compile("^$REGEX_CUSTOM_EMOJI")
    private val PATTERN_NAMED_EMOJI: Pattern = Pattern.compile("^:([^\\s:]+?(?:::skin-tone-\\d)?):")
    private val PATTERN_UNESCAPE_EMOTICON: Pattern = Pattern.compile("^(¯\\\\_\\(ツ\\)_/¯)")
    private val PATTERN_TIMESTAMP: Pattern = Pattern.compile("^<t:(-?\\d{1,17})(?::(t|T|d|D|f|F|R))?>")
    private val PATTERN_URL: Pattern = Pattern.compile("^$REGEX_URL")
    private val PATTERN_URL_NO_EMBED: Pattern = Pattern.compile("^<$REGEX_URL>")
    private val PATTERN_MASKED_LINK: Pattern = Pattern.compile(LINK)
    private val PATTERN_HOOKED_LINK: Pattern = Pattern.compile(HOOKED_LINK)
    private val PATTERN_SOFT_HYPHEN: Pattern = Pattern.compile("^\\u00AD")
    private val PATTERN_SPOILER: Pattern = Pattern.compile("^\\|\\|([\\s\\S]+?)\\|\\|")
    private val PATTERN_LIST_ITEM: Pattern = Pattern.compile("^([^\\S\\r\\n]*)[*-][ \\s]?(.*)([\\n|\$])?")
    private val PATTERN_HEADER_ITEM: Pattern = MarkdownRules.PATTERN_HEADER_ITEM

    /** A masked link this dense in brackets is an attempt to make the link parser backtrack. */
    private val PATHOLOGICAL_MASKED_LINK_ATTACK_SUSPICIOUS_CHARS = setOf('[', ']')
    private const val PATHOLOGICAL_MASKED_LINK_MIN_LENGTH = 30
    private const val PATHOLOGICAL_MASKED_LINK_SUSPICIOUS_RATIO = 0.3

    // endregion

    /**
     * Threaded through the parser so a block quote rule can refuse to open a nested quote and a
     * code block can know it is being rendered inside one.
     */
    interface BlockQuoteState<Self : BlockQuoteState<Self>> {
        val isInQuote: Boolean
        fun newBlockQuoteState(isInQuote: Boolean): Self
    }

    /**
     * Supplies the unicode emoji catalog. Injected by the emoji store during application start so
     * this object does not have to depend on the store graph.
     */
    interface EmojiDataProvider {
        val unicodeEmojisNamesMap: Map<String, ModelEmojiUnicode>
        val unicodeEmojiSurrogateMap: Map<String, ModelEmojiUnicode>
        val unicodeEmojisPattern: Pattern
    }

    /** Matches nothing, so emoji rules are inert until a real catalog is installed. */
    private object NoEmojiData : EmojiDataProvider {
        override val unicodeEmojisNamesMap: Map<String, ModelEmojiUnicode> = emptyMap()
        override val unicodeEmojiSurrogateMap: Map<String, ModelEmojiUnicode> = emptyMap()
        override val unicodeEmojisPattern: Pattern = Pattern.compile("(?!)")
    }

    private var emojiDataProvider: EmojiDataProvider = NoEmojiData
    private var unicodeEmojiPattern: Pattern = anchoredUnicodeEmojiPattern(NoEmojiData)

    /**
     * Installs the unicode emoji catalog.
     *
     * Must be called before the first message is parsed: [DiscordParser] builds its parsers once
     * and the emoji rules capture the catalog pattern at that point, matching the reference.
     */
    @JvmStatic
    fun setEmojiDataProvider(emojiDataProvider: EmojiDataProvider) {
        this.emojiDataProvider = emojiDataProvider
        this.unicodeEmojiPattern = anchoredUnicodeEmojiPattern(emojiDataProvider)
    }

    /** Looks a unicode emoji up by its canonical name, for callers that only kept the name. */
    internal fun unicodeEmojiByName(name: String): ModelEmojiUnicode? =
        emojiDataProvider.unicodeEmojisNamesMap[name]

    private fun anchoredUnicodeEmojiPattern(provider: EmojiDataProvider): Pattern =
        Pattern.compile("^(${provider.unicodeEmojisPattern.pattern()})")

    /** Rewrites raw emoji surrogates to their `:name:` form so one emoji rule can handle both. */
    private fun replaceEmojiSurrogates(originalText: String): String {
        val result = StringBuffer()
        val matcher = emojiDataProvider.unicodeEmojisPattern.matcher(originalText)
        while (matcher.find()) {
            val emoji = emojiDataProvider.unicodeEmojiSurrogateMap[matcher.group()] ?: continue
            matcher.appendReplacement(result, ":${emoji.firstName}:")
        }
        matcher.appendTail(result)
        return result.toString()
    }

    private fun String?.toLongOrDefault(default: Long = -1L): Long = this?.toLongOrNull() ?: default

    // region Emphasis

    /** `**bold**` in a fixed colour, used by the changelog and other non-chat surfaces. */
    fun <T, S> createBoldColoredRule(@ColorInt color: Int): Rule<T, Node<T>, S> =
        SimpleMarkdownRules.createSimpleStyleRule(SimpleMarkdownRules.PATTERN_BOLD) {
            listOf<CharacterStyle>(ForegroundColorSpan(color), StyleSpan(Typeface.BOLD))
        }

    /** `~~struck~~` in a fixed colour. */
    fun <T, S> createStrikethroughColoredRule(@ColorInt color: Int): Rule<T, Node<T>, S> =
        SimpleMarkdownRules.createSimpleStyleRule(SimpleMarkdownRules.PATTERN_STRIKETHRU) {
            listOf<CharacterStyle>(ForegroundColorSpan(color), StrikethroughSpan())
        }

    // endregion

    // region Blocks

    /** `> quote` and `>>> block quote`; refuses to nest. */
    fun <T : BasicRenderContext, S : BlockQuoteState<S>> createBlockQuoteRule(): Rule.BlockRule<T, BlockQuoteNode<T>, S> =
        object : Rule.BlockRule<T, BlockQuoteNode<T>, S>(PATTERN_BLOCK_QUOTE) {
            override fun match(inspectionSource: CharSequence, lastCapture: String?, state: S): Matcher? =
                if (state.isInQuote) null else super.match(inspectionSource, lastCapture, state)

            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in BlockQuoteNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> {
                // Group 1 is the `>>>` body, group 2 the single line `>` body.
                val contentGroup = if (matcher.group(1) != null) 1 else 2
                return ParseSpec(
                    BlockQuoteNode(),
                    state.newBlockQuoteState(true),
                    matcher.start(contentGroup),
                    matcher.end(contentGroup),
                )
            }
        }

    /** ```` ```lang ... ``` ```` fenced code with per-language syntax highlighting. */
    fun <RC : BasicRenderContext, S : BlockQuoteState<S>> createCodeBlockRule(): Rule<RC, Node<RC>, S> {
        val codeStyleProviders = createCodeStyleProviders<RC>()
        return CodeRules.createCodeRule(
            codeStyleProviders.defaultStyleProvider,
            CodeRules.createCodeLanguageMap(codeStyleProviders),
        ) { codeNode: CodeNode<RC>, isBlock: Boolean, state: S ->
            if (isBlock) {
                BlockBackgroundNode(state.isInQuote, codeNode)
            } else {
                object : Node.Parent<RC>(codeNode) {
                    override fun render(builder: SpannableStringBuilder, renderContext: RC) {
                        val start = builder.length
                        super.render(builder, renderContext)
                        builder.setSpan(
                            BackgroundColorSpan(ColorCompat.getThemedColor(renderContext.context, R.attr.theme_chat_code)),
                            start,
                            builder.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                }
            }
        }
    }

    /** `` `inline code` `` */
    fun <RC : BasicRenderContext, S : BlockQuoteState<S>> createInlineCodeRule(): Rule<RC, Node<RC>, S> =
        CodeRules.createInlineCodeRule(
            spanProvider { context -> listOf(codeTextAppearance(context), RelativeSizeSpan(CODE_TEXT_SCALE)) },
            spanProvider { renderContext ->
                listOf(BackgroundColorSpan(ColorCompat.getThemedColor(renderContext.context, R.attr.theme_chat_code)))
            },
        )

    /** `# heading` through `### heading`. */
    fun <T : BasicRenderContext, S> createHeaderItemRule(): Rule.BlockRule<T, HeaderNode<T>, S> =
        object : Rule.BlockRule<T, HeaderNode<T>, S>(PATTERN_HEADER_ITEM) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in HeaderNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> = ParseSpec(
                HeaderNode(matcher.group(1)?.length ?: 1),
                state,
                matcher.start(2),
                matcher.end(2),
            )
        }

    /** `- item` / `* item`, with one level of indentation supported. */
    fun <T : BasicRenderContext, S> createListItemRule(): Rule<T, BulletListNode<T>, S> =
        object : Rule<T, BulletListNode<T>, S>(PATTERN_LIST_ITEM) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in BulletListNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> {
                val nestedLevel = if (matcher.group(1).isNullOrEmpty()) 1 else 2
                // Group 3 only captures when the item is terminated by a newline of its own.
                val includesNewline = !matcher.group(3).isNullOrEmpty()
                return ParseSpec(
                    BulletListNode(nestedLevel, includesNewline),
                    state,
                    matcher.start(2),
                    matcher.end(2),
                )
            }
        }

    // endregion

    // region Mentions

    /** `<#id>` */
    fun <T : ChannelMentionNode.RenderContext, S> createChannelMentionRule(): Rule<T, ChannelMentionNode<T>, S> =
        object : Rule<T, ChannelMentionNode<T>, S>(PATTERN_CHANNEL_MENTION) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in ChannelMentionNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> = ParseSpec(ChannelMentionNode(matcher.group(1).toLongOrDefault()), state)
        }

    /** `<@&id>` */
    fun <T : RoleMentionNode.RenderContext, S> createRoleMentionRule(): Rule<T, RoleMentionNode<T>, S> =
        object : Rule<T, RoleMentionNode<T>, S>(PATTERN_ROLE_MENTION) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in RoleMentionNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> = ParseSpec(RoleMentionNode(matcher.group(1).toLongOrDefault()), state)
        }

    /** `<@id>`, `<@!id>`, `@everyone` and `@here`. */
    fun <T : UserMentionNode.RenderContext, S> createUserMentionRule(): Rule<T, Node<T>, S> =
        object : Rule<T, Node<T>, S>(PATTERN_MENTION) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in Node<T>, S>,
                state: S,
            ): ParseSpec<T, S> {
                val userId = matcher.group(1)
                if (!userId.isNullOrEmpty()) {
                    return ParseSpec(UserMentionNode(UserMentionNode.Type.USER, userId.toLongOrDefault()), state)
                }
                val broadcast = matcher.group(2)
                val type = if (broadcast?.firstOrNull() == 'e') {
                    UserMentionNode.Type.EVERYONE
                } else {
                    UserMentionNode.Type.HERE
                }
                return ParseSpec(UserMentionNode(type), state)
            }
        }

    // endregion

    // region Emoji

    /** `<:name:id>` and `<a:name:id>`. */
    fun <T : EmojiNode.RenderContext, S> createCustomEmojiRule(): Rule<T, EmojiNode<T>, S> =
        object : Rule<T, EmojiNode<T>, S>(PATTERN_CUSTOM_EMOJI) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in EmojiNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> {
                val isAnimated = !matcher.group(1).isNullOrEmpty()
                val name = matcher.group(2).orEmpty()
                val emojiId = matcher.group(3).toLongOrDefault()
                return ParseSpec(EmojiNode.from(name, emojiId, isAnimated), state)
            }
        }

    /** `:name:`; unknown names stay as literal text. */
    fun <T : EmojiNode.RenderContext, S> createNamedEmojiRule(): Rule<T, Node<T>, S> =
        object : Rule<T, Node<T>, S>(PATTERN_NAMED_EMOJI) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in Node<T>, S>,
                state: S,
            ): ParseSpec<T, S> {
                val name = matcher.group(1).orEmpty()
                val unicodeEmoji = emojiDataProvider.unicodeEmojisNamesMap[name]
                    ?: return ParseSpec(TextNode(matcher.group()), state)
                return ParseSpec(EmojiNode.from(unicodeEmoji), state)
            }
        }

    /** Raw emoji surrogates; unknown surrogates stay as literal text. */
    fun <T : EmojiNode.RenderContext, S> createUnicodeEmojiRule(): Rule<T, Node<T>, S> =
        object : Rule<T, Node<T>, S>(unicodeEmojiPattern) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in Node<T>, S>,
                state: S,
            ): ParseSpec<T, S> {
                val match = matcher.group()
                val unicodeEmoji = emojiDataProvider.unicodeEmojiSurrogateMap[match]
                    ?: return ParseSpec(TextNode(match), state)
                return ParseSpec(EmojiNode.from(unicodeEmoji), state)
            }
        }

    /** Keeps the shrug emoticon intact instead of letting the escape rule eat its backslash. */
    fun <T, S> createUnescapeEmoticonRule(): Rule<T, TextNode<T>, S> =
        object : Rule<T, TextNode<T>, S>(PATTERN_UNESCAPE_EMOTICON) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in TextNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> = ParseSpec(TextNode(matcher.group(1).orEmpty()), state)
        }

    // endregion

    // region Links, timestamps and text

    /** `<t:seconds[:style]>` */
    fun <T : TimestampNode.RenderContext, S> createTimestampRule(): Rule<T, TimestampNode<T>, S> =
        object : Rule<T, TimestampNode<T>, S>(PATTERN_TIMESTAMP) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in TimestampNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> = ParseSpec(TimestampNode(matcher.group(1).orEmpty(), matcher.group(2)), state)
        }

    /** A bare `https://` link. */
    fun <T : UrlNode.RenderContext, S> createUrlRule(): Rule<T, UrlNode<T>, S> =
        object : Rule<T, UrlNode<T>, S>(PATTERN_URL) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in UrlNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> = ParseSpec(UrlNode(matcher.group(1).orEmpty()), state)
        }

    /** `<https://...>`, which suppresses the embed but is still a link. */
    fun <T : UrlNode.RenderContext, S> createUrlNoEmbedRule(): Rule<T, UrlNode<T>, S> =
        object : Rule<T, UrlNode<T>, S>(PATTERN_URL_NO_EMBED) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in UrlNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> = ParseSpec(UrlNode(matcher.group(1).orEmpty()), state)
        }

    /** `[label](https://...)`, only enabled for surfaces that allow masked links. */
    fun <T : UrlNode.RenderContext, S> createMaskedLinkRule(): Rule<T, UrlNode<T>, S> =
        object : Rule<T, UrlNode<T>, S>(PATTERN_MASKED_LINK) {
            override fun match(inspectionSource: CharSequence, lastCapture: String?, state: S): Matcher? =
                if (isLikelyPathologicalAttack(inspectionSource)) {
                    null
                } else {
                    super.match(inspectionSource, lastCapture, state)
                }

            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in UrlNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> {
                val mask = matcher.group(1).orEmpty()
                val url = matcher.group(2).orEmpty()
                return ParseSpec(UrlNode(url, mask), state)
            }
        }

    /**
     * `$[label](target)`; the label is rendered unstyled and the target is dropped because the
     * hook is resolved by the surface that owns the text, not by the markdown renderer.
     */
    fun <T, S> createHookedLinkRule(): Rule<T, Node<T>, S> =
        object : Rule<T, Node<T>, S>(PATTERN_HOOKED_LINK) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in Node<T>, S>,
                state: S,
            ): ParseSpec<T, S> = ParseSpec(
                StyleNode<T, Any>(emptyList()),
                state,
                matcher.start(1),
                matcher.end(1),
            )
        }

    /** `||spoiler||` */
    fun <T : SpoilerNode.RenderContext, S> createSpoilerRule(): Rule<T, SpoilerNode<T>, S> =
        object : Rule<T, SpoilerNode<T>, S>(PATTERN_SPOILER) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in SpoilerNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> = ParseSpec(
                SpoilerNode(matcher.group(1).orEmpty()),
                state,
                matcher.start(1),
                matcher.end(1),
            )
        }

    /** Drops soft hyphens so they cannot be used to smuggle invisible characters into text. */
    fun <T, S> createSoftHyphenRule(): Rule<T, TextNode<T>, S> =
        object : Rule<T, TextNode<T>, S>(PATTERN_SOFT_HYPHEN) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in TextNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> = ParseSpec(TextNode(""), state)
        }

    /**
     * Terminal text rule.
     *
     * Before emitting plain text it rewrites unicode emoji surrogates to `:name:` and re-parses the
     * result, so a surrogate in the middle of a word still becomes an emoji image.
     */
    fun <T : EmojiNode.RenderContext, S> createTextReplacementRule(): Rule<T, Node<T>, S> =
        object : Rule<T, Node<T>, S>(SimpleMarkdownRules.PATTERN_TEXT) {
            private val innerRules: List<Rule<T, Node<T>, S>> = listOf(
                createNamedEmojiRule(),
                SimpleMarkdownRules.createTextRule(),
            )

            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in Node<T>, S>,
                state: S,
            ): ParseSpec<T, S> {
                val replaced = replaceEmojiSurrogates(matcher.group())
                @Suppress("UNCHECKED_CAST")
                val innerParser = parser as Parser<T, Node<T>, S>
                val parsed = innerParser.parse(replaced, state, innerRules)
                val node = if (parsed.size == 1) {
                    parsed.first()
                } else {
                    StyleNode<T, Any>(emptyList()).apply { parsed.forEach { addChild(it) } }
                }
                return ParseSpec(node, state)
            }
        }

    // endregion

    private fun isLikelyPathologicalAttack(source: CharSequence): Boolean {
        if (source.length < PATHOLOGICAL_MASKED_LINK_MIN_LENGTH) return false
        val threshold = source.length * PATHOLOGICAL_MASKED_LINK_SUSPICIOUS_RATIO
        var suspicious = 0
        for (character in source) {
            if (character in PATHOLOGICAL_MASKED_LINK_ATTACK_SUSPICIOUS_CHARS) {
                suspicious++
                if (suspicious > threshold) return true
            }
        }
        return false
    }

    private const val CODE_TEXT_SCALE = 0.85f

    private fun codeTextAppearance(context: BasicRenderContext) =
        TextAppearanceSpan(context.context, R.style.Code_TextAppearance)

    private fun <RC : BasicRenderContext> spanProvider(
        styles: (RC) -> Iterable<Any>,
    ): StyleNode.SpanProvider<RC> = object : StyleNode.SpanProvider<RC> {
        override fun get(renderContext: RC): Iterable<Any> = styles(renderContext)
    }

    private fun <RC : BasicRenderContext> textAppearanceProvider(styleRes: Int): StyleNode.SpanProvider<RC> =
        spanProvider { context -> listOf(TextAppearanceSpan(context.context, styleRes)) }

    /**
     * Maps SimpleAST's syntax-highlight roles onto the recovered `Code.TextAppearance.*` styles.
     * Addition/deletion (diff) roles keep the library defaults, as in the reference.
     */
    private fun <RC : BasicRenderContext> createCodeStyleProviders(): CodeStyleProviders<RC> =
        CodeStyleProviders(
            spanProvider { context -> listOf(codeTextAppearance(context), RelativeSizeSpan(CODE_TEXT_SCALE)) },
            textAppearanceProvider(R.style.Code_TextAppearance_Comment),
            textAppearanceProvider(R.style.Code_TextAppearance_Literal),
            textAppearanceProvider(R.style.Code_TextAppearance_Keyword),
            textAppearanceProvider(R.style.Code_TextAppearance_Identifier),
            textAppearanceProvider(R.style.Code_TextAppearance_Types),
            textAppearanceProvider(R.style.Code_TextAppearance_Generics),
            textAppearanceProvider(R.style.Code_TextAppearance_Params),
        )
}

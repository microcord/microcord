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
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.textprocessing.node.AttachmentLinkNode
import com.discord.utilities.textprocessing.node.BasicRenderContext
import com.discord.utilities.textprocessing.node.BlockBackgroundNode
import com.discord.utilities.textprocessing.node.BlockQuoteNode
import com.discord.utilities.textprocessing.node.ChannelLinkNode
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
import com.discord.utilities.textprocessing.node.UserLinkNode
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

    private const val REGEX_URL = "((?:https?|steam)://[^\\s<]+[^<.,:;\"'\\]\\s])"
    private const val REGEX_CHANNEL_LINK =
        "((?:https?://)?(?:canary\\.|ptb\\.)?(?:discord|discordapp)\\.com/channels/(@me|\\d+)/(\\d+)(?:/(\\d+))?)"
    private const val REGEX_USER_LINK =
        "((?:https?://)?(?:canary\\.|ptb\\.)?(?:discord|discordapp)\\.com/users/(\\d+))"
    private const val REGEX_ATTACHMENT_LINK =
        "(https://(?:(?:media|images)\\.discordapp\\.net|cdn\\.discordapp\\.com)/(?:attachments|ephemeral-attachments)/\\d+/\\d+/([A-Za-z0-9._-]*[A-Za-z0-9_-])(?:[?][a-zA-Z0-9?&=_-]*)?)"
    private const val REGEX_LINK_INSIDE = "(?:\\[[^]]*]|[^]]|](?=[^\\[]*]))*"
    private const val REGEX_LINK_HREF_AND_TITLE =
        "\\s*<?((?:\\([^)]*\\)|[^\\s\\\\()]|\\\\.)*?)>?(?:\\s+['\"]([\\s\\S]*?)['\"])?\\s*"
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
    private val PATTERN_CHANNEL_LINK: Pattern = Pattern.compile("^$REGEX_CHANNEL_LINK")
    private val PATTERN_USER_LINK: Pattern = Pattern.compile("^$REGEX_USER_LINK")
    private val PATTERN_ATTACHMENT_LINK: Pattern = Pattern.compile("^$REGEX_ATTACHMENT_LINK")
    private val PATTERN_URL_NO_EMBED: Pattern = Pattern.compile("^<$REGEX_URL>")
    private val PATTERN_MAILTO: Pattern = Pattern.compile("^<([^\\s<>@]+@[^\\s<>@]+\\.[^\\s<>@]+)>")
    private val PATTERN_TEL: Pattern = Pattern.compile(
        "^<((?:(?:tel|sms):\\+?|\\+)(?:(?:[0-9]|\\([0-9]+\\)))(?:[- ./]?(?:[0-9]|\\([0-9]+\\)))+)>",
    )
    private val PATTERN_MASKED_LINK: Pattern = Pattern.compile(LINK)

    /** A custom emoji's CDN image, as client mods post it in place of an emoji the sender cannot use. */
    private const val REGEX_EMOJI_URL =
        "https://(?:cdn\\.discordapp\\.com|media\\.discordapp\\.net)/emojis/\\d+\\.(?:webp|png|gif|jpe?g|avif)(?:[?][^\\s()<>]*)?"
    private val PATTERN_EMOJI_URL: Pattern = Pattern.compile(REGEX_EMOJI_URL)
    private val PATTERN_EMOJI_LINK: Pattern = Pattern.compile("^<?($REGEX_EMOJI_URL)>?")
    private val PATTERN_MASKED_EMOJI_LINK: Pattern =
        Pattern.compile("^\\[([^\\]\\n]*)\\]\\(<?($REGEX_EMOJI_URL)>?\\)")
    private val PATTERN_HOOKED_LINK: Pattern = Pattern.compile(HOOKED_LINK)
    private val PATTERN_SOFT_HYPHEN: Pattern = Pattern.compile("^\\u00AD")
    private val PATTERN_SPOILER: Pattern = Pattern.compile("^\\|\\|([\\s\\S]+?)\\|\\|")
    /** `-`, `*` and `1.`; `+` is deliberately absent, as in the reference. */
    private const val LIST_BULLET = "(?:[*-]|\\d+\\.)"

    /**
     * A whole list, from its first bullet to the line that stops being part of it: a line that is
     * neither indented nor another bullet at the same indent.
     */
    private val PATTERN_LIST: Pattern =
        Pattern.compile("^( *)($LIST_BULLET) [\\s\\S]+?(?:\\n(?! )(?!\\1$LIST_BULLET )|\$)")

    /** The bullet and the space after it, which is stripped before an item's content is parsed. */
    private val PATTERN_LIST_ITEM_PREFIX: Pattern = Pattern.compile("^( *)($LIST_BULLET) +")

    /**
     * One item and its continuation lines, with `%INDENT%` replaced by the leading-space run that
     * makes a bullet a sibling of the first one.
     */
    private const val LIST_ITEM_TEMPLATE =
        "(%INDENT%)($LIST_BULLET) +[^\\n]*(?:\\n(?!%INDENT%$LIST_BULLET )[^\\n]*)*(\\n|\$)"

    private val PATTERN_LIST_ITEM_END: Pattern = Pattern.compile(" *\\n\\z")

    /**
     * A list may open on a line that still has indentation in front of it.
     *
     * An item strips only as much indentation from its continuation lines as its own bullet took, so
     * a sub-list indented by more than that keeps the difference. The text rule swallows the newline
     * together with those leftover spaces, which leaves the capture before the bullet ending in a
     * space rather than the newline every other block rule insists on.
     */
    private val PATTERN_LIST_LOOKBEHIND: Pattern = Pattern.compile("\\n *$")

    /**
     * SimpleAST's text pattern with one stop added, before `12.`.
     *
     * Digits are ordinary text, so without it the text preceding a nested ordered list swallows the
     * newline and the marker's digits, and the list rule never sees a line to start on.
     */
    private val PATTERN_TEXT: Pattern = Pattern.compile(
        "^[\\s\\S]+?(?=[^0-9A-Za-z\\s\\u00c0-\\uffff]|\\n| {2,}\\n|\\w+:\\S|[0-9]+\\.|\$)",
    )
    private val PATTERN_HEADER_ITEM: Pattern =
        Pattern.compile("^ *(#{1,3})(?:\\s+)([^\\n]+?)#*\\s*(?:\\n|\$)")
    private val PATTERN_SUBTEXT: Pattern = Pattern.compile("^ *-# +((?!(-#)+)[^\\n]+?) *(?:\\n|\$)")
    private val PATTERN_COMMAND_MENTION: Pattern = Pattern.compile(
        "^</((?:[-_\\p{L}\\p{N}]{1,32})(?: [-_\\p{L}\\p{N}]{1,32}){0,2}):(\\d+)>",
    )

    /** A masked link this dense in brackets is an attempt to make the link parser backtrack. */
    private val PATHOLOGICAL_MASKED_LINK_ATTACK_SUSPICIOUS_CHARS = setOf('[', ']')
    private const val PATHOLOGICAL_MASKED_LINK_MIN_LENGTH = 30
    private const val PATHOLOGICAL_MASKED_LINK_SUSPICIOUS_RATIO = 0.3

    private const val MIN_LIST_START = 1L
    /** Past this an ordered list stops counting, as on desktop, where the attribute is ignored. */
    private const val MAX_LIST_START = 1_000_000_000L

    // endregion

    /**
     * Threaded through the parser so a block quote rule can refuse to open a nested quote and a
     * code block can know it is being rendered inside one.
     */
    interface BlockQuoteState<Self : BlockQuoteState<Self>> {
        val isInQuote: Boolean
        fun newBlockQuoteState(isInQuote: Boolean): Self
    }

    /** Threaded through the parser so the list rule can nest and suppress headers inside an item. */
    interface ListState<Self : ListState<Self>> {
        val listLevel: Int
        val allowHeaders: Boolean
        fun newListItemState(): Self
    }

    /**
     * A block rule that matches with a fresh [Matcher] every time.
     *
     * [Rule] keeps one matcher per rule and hands it to the parser, which reads the capture only
     * after `parse` has returned. The block rules below parse their own content by re-entering the
     * parser, which can match the same rule again and reset that one matcher out from under the
     * caller, so each match needs a matcher of its own.
     */
    private abstract class ReentrantBlockRule<R, T : Node<R>, S>(
        private val pattern: Pattern,
    ) : Rule<R, T, S>(pattern) {

        /** A block starts a line. [createListRule] relaxes this; see [PATTERN_LIST_LOOKBEHIND]. */
        protected open fun isAtLineStart(lastCapture: String): Boolean = lastCapture.endsWith('\n')

        override fun match(inspectionSource: CharSequence, lastCapture: String?, state: S): Matcher? {
            if (lastCapture != null && !isAtLineStart(lastCapture)) return null
            return pattern.matcher(inspectionSource).takeIf(Matcher::find)
        }
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

    /**
     * `# heading` through `### heading`.
     *
     * Parses its own content instead of handing back a range, because SimpleAST keeps a single flat
     * "last capture" for the whole traversal: descending into a child would overwrite the newline
     * this rule matched, and the next [Rule.BlockRule] on the following line would refuse to match.
     * A nested parse runs its own loop, so the header's capture is what the next rule sees.
     */
    fun <T : BasicRenderContext, S : ListState<S>> createHeaderItemRule(): Rule<T, HeaderNode<T>, S> =
        object : ReentrantBlockRule<T, HeaderNode<T>, S>(PATTERN_HEADER_ITEM) {
            override fun match(inspectionSource: CharSequence, lastCapture: String?, state: S): Matcher? =
                if (state.allowHeaders) super.match(inspectionSource, lastCapture, state) else null

            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in HeaderNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> {
                val node = HeaderNode<T>(matcher.group(1)?.length ?: 1)
                parseInto(node, parser, matcher.group(2).orEmpty(), state)
                return ParseSpec.createTerminal(node, state)
            }
        }

    /** `-# muted supporting text`; unlike headings, this is enabled in every message parser. */
    fun <T : BasicRenderContext, S> createSubtextRule(): Rule<T, SubtextNode<T>, S> =
        object : ReentrantBlockRule<T, SubtextNode<T>, S>(PATTERN_SUBTEXT) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in SubtextNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> {
                val node = SubtextNode<T>()
                parseInto(node, parser, matcher.group(1).orEmpty().trim(), state)
                return ParseSpec.createTerminal(node, state)
            }
        }

    /**
     * A whole `- item` / `1. item` list.
     *
     * The list is captured in one match and split into items here rather than one rule match per
     * line, because an item owns its continuation lines and its indented sub-list, and the nesting
     * level of a bullet is only meaningful relative to the first bullet of the list it belongs to.
     */
    fun <T : BasicRenderContext, S : ListState<S>> createListRule(): Rule<T, ListNode<T>, S> =
        object : ReentrantBlockRule<T, ListNode<T>, S>(PATTERN_LIST) {
            override fun isAtLineStart(lastCapture: String): Boolean =
                lastCapture.isEmpty() || PATTERN_LIST_LOOKBEHIND.matcher(lastCapture).find()

            override fun match(inspectionSource: CharSequence, lastCapture: String?, state: S): Matcher? =
                if (state.listLevel >= MessageParseState.MAX_LIST_NESTING_LEVEL) {
                    null
                } else {
                    super.match(inspectionSource, lastCapture, state)
                }

            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in ListNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> {
                val ordered = matcher.group(2).orEmpty().length > 1
                val start = if (ordered) {
                    matcher.group(2).orEmpty().removeSuffix(".").toLongOrNull()
                        ?.coerceIn(MIN_LIST_START, MAX_LIST_START)?.toInt() ?: MIN_LIST_START.toInt()
                } else {
                    0
                }
                val node = ListNode<T>()
                splitListItems(matcher.group()).forEachIndexed { index, content ->
                    val item = ListItemNode<T>(
                        marker = if (ordered) "${start + index}. " else null,
                        level = state.listLevel,
                    )
                    node.addChild(item)
                    // A sub-list is hoisted next to the item that introduced it, not under it.
                    parseListItem(parser, content, state).forEach { child ->
                        if (child is ListNode<*>) node.addChild(child) else item.addChild(child)
                    }
                }
                return ParseSpec.createTerminal(node, state)
            }
        }

    /**
     * Splits a captured list into its sibling items.
     *
     * A bullet is a sibling of the first one when it carries the same leading-space run, or one
     * more; anything deeper belongs to the item above it and is re-parsed as part of its content.
     */
    private fun splitListItems(capturedList: String): List<String> {
        val prefix = PATTERN_LIST_ITEM_PREFIX.matcher(capturedList)
        if (!prefix.find()) return emptyList()
        val prefixLength = prefix.group().length
        val leadingSpaces = prefix.group(1).orEmpty().length
        val siblingIndent = " {$leadingSpaces,${leadingSpaces + 1}}"
        val itemPattern = Pattern.compile(
            LIST_ITEM_TEMPLATE.replace("%INDENT%", siblingIndent),
            Pattern.MULTILINE,
        )
        // Every line of an item's continuation carries the first bullet's indentation; the
        // reference measures that from the first bullet alone so deeper bullets keep their level.
        val continuationIndent = Pattern.compile("^ {1,$prefixLength}", Pattern.MULTILINE)

        val items = ArrayList<String>()
        val matcher = itemPattern.matcher(capturedList)
        while (matcher.find()) {
            val withoutBullet = PATTERN_LIST_ITEM_PREFIX.matcher(matcher.group()).replaceFirst("")
            items += continuationIndent.matcher(withoutBullet).replaceAll("")
        }
        return items
    }

    private fun <T : BasicRenderContext, S : ListState<S>> parseListItem(
        parser: Parser<T, in ListNode<T>, S>,
        content: String,
        state: S,
    ): List<Node<T>> {
        @Suppress("UNCHECKED_CAST")
        val innerParser = parser as Parser<T, Node<T>, S>
        val trimmed = PATTERN_LIST_ITEM_END.matcher(content).replaceFirst("")
        val parsed = innerParser.parse(trimmed, state.newListItemState()).toMutableList()
        // An item owns the break that ends it, so any the content left behind — at its end, or
        // ahead of the sub-list it introduces — would draw a blank line between two bullets.
        for (index in parsed.indices.reversed()) {
            val node = parsed[index]
            if (node !is TextNode<*> || node.content.isNotBlank()) continue
            if (index == parsed.lastIndex || parsed[index + 1] is ListNode<*>) parsed.removeAt(index)
        }
        return parsed
    }

    private fun <T, S> parseInto(node: Node<T>, parser: Parser<T, *, S>, source: String, state: S) {
        if (source.isEmpty()) return
        @Suppress("UNCHECKED_CAST")
        val innerParser = parser as Parser<T, Node<T>, S>
        innerParser.parse(source, state).forEach(node::addChild)
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

    /** `</name subcommand:id>`, with the same Unicode command-name boundaries as desktop. */
    fun <T : CommandMentionNode.RenderContext, S> createCommandMentionRule(): Rule<T, CommandMentionNode<T>, S> =
        object : Rule<T, CommandMentionNode<T>, S>(PATTERN_COMMAND_MENTION) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in CommandMentionNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> = ParseSpec(
                CommandMentionNode(matcher.group(1).orEmpty(), matcher.group(2).toLongOrDefault()),
                state,
            )
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

    /** Whether [url] is a custom emoji's CDN image; such a link renders inline as the emoji. */
    fun isEmojiUrl(url: String?): Boolean = url != null && PATTERN_EMOJI_URL.matcher(url).matches()

    /**
     * An emoji CDN link, bare or as `[name](url)`, drawn as the emoji itself the way client mods'
     * fake-emoji messages are meant to be read. The name comes from the URL's `name=` parameter or
     * the mask; `.gif` or `animated=true` makes it animated.
     */
    fun <T : EmojiNode.RenderContext, S> createEmojiLinkRule(masked: Boolean): Rule<T, EmojiNode<T>, S> =
        object : Rule<T, EmojiNode<T>, S>(if (masked) PATTERN_MASKED_EMOJI_LINK else PATTERN_EMOJI_LINK) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in EmojiNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> {
                val url = matcher.group(if (masked) 2 else 1).orEmpty()
                val path = url.substringBefore('?')
                val query = url.substringAfter('?', "").split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
                val name = query["name"]?.takeIf { it.isNotBlank() }
                    ?: matcher.group(1).takeIf { masked && !it.isNullOrBlank() }
                    ?: "emoji"
                val emojiId = path.substringAfterLast('/').substringBefore('.').toLongOrDefault()
                val isAnimated = path.endsWith(".gif") || query["animated"] == "true"
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

    /** A `discord.com/channels/...` URL, rendered as a channel-mention pill (RN parity). */
    fun <T : ChannelLinkNode.RenderContext, S> createChannelLinkRule(): Rule<T, ChannelLinkNode<T>, S> =
        object : Rule<T, ChannelLinkNode<T>, S>(PATTERN_CHANNEL_LINK) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in ChannelLinkNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> {
                val guild = matcher.group(2).takeUnless { it == "@me" }?.toLongOrNull()
                val channelId = matcher.group(3).toLongOrNull() ?: return ParseSpec(
                    UrlNode(matcher.group(1).orEmpty()),
                    state,
                )
                val messageId = matcher.group(4)?.toLongOrNull()
                return ParseSpec(
                    ChannelLinkNode(matcher.group(1).orEmpty(), channelId, guild, messageId),
                    state,
                )
            }
        }

    /** A `discord.com/users/...` URL, rendered as an `@username` mention (RN parity). */
    fun <T : UserLinkNode.RenderContext, S> createUserLinkRule(): Rule<T, UserLinkNode<T>, S> =
        object : Rule<T, UserLinkNode<T>, S>(PATTERN_USER_LINK) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in UserLinkNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> = ParseSpec(
                UserLinkNode(matcher.group(1).orEmpty(), matcher.group(2).toLongOrDefault()),
                state,
            )
        }

    /** A `cdn.discordapp.com/attachments/...` URL, rendered as a paperclip + filename pill (RN parity). */
    fun <T : AttachmentLinkNode.RenderContext, S> createAttachmentLinkRule(): Rule<T, AttachmentLinkNode<T>, S> =
        object : Rule<T, AttachmentLinkNode<T>, S>(PATTERN_ATTACHMENT_LINK) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in AttachmentLinkNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> = ParseSpec(
                AttachmentLinkNode(matcher.group(1).orEmpty(), matcher.group(2).orEmpty()),
                state,
            )
        }

    /** A bare `https://` link. */
    fun <T : UrlNode.RenderContext, S> createUrlRule(): Rule<T, UrlNode<T>, S> =
        object : Rule<T, UrlNode<T>, S>(PATTERN_URL) {
            override fun match(inspectionSource: CharSequence, lastCapture: String?, state: S): Matcher? {
                val matcher = super.match(inspectionSource, lastCapture, state) ?: return null
                val url = matcher.group(1).orEmpty()
                var openParenthesisSearchStart = 0
                var trimmedLength = url.length
                for (index in url.lastIndex downTo 0) {
                    if (url[index] != ')') break
                    val opening = url.indexOf('(', openParenthesisSearchStart)
                    if (opening < 0) {
                        trimmedLength--
                    } else {
                        openParenthesisSearchStart = opening + 1
                    }
                }
                if (trimmedLength == url.length) return matcher

                matcher.region(0, matcher.start(1) + trimmedLength)
                return matcher.takeIf { it.lookingAt() }
            }

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

    /** `<name@example.com>` */
    fun <T : UrlNode.RenderContext, S> createMailtoRule(): Rule<T, UrlNode<T>, S> =
        object : Rule<T, UrlNode<T>, S>(PATTERN_MAILTO) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in UrlNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> {
                val address = matcher.group(1).orEmpty()
                return ParseSpec(UrlNode("mailto:$address", address), state)
            }
        }

    /** `<+1 555 0100>`, `<tel:...>` and `<sms:...>`. */
    fun <T : UrlNode.RenderContext, S> createTelephoneRule(): Rule<T, UrlNode<T>, S> =
        object : Rule<T, UrlNode<T>, S>(PATTERN_TEL) {
            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in UrlNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> {
                val display = matcher.group(1).orEmpty()
                val target = display.replace(Regex("[ /]+"), "-").let {
                    if (it.startsWith("tel:") || it.startsWith("sms:")) it else "tel:$it"
                }
                return ParseSpec(UrlNode(target, display), state)
            }
        }

    /** `[label](https://...)`, only enabled for surfaces that allow masked links. */
    fun <T : UrlNode.RenderContext, S> createMaskedLinkRule(): Rule<T, UrlNode<T>, S> =
        object : Rule<T, UrlNode<T>, S>(PATTERN_MASKED_LINK) {
            override fun match(inspectionSource: CharSequence, lastCapture: String?, state: S): Matcher? =
                if (isLikelyPathologicalAttack(inspectionSource)) {
                    null
                } else {
                    super.match(inspectionSource, lastCapture, state)?.takeIf {
                        runCatching { toPunyCodeAsciiUrl(it.group(2).orEmpty()) }.isSuccess
                    }
                }

            override fun parse(
                matcher: Matcher,
                parser: Parser<T, in UrlNode<T>, S>,
                state: S,
            ): ParseSpec<T, S> {
                val mask = matcher.group(1).orEmpty()
                val url = matcher.group(2).orEmpty()
                // The label is markdown too (`[**bold**](url)`), so it is parsed into children.
                return ParseSpec(UrlNode(url, mask), state, matcher.start(1), matcher.end(1))
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
        object : Rule<T, Node<T>, S>(PATTERN_TEXT) {
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
    internal fun <RC : BasicRenderContext> createCodeStyleProviders(): CodeStyleProviders<RC> =
        CodeStyleProviders(
            spanProvider { context -> listOf(codeTextAppearance(context), RelativeSizeSpan(CODE_TEXT_SCALE)) },
            textAppearanceProvider(R.style.Code_TextAppearance_Comment),
            textAppearanceProvider(R.style.Code_TextAppearance_Literal),
            textAppearanceProvider(R.style.Code_TextAppearance_Keyword),
            textAppearanceProvider(R.style.Code_TextAppearance_Identifier),
            textAppearanceProvider(R.style.Code_TextAppearance_Types),
            textAppearanceProvider(R.style.Code_TextAppearance_Generics),
            textAppearanceProvider(R.style.Code_TextAppearance_Params),
            textAppearanceProvider(R.style.Code_TextAppearance_Addition),
            textAppearanceProvider(R.style.Code_TextAppearance_Deletion),
        )
}

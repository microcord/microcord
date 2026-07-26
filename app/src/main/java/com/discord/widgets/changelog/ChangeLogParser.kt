package com.discord.widgets.changelog

import android.content.Context
import android.text.style.CharacterStyle
import android.text.style.ParagraphStyle
import android.text.style.StyleSpan
import android.text.style.TextAppearanceSpan
import com.discord.R
import com.discord.app.AppToast
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.node.StyleNode
import com.discord.simpleast.core.parser.ParseSpec
import com.discord.simpleast.core.parser.Parser
import com.discord.simpleast.core.parser.Rule
import com.discord.simpleast.core.simple.SimpleMarkdownRules
import com.discord.simpleast.markdown.MarkdownRules
import com.discord.utilities.textprocessing.AstRenderer
import com.discord.utilities.textprocessing.MessageParseState
import com.discord.utilities.textprocessing.Rules
import com.discord.utilities.textprocessing.node.UrlNode
import com.discord.utilities.textprocessing.spans.BulletSpan
import com.discord.utilities.textprocessing.spans.VerticalPaddingSpan
import com.discord.views.ThemedColor
import com.facebook.drawee.span.DraweeSpanStringBuilder
import java.util.regex.Matcher

/**
 * Turns the markdown body of a changelog into styled text.
 *
 * Changelog markdown is a different dialect from message markdown: no mentions, no emoji, no
 * spoilers, but it *does* use setext-style class-tagged headers — `Added {added}`, `Fixed {fixed}`,
 * `Improved {improved}`, `Progress {progress}` — which colour the section title, plus markdown
 * bullet lists. That is why this is its own parser and not a [com.discord.utilities.textprocessing.DiscordParser]
 * mode.
 */
object ChangeLogParser {

    /**
     * @param changeLogRules `true` selects the branded "special" changelog styling, which promotes
     *   H1 to the larger `Markdown.ChangelogSpecial.Header1` appearance and gives the first header a
     *   wider top margin.
     * @param onClickListener receives `(context, url, mask)` for every link the body contains.
     */
    @JvmStatic
    @JvmOverloads
    fun parse(
        context: Context,
        input: CharSequence,
        changeLogRules: Boolean = false,
        onClickListener: (Context, String, String?) -> Unit,
    ): DraweeSpanStringBuilder {
        val parser = Parser<ChangeLogRenderContext, Node<ChangeLogRenderContext>, MessageParseState>(false)
        parser.addRule(Rules.createSoftHyphenRule())
        parser.addRule(Rules.createBlockQuoteRule())
        parser.addRule(Rules.createMaskedLinkRule())
        parser.addRule(Rules.createUrlNoEmbedRule())
        parser.addRule(Rules.createUrlRule())
        parser.addRule(Rules.createHookedLinkRule())
        parser.addRules(
            if (changeLogRules) {
                createChangelogSpecialMarkdownRules(context)
            } else {
                createMarkdownRules(context)
            },
        )
        parser.addRules(SimpleMarkdownRules.createSimpleMarkdownRules())

        return AstRenderer.render(
            parser.parse(input, MessageParseState.initialState),
            ChangeLogRenderContext(context, onClickListener),
        )
    }

    private fun createMarkdownRules(
        context: Context,
    ): List<Rule<ChangeLogRenderContext, Node<ChangeLogRenderContext>, MessageParseState>> = listOf(
        MarkdownRules.HeaderRule(ChangelogSpans.createHeaderStyleSpanProvider(context)),
        HeaderLineClassedRule(
            ChangelogSpans.createHeaderPaddingSpansProvider(context),
            ChangelogSpans.createHeaderStyleSpanProvider(context),
            ChangelogSpans.createHeaderClassSpanProvider(context),
        ),
        MarkdownListItemRule(ChangelogSpans.createMarkdownBulletSpansProvider(context)),
    )

    private fun createChangelogSpecialMarkdownRules(
        context: Context,
    ): List<Rule<ChangeLogRenderContext, Node<ChangeLogRenderContext>, MessageParseState>> = listOf(
        HeaderLineClassedRule(
            ChangelogSpans.createChangelogSpecialHeaderPaddingSpansProvider(context),
            ChangelogSpans.createHeaderStyleSpanProvider(context),
            ChangelogSpans.createHeaderClassSpanProvider(context),
        ),
        HeaderLineClassedRule(
            ChangelogSpans.createHeaderPaddingSpansProvider(context),
            ChangelogSpans.createSpecialHeaderStyleSpanProvider(context),
            ChangelogSpans.createHeaderClassSpanProvider(context),
        ),
        MarkdownListItemRule(ChangelogSpans.createMarkdownBulletSpansProvider(context)),
    )
}

/**
 * Render context for changelog bodies.
 *
 * Long-pressing a link toasts its destination — the reference's `AppToast.show(context, url)` — so
 * a masked link's real target can be inspected without following it.
 */
internal class ChangeLogRenderContext(
    override val context: Context,
    override val onClickUrl: (Context, String, String?) -> Unit,
) : UrlNode.RenderContext {

    override val linkColorAttrResId: Int = R.attr.colorTextLink

    override val onLongPressUrl: (String) -> Unit = { url ->
        AppToast.show(context, url)
    }
}

/**
 * A classed header line that also reserves vertical space around itself.
 *
 * The library's [MarkdownRules.HeaderLineClassedRule] produces the styled header node; wrapping it
 * in a [StyleNode] carrying [VerticalPaddingSpan]s is what separates changelog sections, because
 * the recovered styles set no margins of their own.
 *
 * Belongs in `com.discord.utilities.textprocessing.Rules` as a nested class (that is where the
 * reference keeps it); it lives here only because that file is owned by another agent.
 */
internal class HeaderLineClassedRule<RC, S>(
    private val headerPaddingSpanProvider: () -> List<Any>,
    styleSpanProvider: (Int) -> CharacterStyle,
    classSpanProvider: (String) -> Any?,
) : MarkdownRules.HeaderLineClassedRule<RC, Any?, S>(styleSpanProvider, classSpanProvider) {

    override fun parse(matcher: Matcher, parser: Parser<RC, in Node<RC>, S>, state: S): ParseSpec<RC, S> {
        val headerNode = super.parse(matcher, parser, state).root
        val paddedNode = StyleNode<RC, Any>(headerPaddingSpanProvider())
        paddedNode.addChild(headerNode)
        return ParseSpec(paddedNode, state)
    }
}

/**
 * A markdown bullet whose spans are supplied by the caller.
 *
 * The library's `ListItemRule` only accepts a `BulletSpan` factory; the changelog needs a bullet
 * *and* vertical padding, so it re-implements the parse against the same pattern and emits a
 * [StyleNode] holding both. As above, this belongs next to the reference's `Rules`.
 */
internal class MarkdownListItemRule<RC, S>(
    private val spansProvider: () -> List<Any>,
) : Rule.BlockRule<RC, Node<RC>, S>(MarkdownRules.PATTERN_LIST_ITEM) {

    override fun parse(matcher: Matcher, parser: Parser<RC, in Node<RC>, S>, state: S): ParseSpec<RC, S> =
        ParseSpec(StyleNode<RC, Any>(spansProvider()), state, matcher.start(1), matcher.end(1))
}

/**
 * Span providers for changelog markdown.
 *
 * The class names come from the changelog markdown itself (`{added}`, `{fixed}`, `{improved}`,
 * `{progress}`, `{changelogSpecial}`, `{marginTop}`) and each maps to a recovered text appearance.
 * An unrecognised class yields `null`, which leaves the header at its default style rather than
 * failing the parse.
 *
 * Belongs at `com.discord.utilities.textprocessing.ChangelogSpans`; kept here for the same
 * ownership reason as the two rules above.
 */
internal object ChangelogSpans {

    /** Px, not dp — the reference passes a raw radius to [BulletSpan]. */
    private const val MARKDOWN_BULLET_RADIUS = 8

    fun createHeaderStyleSpanProvider(context: Context): (Int) -> CharacterStyle = { header ->
        when (header) {
            1 -> TextAppearanceSpan(context, R.style.Markdown_Changelog_Header1)
            4 -> TextAppearanceSpan(context, R.style.Markdown_Changelog_Header4)
            else -> StyleSpan(android.graphics.Typeface.BOLD_ITALIC)
        }
    }

    fun createSpecialHeaderStyleSpanProvider(context: Context): (Int) -> CharacterStyle = { header ->
        when (header) {
            1 -> TextAppearanceSpan(context, R.style.Markdown_ChangelogSpecial_Header1)
            4 -> TextAppearanceSpan(context, R.style.Markdown_Changelog_Header4)
            else -> StyleSpan(android.graphics.Typeface.BOLD_ITALIC)
        }
    }

    fun createHeaderPaddingSpansProvider(context: Context): () -> List<VerticalPaddingSpan> {
        val top = context.resources.getDimensionPixelSize(R.dimen.markdown_header_1_top_padding)
        val bottom = context.resources.getDimensionPixelSize(R.dimen.markdown_header_1_bottom_padding)
        return { listOf(VerticalPaddingSpan(top, bottom)) }
    }

    fun createChangelogSpecialHeaderPaddingSpansProvider(context: Context): () -> List<VerticalPaddingSpan> {
        val top = context.resources
            .getDimensionPixelSize(R.dimen.markdown_header_1_changelog_special_top_padding)
        val bottom = context.resources.getDimensionPixelSize(R.dimen.markdown_header_1_bottom_padding)
        return { listOf(VerticalPaddingSpan(top, bottom)) }
    }

    fun createHeaderClassSpanProvider(context: Context): (String) -> Any? {
        val marginTop = context.resources.getDimensionPixelSize(R.dimen.markdown_header_class_marginTop)
        return { className ->
            when (className) {
                "added" -> TextAppearanceSpan(context, R.style.Markdown_Changelog_Header1_Added)
                "fixed" -> TextAppearanceSpan(context, R.style.Markdown_Changelog_Header1_Fixed)
                "improved" -> TextAppearanceSpan(context, R.style.Markdown_Changelog_Header1_Improved)
                "progress" -> TextAppearanceSpan(context, R.style.Markdown_Changelog_Header1_Progress)
                "changelogSpecial" ->
                    TextAppearanceSpan(context, R.style.Markdown_ChangelogSpecial_Header1)
                "marginTop" -> VerticalPaddingSpan(0, marginTop)
                else -> null
            }
        }
    }

    fun createMarkdownBulletSpansProvider(context: Context): () -> List<ParagraphStyle> {
        val gapWidth = context.resources.getDimensionPixelSize(R.dimen.markdown_bullet_gap)
        val verticalPadding =
            context.resources.getDimensionPixelSize(R.dimen.markdown_bullet_vertical_padding)
        val color = ThemedColor.resolve(context, R.attr.color_brand_500)
        return {
            listOf(
                VerticalPaddingSpan(verticalPadding, verticalPadding),
                BulletSpan(gapWidth, color, MARKDOWN_BULLET_RADIUS),
            )
        }
    }
}

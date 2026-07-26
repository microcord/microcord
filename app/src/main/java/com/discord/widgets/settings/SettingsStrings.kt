package com.discord.widgets.settings

import android.content.Context
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.view.View
import androidx.annotation.StringRes
import com.discord.i18n.FormatUtils
import com.discord.utilities.uri.UriHandler

/**
 * Renders a recovered Discord string for a settings/servers screen.
 *
 * Recovered strings carry four kinds of markup at once, for example
 * `"Mute **!!{name}!!**"`,
 * `"…Check our [Help Center]({helpdeskArticle}) for more info!"` and
 * `"You have muted this channel until $[**{endTime}**](endTimeHook)"`:
 *
 * - named `{placeholder}` tokens and `!!hook!!` markers, handled by [FormatUtils];
 * - `**bold**` emphasis;
 * - `[label](destination)` masked links;
 * - `$[label](hookName)` *hook* runs, where `hookName` names a caller-supplied style rather than a
 *   destination. The reference passes a `RenderContext` lambda per hook that mutates the run's
 *   colour/typeface; [render]'s `hooks` map is the same contract, keyed by hook name.
 *
 * The reference renders all three through `com.discord.i18n.RenderContext` / `Hook`
 * (`b.a.k.b.h/j/k/m/n` after obfuscation), which this project has not reconstructed —
 * `com.discord.i18n` currently contains only [FormatUtils], which strips the hooks and substitutes
 * the tokens but leaves the emphasis and link syntax literal.
 *
 * This file covers the last two so settings screens do not display raw asterisks and brackets. It is
 * a stopgap that belongs in `com.discord.i18n` next to [FormatUtils]; it is deliberately kept in one
 * place rather than re-implemented per screen, and it is listed in `docs/parity-audit/followups.md`
 * so the i18n owner can fold it in.
 */
internal object SettingsStrings {

    private val BOLD = Regex("""\*\*(.+?)\*\*""", RegexOption.DOT_MATCHES_ALL)
    private val MASKED_LINK = Regex("""\[([^]]+)]\(([^)]+)\)""")
    private val HOOK = Regex("""\$\[([^]]+)]\(([^)]+)\)""")

    /**
     * Formats [stringRes] with [args] and applies hook, bold and masked-link spans.
     *
     * @param hooks spans to apply to each `$[…](hookName)` run, keyed by hook name. A hook with no
     *   entry still has its markup removed, so the run renders as plain text rather than as
     *   literal `$[`…`]`.
     * @param onLink invoked for a masked link's destination; defaults to opening it through
     *   [UriHandler], which is what every settings link in the reference does.
     */
    fun render(
        context: Context,
        @StringRes stringRes: Int,
        vararg args: Pair<String, Any?>,
        hooks: Map<String, List<Any>> = emptyMap(),
        onLink: ((String) -> Unit)? = null,
    ): CharSequence =
        render(context, FormatUtils.format(context.getString(stringRes), *args), hooks, onLink)

    fun render(
        context: Context,
        text: CharSequence,
        hooks: Map<String, List<Any>> = emptyMap(),
        onLink: ((String) -> Unit)? = null,
    ): CharSequence {
        val builder = SpannableStringBuilder(text)
        // Bold first: a hook run is usually written `$[**{token}**](hook)`, and rewriting the inner
        // emphasis afterwards would replace the hook span's entire range, which drops an
        // exclusive-exclusive span.
        applyBold(builder)
        // Hooks before links: `$[label](hook)` also matches [MASKED_LINK], so running the link pass
        // first would turn a style hook into a link to the literal text "endTimeHook".
        applyHooks(builder, hooks)
        applyMaskedLinks(builder) { url -> onLink?.invoke(url) ?: UriHandler.handle(context, url) }
        return builder
    }

    private fun applyHooks(builder: SpannableStringBuilder, hooks: Map<String, List<Any>>) {
        while (true) {
            val match = HOOK.find(builder) ?: return
            val hookName = match.groupValues[2]
            unwrap(builder, match, prefixLength = 2) { start, end ->
                hooks[hookName].orEmpty().forEach { span ->
                    builder.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    private fun applyMaskedLinks(builder: SpannableStringBuilder, onLink: (String) -> Unit) {
        while (true) {
            val match = MASKED_LINK.find(builder) ?: return
            val destination = match.groupValues[2]
            unwrap(builder, match, prefixLength = 1) { start, end ->
                builder.setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) = onLink(destination)
                    },
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }

    private fun applyBold(builder: SpannableStringBuilder) {
        var searchFrom = 0
        while (true) {
            val match = BOLD.find(builder, searchFrom) ?: return
            unwrap(builder, match, prefixLength = 2) { start, end ->
                builder.setSpan(
                    StyleSpan(android.graphics.Typeface.BOLD),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                searchFrom = end
            }
        }
    }

    /**
     * Deletes the delimiters around [match]'s first capture group and hands the surviving run's
     * bounds to [span].
     *
     * The delimiters are *deleted* rather than the whole match being replaced with its label: a
     * replacement that covers a previously applied span's entire range drops that span, which is
     * exactly the case for a hook wrapped around emphasis (`$[**{token}**](hook)`).
     */
    private inline fun unwrap(
        builder: SpannableStringBuilder,
        match: MatchResult,
        prefixLength: Int,
        span: (start: Int, end: Int) -> Unit,
    ) {
        val start = match.range.first + prefixLength
        val end = start + match.groupValues[1].length
        // Trailing delimiters go first so [start] and [end] stay valid.
        builder.delete(end, match.range.last + 1)
        builder.delete(match.range.first, start)
        span(match.range.first, match.range.first + match.groupValues[1].length)
    }
}

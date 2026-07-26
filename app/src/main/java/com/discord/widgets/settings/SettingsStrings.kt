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
 * Recovered strings carry three kinds of markup at once, for example
 * `"Mute **!!{name}!!**"` and
 * `"…Check our [Help Center]({helpdeskArticle}) for more info!"`:
 *
 * - named `{placeholder}` tokens and `!!hook!!` markers, handled by [FormatUtils];
 * - `**bold**` emphasis;
 * - `[label](destination)` masked links.
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

    /**
     * Formats [stringRes] with [args] and applies bold + masked-link spans.
     *
     * @param onLink invoked for a masked link's destination; defaults to opening it through
     *   [UriHandler], which is what every settings link in the reference does.
     */
    fun render(
        context: Context,
        @StringRes stringRes: Int,
        vararg args: Pair<String, Any?>,
        onLink: ((String) -> Unit)? = null,
    ): CharSequence = render(context, FormatUtils.format(context.getString(stringRes), *args), onLink)

    fun render(
        context: Context,
        text: CharSequence,
        onLink: ((String) -> Unit)? = null,
    ): CharSequence {
        val builder = SpannableStringBuilder(text)
        applyMaskedLinks(builder) { url -> onLink?.invoke(url) ?: UriHandler.handle(context, url) }
        applyBold(builder)
        return builder
    }

    private fun applyMaskedLinks(builder: SpannableStringBuilder, onLink: (String) -> Unit) {
        while (true) {
            val match = MASKED_LINK.find(builder) ?: return
            val label = match.groupValues[1]
            val destination = match.groupValues[2]
            builder.replace(match.range.first, match.range.last + 1, label)
            builder.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) = onLink(destination)
                },
                match.range.first,
                match.range.first + label.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun applyBold(builder: SpannableStringBuilder) {
        var searchFrom = 0
        while (true) {
            val match = BOLD.find(builder, searchFrom) ?: return
            val inner = match.groupValues[1]
            builder.replace(match.range.first, match.range.last + 1, inner)
            builder.setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                match.range.first,
                match.range.first + inner.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            searchFrom = match.range.first + inner.length
        }
    }
}

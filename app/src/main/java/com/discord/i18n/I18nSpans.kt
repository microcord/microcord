package com.discord.i18n

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.widget.TextView
import androidx.annotation.StringRes

/**
 * Renders the inline markup Discord's translated strings carry.
 *
 * [FormatUtils] substitutes `{name}` tokens and strips `!!hook!!` markers, but the recovered strings
 * also contain markdown: `[label](https://…)` masked links and `**bold**` runs. Setting such a
 * string straight onto a `TextView` shows the raw brackets and asterisks — which is exactly what
 * happens today on the Terms of Service screen, whose four link rows are entirely markdown.
 *
 * This is the span-producing half of the reference's `com.discord.i18n` formatter (the obfuscated
 * `b.a.k.b`, whose `m`/`n`/`e` overloads every screen calls). Only the two constructs that appear in
 * first-party strings are handled; a full markdown pass belongs to
 * `com.discord.utilities.textprocessing` and is not duplicated here.
 */
object I18nSpans {

    /**
     * `[label](target)` — the target may itself have been produced by a `{token}` substitution.
     *
     * The optional leading `$` is Discord's marker for "this target names a hook, not a URL"
     * (`$[Learn More](learnMoreHook)`). It is consumed by the match so it never reaches the user;
     * without that, every hook string renders with a stray `$` in front of its label.
     */
    // The closing `]` is escaped for the same reason `}` is in FormatUtils: Android's ICU engine
    // rejects a bare closing delimiter that the JVM accepts.
    private val MASKED_LINK = Regex("""\$?\[([^\[\]]+)\]\(([^()\s]+)\)""")

    /** `**bold**`, non greedy so two runs in one string do not merge. */
    private val BOLD = Regex("""\*\*(.+?)\*\*""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Substitutes [args] into [template] and converts the surviving markdown into spans.
     *
     * Link targets become [URLSpan]s so a `LinkifiedTextView` (or any view with a link movement
     * method) opens them; bold runs become [StyleSpan]s.
     */
    @JvmStatic
    fun render(template: CharSequence, vararg args: Pair<String, Any?>): CharSequence =
        renderMarkdown(FormatUtils.format(template, *args))

    private fun renderMarkdown(source: String): CharSequence {
        val builder = SpannableStringBuilder(source)
        applyInline(builder, MASKED_LINK) { match -> URLSpan(match.groupValues[2]) }
        applyInline(builder, BOLD) { StyleSpan(android.graphics.Typeface.BOLD) }
        return builder
    }

    /**
     * Strips every [pattern] match down to its first capture group and spans what is left.
     *
     * Matches are rewritten last-to-first so earlier match offsets stay valid while the builder
     * shrinks.
     *
     * The label text itself is **never replaced, only surrounded** — the trailing delimiter is
     * deleted, then the leading one. That is load-bearing for nested markup: `SpannableStringBuilder`
     * drops any `SPAN_EXCLUSIVE_EXCLUSIVE` span whose whole range is overwritten, so rewriting
     * `**[label](target)**` with a `replace()` of the outer run would delete the `URLSpan` the inner
     * pass had just set (and swapping the two passes would delete the `StyleSpan` instead). Deleting
     * only the delimiters leaves the label's character range — and every span already anchored to it
     * — intact, which makes the two passes order-independent.
     */
    private fun applyInline(
        builder: SpannableStringBuilder,
        pattern: Regex,
        spanFor: (MatchResult) -> Any,
    ) {
        val matches = pattern.findAll(builder.toString()).toList()
        for (match in matches.asReversed()) {
            val labelRange = match.groups[1]!!.range
            val labelStart = labelRange.first
            val labelLength = labelRange.last + 1 - labelStart
            // Trailing delimiter first, so labelStart is still valid when the leading one goes.
            builder.delete(labelStart + labelLength, match.range.last + 1)
            builder.delete(match.range.first, labelStart)
            builder.setSpan(
                spanFor(match),
                match.range.first,
                match.range.first + labelLength,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }
}

/** Formats [stringRes] and applies its inline markup to this view. */
fun TextView.setI18nText(@StringRes stringRes: Int, vararg args: Pair<String, Any?>) {
    text = I18nSpans.render(context.getString(stringRes), *args)
}

/** Formats [stringRes] into a rendered [CharSequence] without touching a view. */
fun Context.i18nRender(@StringRes stringRes: Int, vararg args: Pair<String, Any?>): CharSequence =
    I18nSpans.render(getString(stringRes), *args)

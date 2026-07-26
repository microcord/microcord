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

    /** `[label](target)` — the target may itself have been produced by a `{token}` substitution. */
    private val MASKED_LINK = Regex("""\[([^\[\]]+)]\(([^()\s]+)\)""")

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
     * Replaces every [pattern] match with its first capture group and spans the replacement.
     *
     * Matches are rewritten last-to-first so earlier match offsets stay valid while the builder
     * shrinks.
     */
    private fun applyInline(
        builder: SpannableStringBuilder,
        pattern: Regex,
        spanFor: (MatchResult) -> Any,
    ) {
        val matches = pattern.findAll(builder.toString()).toList()
        for (match in matches.asReversed()) {
            val label = match.groupValues[1]
            builder.replace(match.range.first, match.range.last + 1, label)
            builder.setSpan(
                spanFor(match),
                match.range.first,
                match.range.first + label.length,
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

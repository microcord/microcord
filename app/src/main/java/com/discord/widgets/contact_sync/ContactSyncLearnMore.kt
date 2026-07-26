package com.discord.widgets.contact_sync

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.i18n.I18nSpans
import com.discord.utilities.spans.ClickableSpan

/** The hook name every contact-sync "Learn More" string uses. */
private const val LEARN_MORE_HOOK = "learnMoreHook"

/**
 * Renders a contact-sync consent string and makes its "Learn More" run tappable.
 *
 * The consent copy is not decoration — it is where the user is told that their address book will be
 * uploaded and re-uploaded — so the link has to work on every surface that shows it. Three do: the
 * landing step, the upsell sheet, and the permissions sheet.
 *
 * The strings are written as `… $[Learn More](learnMoreHook)`. [I18nSpans] turns the
 * `[label](target)` part into a [URLSpan], which is right for real links but wrong for a hook, so
 * the span is swapped for a [ClickableSpan] here. The leading `$` is Discord's marker for
 * "this is a hook, not a URL"; it is stripped rather than shown to the user.
 *
 * There are now three independent hook-swapping helpers in the codebase (see
 * `docs/parity-audit/followups.md`); they belong in `com.discord.i18n` and this one should be folded
 * in with them, including the `$` handling, which the shared renderer does not do today.
 */
internal fun TextView.setContactSyncLearnMoreText(@StringRes stringRes: Int, onClick: () -> Unit) {
    val rendered = I18nSpans.render(context.getString(stringRes))
    val builder = SpannableStringBuilder(rendered)
    val linkColor = ContextCompat.getColor(context, R.color.link)

    builder.getSpans(0, builder.length, URLSpan::class.java)
        .filter { it.url == LEARN_MORE_HOOK }
        .forEach { span ->
            val start = builder.getSpanStart(span)
            val end = builder.getSpanEnd(span)
            builder.removeSpan(span)
            builder.setSpan(
                ClickableSpan(linkColor, false) { onClick() },
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            // Drop the `$` hook marker that sits immediately before the label.
            if (start > 0 && builder[start - 1] == '$') builder.delete(start - 1, start)
        }

    text = builder
    movementMethod = LinkMovementMethod.getInstance()
}

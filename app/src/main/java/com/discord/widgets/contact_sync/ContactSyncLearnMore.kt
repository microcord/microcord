package com.discord.widgets.contact_sync

import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.i18n.I18nSpans
import com.discord.i18n.withClickableHook
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
 * the span is swapped for a [ClickableSpan] by [withClickableHook]. The leading `$` is Discord's
 * marker for "this is a hook, not a URL"; [I18nSpans] now consumes it as part of the match, so it is
 * never shown to the user here or anywhere else the pattern is used.
 *
 * All that is left here is the contact-sync specifics: the link colour and the movement method.
 */
internal fun TextView.setContactSyncLearnMoreText(@StringRes stringRes: Int, onClick: () -> Unit) {
    text = I18nSpans.render(context.getString(stringRes))
        .withClickableHook(LEARN_MORE_HOOK, ContextCompat.getColor(context, R.color.link), onClick)
    movementMethod = LinkMovementMethod.getInstance()
}

package com.discord.widgets.hubs

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View

/**
 * Turns the action markers Discord's hub strings carry into real click handlers.
 *
 * `hub_pin_description` is `"… [Resend code](onClick) to !!{email}!!"` and
 * `hub_email_connection_guild_select_subheader` is `"… [Join the waitlist.](onJoinWaitlist)"`.
 * [com.discord.i18n.I18nSpans] renders both as [URLSpan]s because most masked links really are links;
 * these name a hook the screen binds instead. Swapping the span keeps the translated wording and word
 * order authoritative rather than splitting the sentence in code.
 *
 * A near-identical helper exists in `widgets/chat/list/adapter` and the follow-ups list says both
 * belong in `com.discord.i18n`. This copy is deliberately local so the hub package does not depend on
 * a file another wave is actively moving; delete it when the shared one lands.
 */
internal fun CharSequence.withHubHook(hookName: String, onClick: (View) -> Unit): CharSequence {
    val builder = SpannableStringBuilder(this)
    builder.getSpans(0, builder.length, URLSpan::class.java)
        .filter { it.url == hookName }
        .forEach { span ->
            val start = builder.getSpanStart(span)
            val end = builder.getSpanEnd(span)
            builder.removeSpan(span)
            builder.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) = onClick(widget)
                },
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    return builder
}

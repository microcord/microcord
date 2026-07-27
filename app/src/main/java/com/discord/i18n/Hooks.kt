package com.discord.i18n

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.discord.utilities.spans.ClickableSpan

/**
 * One `(hookName)` target inside a translated string.
 *
 * [text] overrides the label written between the brackets, which is only needed for the strings that
 * ship an empty label (`$[](usernameHook)`) and expect the screen to supply the words as well as the
 * behaviour.
 */
data class Hook(
    val text: CharSequence? = null,
    val spans: List<Any> = emptyList(),
    val onClick: ((View) -> Unit)? = null,
)

/**
 * The hook layer on top of [I18nSpans].
 *
 * [FormatUtils] substitutes `{name}` tokens and strips `!!…!!` markers; [I18nSpans] turns `**bold**`
 * runs and `[label](target)` masked links into spans. Neither can resolve Discord's *hook* form,
 * where the target names something the screen must bind rather than a URL — `$[Learn More]
 * (learnMoreHook)`, `**$[!!{webhookName}!!](webhookNameHook)**`. This adds exactly that layer and
 * delegates everything else.
 *
 * This is the single implementation. Three independently-written ones previously existed
 * (`ChatRowText` in the chat rows, `CharSequence.withClickableHook` in the AutoMod rows, and
 * contact sync's "Learn More" renderer); they are all folded in here, which is where the reference
 * keeps the equivalent (`RenderContext`/`Hook` in its own `com.discord.i18n`).
 *
 * It is for Discord's own copy only — user-authored message content goes through
 * [com.discord.utilities.textprocessing.DiscordParser], never through here.
 */
object I18nHooks {

    /** As [I18nSpans]' masked-link pattern, but allowing the empty label and the `$` prefix. */
    private val HOOK = Regex("""\$?\[([^\[\]]*)]\(([^()\s]*)\)""")

    /** A hook wrapped in a bold run, e.g. `**$[!!{webhookName}!!](webhookNameHook)**`. */
    private val BOLD_HOOK = Regex("""\*\*(\$?\[[^\[\]]*]\(([^()\s]*)\))\*\*""")

    /** Formats [stringRes], substituting [args] and binding [hooks]. */
    @JvmStatic
    @JvmOverloads
    fun format(
        context: Context,
        @StringRes stringRes: Int,
        args: Map<String, Any?> = emptyMap(),
        hooks: Map<String, Hook> = emptyMap(),
    ): CharSequence = format(context.getString(stringRes), args, hooks)

    /** As [format], for a template that has already been resolved to text. */
    @JvmStatic
    @JvmOverloads
    fun format(
        template: CharSequence,
        args: Map<String, Any?> = emptyMap(),
        hooks: Map<String, Hook> = emptyMap(),
    ): CharSequence {
        val boldHooks = mutableSetOf<String>()
        val filled = fillHookLabels(template.toString(), hooks, boldHooks)
        val rendered = I18nSpans.render(filled, *args.map { it.key to it.value }.toTypedArray())
        return bindHooks(rendered, hooks, boldHooks)
    }

    /** Discord's plurals use `{count}` or the bare `{#}` token for the quantity. */
    @JvmStatic
    fun plural(context: Context, @PluralsRes pluralRes: Int, quantity: Int): String =
        FormatUtils.format(
            context.resources.getQuantityString(pluralRes, quantity),
            mapOf("count" to quantity, "#" to quantity),
        )

    /**
     * Rewrites every hook into the plain `[label](hookName)` form [I18nSpans] understands: an empty
     * label is filled from the hook, and a hook with neither a label nor a text disappears entirely
     * rather than leaving stray brackets in the sentence.
     *
     * A bold run wrapped *around* a hook is unwrapped and its name collected into [boldHooks], with
     * the bold re-applied in [bindHooks]. That is no longer strictly required — [I18nSpans] now
     * preserves nested spans — but it keeps the bold anchored to the hook's final range even when a
     * substituted argument changes the run's length, so it is retained.
     */
    private fun fillHookLabels(
        template: String,
        hooks: Map<String, Hook>,
        boldHooks: MutableSet<String>,
    ): String {
        val unwrapped = BOLD_HOOK.replace(template) { match ->
            val hookName = match.groupValues[2]
            if (!hooks.containsKey(hookName)) return@replace match.value
            boldHooks.add(hookName)
            match.groupValues[1]
        }
        return HOOK.replace(unwrapped) { match ->
            val hookName = match.groupValues[2]
            val label = hooks[hookName]?.text?.toString()?.takeIf(String::isNotEmpty)
                ?: match.groupValues[1]
            if (label.isEmpty()) "" else "[$label]($hookName)"
        }
    }

    /**
     * Swaps the [URLSpan]s [I18nSpans] produced for hook targets with what the caller actually
     * wants. A target that is a real url is left alone, so a string mixing both still works.
     */
    private fun bindHooks(
        rendered: CharSequence,
        hooks: Map<String, Hook>,
        boldHooks: Set<String>,
    ): CharSequence {
        if (hooks.isEmpty()) return rendered
        val builder = SpannableStringBuilder(rendered)
        builder.getSpans(0, builder.length, URLSpan::class.java).forEach { span ->
            val hook = hooks[span.url] ?: return@forEach
            val start = builder.getSpanStart(span)
            val end = builder.getSpanEnd(span)
            builder.removeSpan(span)
            if (span.url in boldHooks) {
                builder.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            hook.spans.forEach { builder.setSpan(it, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
            hook.onClick?.let { onClick ->
                builder.setSpan(
                    ClickableSpan(null, underline = false, onClickListener = onClick),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return builder
    }
}

/**
 * Makes the run that [I18nSpans] rendered as a link to [hookName] tappable instead.
 *
 * [I18nSpans] renders every `[label](target)` as a [URLSpan] because most of them really are links;
 * a hook target instead names something the screen binds. Swapping the span keeps the string's
 * wording and word order authoritative rather than splitting the sentence up in code.
 *
 * @param color the link colour, or `null` to inherit the surrounding text colour.
 */
fun CharSequence.withClickableHook(
    hookName: String,
    @ColorInt color: Int? = null,
    onClick: () -> Unit,
): CharSequence {
    val builder = SpannableStringBuilder(this)
    builder.getSpans(0, builder.length, URLSpan::class.java)
        .filter { it.url == hookName }
        .forEach { span ->
            val start = builder.getSpanStart(span)
            val end = builder.getSpanEnd(span)
            builder.removeSpan(span)
            builder.setSpan(
                ClickableSpan(color, false) { onClick() },
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    return builder
}

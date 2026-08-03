package com.discord.utilities.textprocessing.node

import android.text.SpannableStringBuilder
import android.text.Spanned
import com.discord.simpleast.core.node.Node
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.spans.ClickableSpan

/** A serialized application-command mention such as `</weather now:123>`. */
class CommandMentionNode<T : CommandMentionNode.RenderContext>(
    val commandName: String,
    val commandId: Long,
) : Node<T>() {
    interface RenderContext : BasicRenderContext {
        val commandMentionOnClick: ((Long, String) -> Unit)?
    }

    override fun render(builder: SpannableStringBuilder, renderContext: T) {
        val start = builder.length
        builder.append('/').append(commandName)
        renderContext.commandMentionOnClick?.let { click ->
            builder.setSpan(
                ClickableSpan(
                    ColorCompat.getThemedColor(renderContext.context, com.discord.R.attr.colorTextLink),
                    false,
                ) { click(commandId, commandName) },
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }
}

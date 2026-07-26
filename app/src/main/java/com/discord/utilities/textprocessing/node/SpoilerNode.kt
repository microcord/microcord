package com.discord.utilities.textprocessing.node

import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.simpleast.core.node.Node
import com.discord.utilities.spans.ClickableSpan
import com.discord.utilities.textprocessing.spans.SpoilerSpan

/**
 * `||hidden||` spoiler.
 *
 * While hidden the node strips every clickable span produced by its children so a link inside a
 * spoiler cannot be followed before the spoiler is revealed, and installs its own click span that
 * reveals it. [id] is the index assigned during preprocessing and is what the message state store
 * persists when the user reveals a spoiler.
 */
class SpoilerNode<T : SpoilerNode.RenderContext>(
    val content: String,
) : Node<T>(), Spoilerable {

    interface RenderContext : BasicRenderContext {
        val spoilerColorRes: Int
        val spoilerRevealedColorRes: Int
        val spoilerOnClick: ((SpoilerNode<*>) -> Unit)?
    }

    var id: Int = UNASSIGNED_ID
        private set

    override var isRevealed: Boolean = false

    fun updateState(id: Int, isRevealed: Boolean) {
        this.id = id
        this.isRevealed = isRevealed
    }

    override fun render(builder: SpannableStringBuilder, renderContext: T) {
        val start = builder.length
        getChildren()?.forEach { it.render(builder, renderContext) }

        if (!isRevealed) {
            builder.getSpans(start, builder.length, ClickableSpan::class.java)
                .forEach { builder.removeSpan(it) }
        }

        createStyles(renderContext).forEach {
            builder.setSpan(it, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun createStyles(renderContext: T): List<Any> {
        val spoilerSpan = SpoilerSpan(
            renderContext.spoilerColorRes,
            renderContext.spoilerRevealedColorRes,
            isRevealed,
        )
        if (isRevealed) return listOf(spoilerSpan)

        val onClick = renderContext.spoilerOnClick ?: return listOf(spoilerSpan)
        val revealSpan = ClickableSpan(
            ContextCompat.getColor(renderContext.context, R.color.transparent),
            false,
        ) {
            // The handler persists the reveal in the message state store, which re-renders the row.
            onClick(this)
        }
        return listOf(spoilerSpan, revealSpan)
    }

    companion object {
        const val UNASSIGNED_ID = -1
    }
}

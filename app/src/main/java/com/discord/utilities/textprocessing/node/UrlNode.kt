package com.discord.utilities.textprocessing.node

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.annotation.AttrRes
import com.discord.simpleast.core.node.Node
import com.discord.utilities.spans.ClickableSpan
import com.discord.utilities.textprocessing.themedColor
import com.discord.utilities.textprocessing.toPunyCodeAsciiUrl

/**
 * A bare `https://...` link, a `<https://...>` no-embed link, or a `[label](https://...)` masked
 * link, in which case [mask] holds the label that is shown instead of the URL.
 *
 * The URL is always converted to its punycode ASCII form before it is shown or opened so a
 * homograph host cannot masquerade as a different one. If that conversion fails the raw text is
 * appended unlinked rather than making a broken link tappable.
 */
class UrlNode<T : UrlNode.RenderContext>(
    val url: String,
    private val mask: String? = null,
) : Node<T>() {

    interface RenderContext : BasicRenderContext {
        @get:AttrRes
        val linkColorAttrResId: Int
        val onClickUrl: (Context, String, String?) -> Unit
        val onLongPressUrl: (String) -> Unit
    }

    override fun render(builder: SpannableStringBuilder, renderContext: T) {
        val safeUrl = try {
            toPunyCodeAsciiUrl(url)
        } catch (failure: Exception) {
            builder.append(url)
            return
        }

        val style = ClickableSpan(
            themedColor(renderContext.context, renderContext.linkColorAttrResId),
            false,
            onLongPress = { renderContext.onLongPressUrl(safeUrl) },
        ) { view -> renderContext.onClickUrl(view.context, safeUrl, mask) }

        val start = builder.length
        builder.append(mask ?: safeUrl)
        builder.setSpan(style, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

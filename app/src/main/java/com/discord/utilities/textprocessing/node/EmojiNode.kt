package com.discord.utilities.textprocessing.node

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.core.content.res.ResourcesCompat
import com.facebook.widget.text.span.BetterImageSpan
import com.discord.R
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.simpleast.core.node.TextNode
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.setVitoSpan
import com.discord.utilities.spans.ClickableSpan
import com.discord.utilities.textprocessing.EmojiImages
import com.discord.utilities.textprocessing.Rules
import com.discord.utilities.textprocessing.dpToPixels
import com.facebook.drawee.drawable.ScalingUtils
import com.facebook.drawee.span.DraweeSpanStringBuilder
import java.io.Serializable

/**
 * A unicode or custom emoji.
 *
 * The node keeps its textual form (`:name:` for unicode, the emoji name for custom emoji) as its
 * content so accessibility and copy still produce readable text, and paints the image on top of it
 * with a Drawee span. When the message consists only of emoji the preprocessor marks the node
 * [isJumbo] and it renders three times larger.
 */
class EmojiNode<T : EmojiNode.RenderContext>(
    emojiName: String,
    private val urlProvider: (isAnimationEnabled: Boolean, sizePx: Int, context: Context) -> String,
    private val emojiIdAndType: EmojiIdAndType,
    private val width: Int = EMOJI_SIZE,
    private val height: Int = EMOJI_SIZE,
) : TextNode<T>(emojiName), Spoilerable {

    interface RenderContext {
        val context: Context

        /** Mirrors the user's "automatically play GIFs / animated emoji" setting. */
        val isAnimationEnabled: Boolean

        fun onEmojiClicked(emojiIdAndType: EmojiIdAndType) = Unit
    }

    /** Identifies the emoji well enough to open its detail sheet. */
    sealed class EmojiIdAndType : Serializable {
        data class Custom(val id: Long, val isAnimated: Boolean, val name: String) : EmojiIdAndType()
        data class Unicode(val name: String) : EmojiIdAndType()
    }

    var isJumbo: Boolean = false

    override var isRevealed: Boolean = true

    override fun render(builder: SpannableStringBuilder, renderContext: T) {
        val context = renderContext.context
        val start = builder.length
        builder.append(content)

        val scale = if (isJumbo) JUMBOIFY_SCALE_FACTOR else 1
        val renderWidth = width * scale
        val renderHeight = height * scale
        val alignment = if (isJumbo) ALIGN_BASELINE else ALIGN_CENTER
        val end = builder.length - 1

        val draweeBuilder = builder as? DraweeSpanStringBuilder
        if (draweeBuilder != null) {
            val url = urlProvider(
                renderContext.isAnimationEnabled,
                IconUtils.getMediaProxySize(renderWidth),
                context,
            )
            val overlay = if (isRevealed) null else ColorDrawable(
                (renderContext as? SpoilerNode.RenderContext)?.spoilerColorRes
                    ?: ColorCompat.getThemedColor(context, R.attr.theme_chat_spoiler_bg),
            )
            val resId = if (url.startsWith("res:///")) url.removePrefix("res:///").toIntOrNull() ?: 0 else 0
            if (resId != 0) {
                val drawable = ResourcesCompat.getDrawable(context.resources, resId, null)?.mutate()
                if (drawable != null) {
                    drawable.setBounds(0, 0, renderWidth, renderHeight)
                    builder.setSpan(BetterImageSpan(drawable, alignment), start, end + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else {
                draweeBuilder.setVitoSpan(
                    context = context,
                    url = url,
                    start = start,
                    end = end,
                    width = renderWidth,
                    height = renderHeight,
                    alignment = alignment,
                    autoPlay = isRevealed,
                    overlay = overlay,
                    scaleType = ScalingUtils.ScaleType.FIT_CENTER,
                )
            }
        }

        if (content.isEmpty() || start > end) return
        val clickableSpan = ClickableSpan(ColorCompat.getThemedColor(context, R.attr.color_brand), false) {
            renderContext.onEmojiClicked(emojiIdAndType)
        }
        builder.setSpan(clickableSpan, start, end + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    override fun equals(other: Any?): Boolean =
        other is EmojiNode<*> &&
            other.content == content &&
            other.width == width &&
            other.height == height &&
            other.isJumbo == isJumbo

    override fun hashCode(): Int {
        var result = content.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        return 31 * result + isJumbo.hashCode()
    }

    companion object {
        /** Base emoji edge length; a jumbo emoji is [JUMBOIFY_SCALE_FACTOR] times this. */
        val EMOJI_SIZE: Int get() = dpToPixels(EMOJI_SIZE_DP)

        private const val EMOJI_SIZE_DP = 16
        private const val JUMBOIFY_SCALE_FACTOR = 3

        /** `BetterImageSpan` alignments used by `DraweeSpanStringBuilder`. */
        private const val ALIGN_BASELINE = 1
        private const val ALIGN_CENTER = 2

        fun <T : RenderContext> from(unicodeEmoji: ModelEmojiUnicode, size: Int = EMOJI_SIZE): EmojiNode<T> {
            val codePoints = unicodeEmoji.codePoints
            return EmojiNode(
                unicodeEmoji.firstName,
                { _, _, context -> ModelEmojiUnicode.getImageUri(codePoints, context) },
                generateEmojiIdAndType(unicodeEmoji),
                size,
                size,
            )
        }

        fun <T : RenderContext> from(
            name: String,
            emojiId: Long,
            isAnimated: Boolean,
            size: Int = EMOJI_SIZE,
        ): EmojiNode<T> = from(name, emojiId, isAnimated, size, EmojiIdAndType.Custom(emojiId, isAnimated, name))

        /**
         * Rebuilds a node from a stored identity.
         *
         * Returns null for a unicode emoji whose name is not in the installed catalog, which is
         * the only case where the caller has nothing to draw.
         */
        fun <T : RenderContext> from(size: Int = EMOJI_SIZE, emojiIdAndType: EmojiIdAndType): EmojiNode<T>? =
            when (emojiIdAndType) {
                is EmojiIdAndType.Custom ->
                    from(emojiIdAndType.name, emojiIdAndType.id, emojiIdAndType.isAnimated, size, emojiIdAndType)
                is EmojiIdAndType.Unicode ->
                    Rules.unicodeEmojiByName(emojiIdAndType.name)?.let { from<T>(it, size) }
            }

        fun generateEmojiIdAndType(unicodeEmoji: ModelEmojiUnicode) =
            EmojiIdAndType.Unicode(unicodeEmoji.firstName)

        private fun <T : RenderContext> from(
            name: String,
            emojiId: Long,
            isAnimated: Boolean,
            size: Int,
            emojiIdAndType: EmojiIdAndType,
        ): EmojiNode<T> = EmojiNode(
            name,
            { isAnimationEnabled, sizePx, _ ->
                EmojiImages.customEmojiUrl(emojiId, isAnimated && isAnimationEnabled, sizePx)
            },
            emojiIdAndType,
            size,
            size,
        )
    }
}

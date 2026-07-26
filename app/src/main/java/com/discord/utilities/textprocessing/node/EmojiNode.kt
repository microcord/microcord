package com.discord.utilities.textprocessing.node

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import com.discord.R
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.simpleast.core.node.TextNode
import com.discord.utilities.spans.ClickableSpan
import com.discord.utilities.textprocessing.EmojiImages
import com.discord.utilities.textprocessing.dpToPixels
import com.discord.utilities.textprocessing.themedColor
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.drawee.drawable.ScalingUtils
import com.facebook.drawee.generic.GenericDraweeHierarchyBuilder
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.facebook.imagepipeline.request.ImageRequestBuilder
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
                EmojiImages.mediaProxySize(renderWidth),
                context,
            )
            val controller = Fresco.newDraweeControllerBuilder()
                .setImageRequest(ImageRequestBuilder.newBuilderWithSource(Uri.parse(url)).build())
                .setAutoPlayAnimations(isRevealed)
                .build()
            val hierarchy = GenericDraweeHierarchyBuilder(context.resources)
                .setPlaceholderImage(ColorDrawable(TRANSPARENT))
                .setActualImageScaleType(ScalingUtils.ScaleType.FIT_CENTER)
                .apply {
                    if (!isRevealed) {
                        val spoilerColor = (renderContext as? SpoilerNode.RenderContext)?.spoilerColorRes
                            ?: themedColor(context, R.attr.theme_chat_spoiler_bg)
                        setOverlay(ColorDrawable(spoilerColor))
                    }
                }
                .build()
            draweeBuilder.setImageSpan(
                context,
                hierarchy,
                controller,
                start,
                end,
                renderWidth,
                renderHeight,
                false,
                alignment,
            )
        }

        if (content.isEmpty() || start > end) return
        val clickableSpan = ClickableSpan(themedColor(context, R.attr.color_brand), false) {
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
        private const val TRANSPARENT = 0

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

        fun <T : RenderContext> from(size: Int = EMOJI_SIZE, emojiIdAndType: EmojiIdAndType): EmojiNode<T>? =
            when (emojiIdAndType) {
                is EmojiIdAndType.Custom ->
                    from(emojiIdAndType.name, emojiIdAndType.id, emojiIdAndType.isAnimated, size, emojiIdAndType)
                is EmojiIdAndType.Unicode -> null
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

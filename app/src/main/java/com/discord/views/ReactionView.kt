package com.discord.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.ColorUtils
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextSwitcher
import android.widget.TextView
import com.discord.R
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.models.domain.emoji.toCodePoints
import com.discord.models.user.User
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.textprocessing.node.EmojiNode
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.facebook.drawee.span.DraweeSpanStringBuilder

class ReactionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    private val counterText1: TextView
    private val counterText2: TextView
    private val counter: TextSwitcher
    private val emojiText: SimpleDraweeSpanTextView
    private var count = 0
    private var messageId: Long? = null
    private var textColor: ColorStateList? = null
    private var meTextColor: ColorStateList? = null

    var reaction: MessageReaction? = null
        private set

    /** Whether this chip shows the emoji's super reactions rather than its normal ones. */
    var isBurst = false
        private set

    /** The theme's pill, put back when a recycled chip goes from a super reaction to a normal one. */
    private var normalBackground: Drawable? = null

    init {
        inflate(context, R.layout.reaction_view, this)
        counterText1 = requireNotNull(findViewById(R.id.counter_text_1))
        counterText2 = requireNotNull(findViewById(R.id.counter_text_2))
        counter = requireNotNull(findViewById(R.id.counter_text_switcher))
        emojiText = requireNotNull(findViewById(R.id.emoji_text_view))
        context.obtainStyledAttributes(attrs, R.styleable.ReactionView).run {
            try {
                textColor = getColorStateList(R.styleable.ReactionView_rv_textColor)
                meTextColor = getColorStateList(R.styleable.ReactionView_rv_meTextColor)
            } finally {
                recycle()
            }
        }
    }

    /**
     * Shows [reaction]'s normal reactions, or with [burst] its super reactions: a pill of their own,
     * tinted with the colours the server picked for the emoji, ringed when one is the user's, and
     * with a pop each time another lands.
     */
    @JvmOverloads
    fun configure(reaction: MessageReaction, messageId: Long, animateEmojis: Boolean, burst: Boolean = false) {
        val newCount = if (burst) reaction.burstCount else reaction.normalCount
        val sameChip = this.messageId == messageId && this.isBurst == burst && this.reaction?.emoji == reaction.emoji
        if (burst && sameChip && newCount > count) popEmoji()
        if (newCount != count || !sameChip) {
            if (!sameChip) {
                counter.setCurrentText(newCount.toString())
            } else {
                val increasing = newCount > count
                counter.setInAnimation(context, if (increasing) R.anim.anim_slide_in_up else R.anim.anim_slide_in_down)
                counter.setOutAnimation(context, if (increasing) R.anim.anim_slide_out_up else R.anim.anim_slide_out_down)
                counter.setText(newCount.toString())
            }
            count = newCount
        }
        if (this.reaction?.emoji != reaction.emoji) {
            renderReactionEmoji(emojiText, reaction.emoji, animateEmojis)
        }
        val isMe = if (burst) reaction.meBurst else reaction.me
        if (normalBackground == null) normalBackground = background
        val palette = if (burst) BurstPalette.of(reaction.burstColors, StoreStream.getUserSettingsSystem().isDarkTheme()) else null
        background = if (burst) burstBackground(palette, isMe) else normalBackground
        setIsMe(isMe)
        (palette?.let { ColorStateList.valueOf(it.accent) } ?: if (isMe) meTextColor else textColor)?.let {
            counterText1.setTextColor(it)
            counterText2.setTextColor(it)
        }
        this.reaction = reaction
        this.messageId = messageId
        this.isBurst = burst
    }

    /** RN's burst pill: the palette colour faintly filled, outlined only when the reaction is yours. */
    private fun burstBackground(palette: BurstPalette?, isMe: Boolean): Drawable {
        val color = palette?.background ?: ColorCompat.getThemedColor(context, R.attr.color_brand_500)
        val opacity = palette?.opacity ?: BurstPalette.DARK_OPACITY
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = BURST_CORNER_DP * density
            setColor(ColorUtils.setAlphaComponent(color, (opacity * 255).toInt()))
            if (isMe) setStroke((BURST_STROKE_DP * density).toInt().coerceAtLeast(1), color)
        }
    }

    /** The emoji swells and settles back, standing in for desktop's burst of particles. */
    private fun popEmoji() {
        emojiText.animate().cancel()
        emojiText.animate().scaleX(POP_SCALE).scaleY(POP_SCALE).setDuration(POP_UP_MS).withEndAction {
            emojiText.animate().scaleX(1f).scaleY(1f).setDuration(POP_DOWN_MS).setInterpolator(OvershootInterpolator()).start()
        }.start()
    }

    @Deprecated("Use configure", ReplaceWith("configure(reaction, messageId, animateEmojis)"))
    fun a(reaction: MessageReaction, messageId: Long, animateEmojis: Boolean) =
        configure(reaction, messageId, animateEmojis)

    /** Built on first use, so chips stay a plain emoji and count while "Who Reacted" is off. */
    private var reactors: LinearLayout? = null

    /** The first few people who reacted, after the count; tapping one calls [onClick] with their id. */
    fun setReactors(users: List<User>, onClick: (Long) -> Unit) {
        val shown = users.take(MAX_REACTORS)
        val row = reactors ?: if (shown.isEmpty()) return else LinearLayout(context).also {
            it.gravity = android.view.Gravity.CENTER_VERTICAL
            addView(it)
            reactors = it
        }
        row.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        while (row.childCount < shown.size) {
            val avatar = LayoutInflater.from(context).inflate(R.layout.reaction_user_avatar, row, false)
            // Each photo tucks under the next one; only the first is spaced off the count.
            if (row.childCount == 0) (avatar.layoutParams as MarginLayoutParams).marginStart = (4 * resources.displayMetrics.density).toInt()
            row.addView(avatar)
        }
        for (index in 0 until row.childCount) {
            val avatar = row.getChildAt(index) as VitoImageView
            val user = shown.getOrNull(index)
            avatar.visibility = if (user == null) View.GONE else View.VISIBLE
            if (user == null) continue
            IconUtils.setIcon(avatar, user, R.dimen.avatar_size_small)
            avatar.contentDescription = user.username
            avatar.setOnClickListener { onClick(user.id) }
        }
    }

    fun setMeTextColor(color: ColorStateList) {
        meTextColor = color
    }

    private fun setIsMe(isMe: Boolean) {
        isActivated = isMe
        isSelected = isMe
        counterText1.isActivated = isMe
        counterText2.isActivated = isMe
    }
}

/**
 * Draws a reaction emoji as an inline image span, exactly as message text does.
 *
 * Unicode emoji are Discord's own bundled artwork rather than the device emoji
 * font, so a reaction looks the same on every device; custom emoji come from the
 * CDN and animate only when the user allows it.
 */
internal fun renderReactionEmoji(
    view: SimpleDraweeSpanTextView,
    emoji: MessageReactionEmoji,
    animate: Boolean,
    sizePx: Int = EmojiNode.EMOJI_SIZE,
) {
    val name = emoji.name.orEmpty()
    val customId = emoji.id?.toLongOrNull()
    val node: EmojiNode<EmojiNode.RenderContext> = if (customId == null) {
        val codePoints = name.toCodePoints()
        EmojiNode(
            name,
            { _, _, context -> ModelEmojiUnicode.getImageUri(codePoints, context) },
            EmojiNode.EmojiIdAndType.Unicode(name),
            sizePx,
            sizePx,
        )
    } else {
        EmojiNode.from(name, customId, emoji.animated, sizePx)
    }
    val builder = DraweeSpanStringBuilder()
    // A hair space gives the span something to sit on without widening the pill.
    builder.append(HAIR_SPACE)
    node.render(builder, ReactionEmojiRenderContext(view.context, animate))
    view.setDraweeSpanStringBuilder(builder)
}

private const val HAIR_SPACE = '\u200A'

private const val MAX_REACTORS = 5
private const val BURST_CORNER_DP = 6f
private const val BURST_STROKE_DP = 1f
private const val POP_SCALE = 1.6f
private const val POP_UP_MS = 140L
private const val POP_DOWN_MS = 260L

/**
 * The colours a super reaction pill is drawn with, as RN derives them from the server's burst
 * colours (`buildPlatformedThemedEmojiColorPalette`): the most vivid of the first three, made to
 * stand off the theme's surface; a stronger version of it for the count.
 */
internal data class BurstPalette(val background: Int, val accent: Int, val opacity: Float) {
    companion object {
        const val DARK_OPACITY = 0.2f
        private const val LIGHT_OPACITY = 0.1f
        private const val DARK_GREY_OPACITY = 0.5f
        private const val LIGHT_GREY_OPACITY = 0.35f
        private const val GREY_SATURATION = 0.1f
        private const val LIGHTNESS_SHIFT = 0.6f
        private const val DARK_SURFACE = 0xFF36393E.toInt()

        fun of(colors: List<String>?, dark: Boolean): BurstPalette? {
            val vivid = colors.orEmpty().take(3)
                .mapNotNull { runCatching { Color.parseColor(it) }.getOrNull() }
                .maxByOrNull { FloatArray(3).also { hsv -> Color.colorToHSV(it, hsv) }.let { hsv -> hsv[1] + hsv[2] } }
                ?: return null
            val surface = if (dark) DARK_SURFACE else Color.WHITE
            val hsl = FloatArray(3).also { ColorUtils.colorToHSL(vivid, it) }
            // Light themes start from a lighter shade, dark ones from a darker, then contrast decides.
            val start = hsl.copyOf().apply {
                this[2] = if (dark) (this[2] - LIGHTNESS_SHIFT).takeIf { it >= 0f } ?: 0.1f
                else (this[2] + LIGHTNESS_SHIFT).takeIf { it <= 1f } ?: 0.9f
            }
            val grey = hsl[1] < GREY_SATURATION
            return BurstPalette(
                background = contrasting(ColorUtils.HSLToColor(start), surface, if (dark) 5.0 else 3.0),
                accent = contrasting(vivid, surface, 7.0),
                opacity = if (dark) (if (grey) DARK_GREY_OPACITY else DARK_OPACITY) else if (grey) LIGHT_GREY_OPACITY else LIGHT_OPACITY,
            )
        }

        /** Steps [color]'s lightness away from [surface] until it reaches [ratio], ten steps at most. */
        private fun contrasting(color: Int, surface: Int, ratio: Double): Int {
            val lighten = ColorUtils.calculateLuminance(surface) <= 0.5
            val hsl = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }
            var current = color
            repeat(10) {
                if (ColorUtils.calculateContrast(current, surface) >= ratio) return current
                if (if (lighten) hsl[2] >= 0.95f else hsl[2] <= 0.05f) return current
                hsl[2] += if (lighten) 0.05f else -0.05f
                current = ColorUtils.HSLToColor(hsl)
            }
            return current
        }
    }
}

private class ReactionEmojiRenderContext(
    override val context: Context,
    override val isAnimationEnabled: Boolean,
) : EmojiNode.RenderContext

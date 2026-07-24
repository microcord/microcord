package com.discord.views

import android.content.Context
import android.content.res.ColorStateList
import android.net.Uri
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextSwitcher
import android.widget.TextView
import com.discord.R
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.drawee.generic.GenericDraweeHierarchyBuilder
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.facebook.imagepipeline.common.ResizeOptions
import com.facebook.imagepipeline.request.ImageRequestBuilder

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

    fun configure(reaction: MessageReaction, messageId: Long, animateEmojis: Boolean) {
        val newCount = reaction.count
        if (newCount != count) {
            if (this.messageId != messageId) {
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
        setIsMe(reaction.me)
        (if (reaction.me) meTextColor else textColor)?.let {
            counterText1.setTextColor(it)
            counterText2.setTextColor(it)
        }
        this.reaction = reaction
        this.messageId = messageId
    }

    @Deprecated("Use configure", ReplaceWith("configure(reaction, messageId, animateEmojis)"))
    fun a(reaction: MessageReaction, messageId: Long, animateEmojis: Boolean) =
        configure(reaction, messageId, animateEmojis)

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

internal fun renderReactionEmoji(
    view: SimpleDraweeSpanTextView,
    emoji: MessageReactionEmoji,
    animate: Boolean,
    sizePx: Int = (view.resources.displayMetrics.density * 16).toInt(),
) {
    val id = emoji.id
    if (id == null) {
        view.text = emoji.name.orEmpty()
        return
    }
    val extension = if (emoji.animated) "gif" else "png"
    val url = "https://cdn.discordapp.com/emojis/$id.$extension?size=32&quality=lossless"
    val request = ImageRequestBuilder.newBuilderWithSource(Uri.parse(url))
        .setResizeOptions(ResizeOptions(sizePx, sizePx))
        .build()
    val controller = Fresco.newDraweeControllerBuilder()
        .setImageRequest(request)
        .setAutoPlayAnimations(animate)
        .build()
    val builder = DraweeSpanStringBuilder().apply {
        append('#')
        setImageSpan(
            view.context,
            GenericDraweeHierarchyBuilder(view.resources).build(),
            controller,
            0,
            0,
            sizePx,
            sizePx,
            false,
            2,
        )
    }
    view.setDraweeSpanStringBuilder(builder)
}

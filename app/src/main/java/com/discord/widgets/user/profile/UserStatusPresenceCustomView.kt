package com.discord.widgets.user.profile

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import com.discord.R
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.models.domain.emoji.toCodePoints
import com.facebook.drawee.view.SimpleDraweeView

class UserStatusPresenceCustomView(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    data class Emoji(val id: String?, val name: String?, val isAnimated: Boolean)

    sealed class ViewState {
        data class WithPlaceholder(val placeholderEmoji: ModelEmojiUnicode) : ViewState()
        data class WithStatus(val emoji: Emoji?, val statusText: String?) : ViewState()
    }

    private val clear: AppCompatImageView
    private val emoji: SimpleDraweeView
    private val text: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_user_status_presence_custom, this)
        clear = requireNotNull(findViewById(R.id.user_status_presence_custom_clear))
        emoji = requireNotNull(findViewById(R.id.user_status_presence_custom_emoji))
        text = requireNotNull(findViewById(R.id.user_status_presence_custom_text))
    }

    fun setOnClear(callback: () -> Unit) = clear.setOnClickListener { callback() }

    fun updateViewState(viewState: ViewState) = when (viewState) {
        is ViewState.WithStatus -> {
            configureStatusEmoji(viewState.emoji)
            text.text = viewState.statusText
            clear.visibility = View.VISIBLE
        }
        is ViewState.WithPlaceholder -> {
            emoji.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            emoji.setImageURI(Uri.parse(ModelEmojiUnicode.getImageUri(viewState.placeholderEmoji.codePoints, context)))
            emoji.visibility = View.VISIBLE
            text.setText(R.string.custom_status_set_custom_status)
            clear.visibility = View.GONE
        }
    }

    private fun configureStatusEmoji(value: Emoji?) {
        emoji.clearColorFilter()
        val url = when {
            value?.id != null -> {
                val extension = if (value.isAnimated) "gif" else "webp"
                "https://cdn.discordapp.com/emojis/${value.id}.$extension?size=32"
            }
            value?.name != null -> ModelEmojiUnicode.getImageUri(
                value.name.toCodePoints(),
                context,
            )
            else -> null
        }
        emoji.setImageURI(url)
        emoji.visibility = if (url == null) View.GONE else View.VISIBLE
    }
}

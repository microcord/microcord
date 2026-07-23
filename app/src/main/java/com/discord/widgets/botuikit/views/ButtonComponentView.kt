package com.discord.widgets.botuikit.views

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.appcompat.widget.AppCompatImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.discord.R
import com.discord.api.botuikit.ButtonStyle
import com.discord.api.botuikit.ComponentType
import com.discord.models.botuikit.ActionInteractionComponentState
import com.discord.models.botuikit.ButtonMessageComponent
import com.discord.views.typing.TypingDots
import com.discord.widgets.botuikit.ComponentProvider
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView

class ButtonComponentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : ConstraintLayout(context, attrs, defStyle), ComponentView<ButtonMessageComponent> {
    override fun configure(
        component: ButtonMessageComponent,
        componentProvider: ComponentProvider,
        componentActionListener: ComponentActionListener,
    ) {
        val button = requireNotNull(findViewById<MaterialButton>(R.id.button))
        val emoji = requireNotNull(findViewById<SimpleDraweeView>(R.id.emoji))
        val label = requireNotNull(findViewById<MaterialTextView>(R.id.label))
        val linkIcon = requireNotNull(findViewById<AppCompatImageView>(R.id.link_icon))
        val loadingDots = requireNotNull(findViewById<TypingDots>(R.id.loading_dots))
        val loading = component.stateInteraction is ActionInteractionComponentState.Loading
        val enabled = component.stateInteraction !is ActionInteractionComponentState.Disabled

        configureStyle(button, component.style)
        if (component.emoji != null && loading) emoji.visibility = View.INVISIBLE
        else emoji.setEmojiOrHide(component.emoji, component.emojiAnimationsEnabled)
        linkIcon.visibility = if (component.style == ButtonStyle.LINK) View.VISIBLE else View.GONE
        val (start, end) = buttonLabelPaddingDp(component.label != null, component.emoji != null, component.style == ButtonStyle.LINK)
        label.setPadding(start.dp, 0, end.dp, 0)
        label.text = component.label
        label.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        label.setEnabledAlpha(enabled)
        emoji.setEnabledAlpha(enabled)
        linkIcon.setEnabledAlpha(enabled)
        button.isEnabled = enabled
        button.setOnClickListener {
            val url = component.url
            if (url != null) openWebUrl(url)
            else component.customId?.let { componentActionListener.onButtonComponentClick(component.index, it) }
        }
        loadingDots.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) loadingDots.start() else loadingDots.stop()
        button.isClickable = !loading
    }

    fun configureStyle(button: Button, style: ButtonStyle) {
        val color = when (style) {
            ButtonStyle.PRIMARY -> R.color.brand_500
            ButtonStyle.SUCCESS -> R.color.status_green_600
            ButtonStyle.DANGER -> R.color.status_red_500
            ButtonStyle.UNKNOWN, ButtonStyle.SECONDARY, ButtonStyle.LINK -> R.color.primary_dark_500
        }
        ViewCompat.setBackgroundTintList(button, ColorStateList.valueOf(ContextCompat.getColor(context, color)))
    }

    override fun type() = ComponentType.BUTTON

    private fun openWebUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") return
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        fun inflateComponent(context: Context, root: ViewGroup): ButtonComponentView =
            LayoutInflater.from(context).inflate(
                R.layout.widget_chat_list_bot_ui_button_component,
                root,
                false,
            ) as ButtonComponentView
    }
}

internal fun buttonLabelPaddingDp(hasLabel: Boolean, hasEmoji: Boolean, hasIcon: Boolean): Pair<Int, Int> =
    if (!hasLabel) 0 to if (hasEmoji && hasIcon) 8 else 16
    else (if (hasEmoji) 8 else 16) to (if (hasIcon) 8 else 16)

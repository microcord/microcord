package com.discord.widgets.botuikit.views.select

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R
import com.discord.api.botuikit.ComponentType
import com.discord.models.botuikit.ActionInteractionComponentState
import com.discord.models.botuikit.SelectMessageComponent
import com.discord.views.typing.TypingDots
import com.discord.widgets.botuikit.ComponentProvider
import com.discord.widgets.botuikit.views.ComponentActionListener
import com.discord.widgets.botuikit.views.ComponentView
import com.discord.widgets.botuikit.views.setEmojiOrHide
import com.discord.widgets.botuikit.views.setEnabledAlpha
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.textview.MaterialTextView

class SelectComponentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : ConstraintLayout(context, attrs, defStyle), ComponentView<SelectMessageComponent> {
    override fun configure(
        component: SelectMessageComponent,
        componentProvider: ComponentProvider,
        componentActionListener: ComponentActionListener,
    ) {
        val selectionRoot = requireNotNull(findViewById<FlexboxLayout>(R.id.select_component_selections_root))
        val icon = requireNotNull(findViewById<SimpleDraweeView>(R.id.select_component_selection_icon))
        val text = requireNotNull(findViewById<MaterialTextView>(R.id.select_component_selection_text))
        val chevron = requireNotNull(findViewById<ImageView>(R.id.select_component_chevron))
        val loadingDots = requireNotNull(findViewById<TypingDots>(R.id.select_component_loading))
        val placeholder = component.placeholder ?: resources.getString(R.string.message_select_component_default_placeholder)

        when {
            component.selectedOptions.isEmpty() -> {
                text.visibility = View.VISIBLE
                selectionRoot.visibility = View.GONE
                icon.visibility = View.GONE
                text.setTextColor(themedColor(R.attr.colorInteractiveMuted))
                text.text = placeholder
            }
            component.maxValues == 1 -> {
                val selected = component.selectedOptions.first()
                text.visibility = View.VISIBLE
                selectionRoot.visibility = View.GONE
                text.setTextColor(themedColor(R.attr.colorInteractiveNormal))
                text.text = selected.label
                icon.setEmojiOrHide(selected.emoji, component.emojiAnimationsEnabled)
            }
            else -> {
                text.visibility = View.GONE
                icon.visibility = View.GONE
                selectionRoot.visibility = View.VISIBLE
                selectionRoot.removeAllViews()
                val inflater = LayoutInflater.from(context)
                component.selectedOptions.forEach { selected ->
                    val pill = inflater.inflate(
                        R.layout.widget_chat_list_bot_ui_select_component_pill,
                        selectionRoot,
                        false,
                    ) as MaterialTextView
                    pill.text = selected.label
                    selectionRoot.addView(pill)
                }
            }
        }

        val enabled = component.stateInteraction !is ActionInteractionComponentState.Disabled
        val loading = component.stateInteraction is ActionInteractionComponentState.Loading
        if (loading) loadingDots.start() else loadingDots.stop()
        loadingDots.visibility = if (loading) View.VISIBLE else View.INVISIBLE
        chevron.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        setEnabledAlpha(enabled, 0.3f)
        setOnClickListener {
            componentActionListener.onSelectComponentClick(
                component.index,
                component.customId,
                placeholder,
                component.options,
                component.selectedOptions,
                component.minValues,
                component.maxValues,
                component.emojiAnimationsEnabled,
            )
        }
        isClickable = enabled && !loading
    }

    override fun type() = ComponentType.SELECT

    private fun themedColor(attribute: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attribute, value, true)
        return value.data
    }

    companion object {
        fun inflateComponent(context: Context, root: ViewGroup): SelectComponentView =
            LayoutInflater.from(context).inflate(
                R.layout.widget_chat_list_bot_ui_select_component,
                root,
                false,
            ) as SelectComponentView
    }
}

package com.discord.widgets.botuikit.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.discord.R
import com.discord.api.botuikit.ComponentType
import com.discord.models.botuikit.ActionRowMessageComponent
import com.discord.models.botuikit.childError
import com.discord.widgets.botuikit.ComponentProvider
import com.discord.widgets.botuikit.replaceViews
import com.google.android.flexbox.FlexboxLayout

class ActionRowComponentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : LinearLayout(context, attrs, defStyle), ComponentView<ActionRowMessageComponent> {
    override fun configure(
        component: ActionRowMessageComponent,
        componentProvider: ComponentProvider,
        componentActionListener: ComponentActionListener,
    ) {
        val group = requireNotNull(findViewById<FlexboxLayout>(R.id.action_row_component_view_group))
        group.replaceViews(component.components.mapIndexedNotNull { index, child ->
            componentProvider.getConfiguredComponentView(componentActionListener, child, group, index) as? View
        })
        val error = component.childError()
        val errorRow = requireNotNull(findViewById<View>(R.id.action_row_component_view_group_error_row))
        errorRow.visibility = if (error == null) View.GONE else View.VISIBLE
        if (error != null) {
            requireNotNull(errorRow.findViewById<TextView>(R.id.view_interaction_failed_label_message)).text =
                error.errorMessage ?: resources.getString(R.string.application_command_failed)
        }
    }

    override fun type() = ComponentType.ACTION_ROW

    companion object {
        fun inflateComponent(context: Context, root: ViewGroup): ActionRowComponentView =
            LayoutInflater.from(context).inflate(
                R.layout.widget_chat_list_bot_ui_action_row_component,
                root,
                false,
            ) as ActionRowComponentView
    }
}

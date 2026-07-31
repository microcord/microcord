@file:JvmName("WidgetChatListAdapterItemBotComponentRowKt")

package com.discord.widgets.chat.list.adapter

import android.view.View
import android.view.ViewGroup
import com.discord.models.botuikit.MessageComponent
import com.discord.widgets.botuikit.views.ComponentView

fun ViewGroup.replaceViews(views: List<ComponentView<out MessageComponent>>) {
    views.forEachIndexed { index, componentView ->
        val view = componentView as View
        if (index < childCount) {
            if (getChildAt(index) !== view) {
                removeViewAt(index)
                addView(view, index)
            }
        } else {
            addView(view, index)
        }
    }
    if (views.size < childCount) removeViews(views.size, childCount - views.size)
}

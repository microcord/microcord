package com.discord.widgets.botuikit

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.discord.models.botuikit.*
import com.discord.widgets.botuikit.views.ActionRowComponentView
import com.discord.widgets.botuikit.views.ButtonComponentView
import com.discord.widgets.botuikit.views.ComponentActionListener
import com.discord.widgets.botuikit.views.ComponentView
import com.discord.widgets.botuikit.views.select.SelectComponentView
import com.discord.widgets.botuikit.views.v2.*

class ComponentProvider(val context: Context) {
    fun getConfiguredComponentView(
        listener: ComponentActionListener,
        component: MessageComponent,
        root: ViewGroup,
        childIndex: Int,
    ): ComponentView<out MessageComponent>? {
        val reusable = root.getChildAt(childIndex) as? ComponentView<*>
        val view = if (reusable?.type() == component.type) {
            reusable
        } else {
            ComponentInflater.from(context).inflateComponent(component.type, root)
        }
        configure(view, component, listener)
        return view
    }

    private fun configure(
        view: ComponentView<*>?,
        component: MessageComponent,
        listener: ComponentActionListener,
    ) {
        when {
            view is ActionRowComponentView && component is ActionRowMessageComponent -> view.configure(component, this, listener)
            view is ButtonComponentView && component is ButtonMessageComponent -> view.configure(component, this, listener)
            view is SelectComponentView && component is SelectMessageComponent -> view.configure(component, this, listener)
            view is SectionComponentView && component is SectionMessageComponent -> view.configure(component, this, listener)
            view is TextDisplayComponentView && component is TextDisplayMessageComponent -> view.configure(component, this, listener)
            view is ThumbnailComponentView && component is ThumbnailMessageComponent -> view.configure(component, this, listener)
            view is MediaGalleryComponentView && component is MediaGalleryMessageComponent -> view.configure(component, this, listener)
            view is FileComponentView && component is FileMessageComponent -> view.configure(component, this, listener)
            view is SeparatorComponentView && component is SeparatorMessageComponent -> view.configure(component, this, listener)
            view is ContainerComponentView && component is ContainerMessageComponent -> view.configure(component, this, listener)
        }
    }
}

internal fun ViewGroup.replaceViews(views: List<View>) {
    views.forEachIndexed { index, view ->
        if (view.parent === this && indexOfChild(view) == index) return@forEachIndexed
        (view.parent as? ViewGroup)?.removeView(view)
        addView(view, index)
    }
    while (childCount > views.size) removeViewAt(childCount - 1)
}

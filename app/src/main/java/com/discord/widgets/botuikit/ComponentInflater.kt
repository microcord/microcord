package com.discord.widgets.botuikit

import android.content.Context
import android.view.ViewGroup
import com.discord.api.botuikit.ComponentType
import com.discord.models.botuikit.MessageComponent
import com.discord.widgets.botuikit.views.ActionRowComponentView
import com.discord.widgets.botuikit.views.ButtonComponentView
import com.discord.widgets.botuikit.views.ComponentView
import com.discord.widgets.botuikit.views.select.SelectComponentView
import com.discord.widgets.botuikit.views.v2.ContainerComponentView
import com.discord.widgets.botuikit.views.v2.FileComponentView
import com.discord.widgets.botuikit.views.v2.MediaGalleryComponentView
import com.discord.widgets.botuikit.views.v2.SectionComponentView
import com.discord.widgets.botuikit.views.v2.SeparatorComponentView
import com.discord.widgets.botuikit.views.v2.TextDisplayComponentView
import com.discord.widgets.botuikit.views.v2.ThumbnailComponentView

class ComponentInflater(val context: Context) {
    fun inflateComponent(
        component: ComponentType,
        root: ViewGroup,
    ): ComponentView<out MessageComponent>? = when (component) {
        ComponentType.ACTION_ROW -> ActionRowComponentView.inflateComponent(context, root)
        ComponentType.BUTTON -> ButtonComponentView.inflateComponent(context, root)
        ComponentType.SELECT, ComponentType.USER_SELECT, ComponentType.ROLE_SELECT,
        ComponentType.MENTIONABLE_SELECT, ComponentType.CHANNEL_SELECT,
        -> SelectComponentView.inflateComponent(context, root)
        ComponentType.SECTION -> SectionComponentView.inflate(context, root)
        ComponentType.TEXT_DISPLAY -> TextDisplayComponentView.inflate(context, root)
        ComponentType.THUMBNAIL -> ThumbnailComponentView.inflate(context, root)
        ComponentType.MEDIA_GALLERY -> MediaGalleryComponentView.inflate(context, root)
        ComponentType.FILE -> FileComponentView.inflate(context, root)
        ComponentType.SEPARATOR -> SeparatorComponentView.inflate(context, root)
        ComponentType.CONTAINER -> ContainerComponentView.inflate(context, root)
        ComponentType.TEXT, ComponentType.LABEL, ComponentType.FILE_UPLOAD,
        ComponentType.RADIO_GROUP, ComponentType.CHECKBOX_GROUP, ComponentType.CHECKBOX,
        ComponentType.UNKNOWN,
        -> null
    }

    companion object {
        fun from(context: Context) = ComponentInflater(context)
    }
}

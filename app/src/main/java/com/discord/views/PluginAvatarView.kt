package com.discord.views

import android.content.Context
import android.util.AttributeSet
import com.facebook.drawee.view.SimpleDraweeView

/** Host-owned Fresco view for plugin layouts. */
class PluginAvatarView : SimpleDraweeView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
}

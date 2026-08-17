package com.discord.views

import android.content.Context
import android.util.AttributeSet

/** Host-owned image view for plugin layouts. Loads through Vito. */
class PluginAvatarView : VitoImageView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
}

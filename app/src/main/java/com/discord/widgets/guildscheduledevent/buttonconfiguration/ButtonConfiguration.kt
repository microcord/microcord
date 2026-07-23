package com.discord.widgets.guildscheduledevent.buttonconfiguration

import android.content.Context
import android.view.View

interface ButtonConfiguration {
    val extrasButtonOnClickListener: View.OnClickListener?
    val primaryButtonOnClickListener: View.OnClickListener?
    val primaryButtonVisible: Boolean
    val secondaryButtonBackground: Int
    val secondaryButtonEnabled: Boolean
    val secondaryButtonOnClickListener: View.OnClickListener?
    val secondaryButtonTextDrawableRes: Int
    val secondaryButtonVisible: Boolean
    val shareButtonOnClickListener: View.OnClickListener?
    val isAnyButtonVisible: Boolean
    val isExtrasVisible: Boolean
    val isShareVisible: Boolean

    fun primaryButtonText(context: Context): CharSequence
    fun secondaryButtonText(context: Context): CharSequence
    fun secondaryButtonTextColor(context: Context): Int
}

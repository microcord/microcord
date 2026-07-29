package com.discord.widgets.chat.input.autocomplete.adapter

import android.view.View
import com.discord.widgets.chat.input.autocomplete.ApplicationPlaceholder

interface StickyHeaderHolder {
    fun getItemView(): View
    fun bind(item: ApplicationPlaceholder)
}

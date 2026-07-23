package com.discord.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.RelativeLayout
import com.discord.R

class UserListItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RelativeLayout(context, attrs, 0) {
    init {
        LayoutInflater.from(context).inflate(R.layout.view_user_list_item, this)
        requireNotNull<android.view.View>(findViewById(R.id.user_list_item_avatar))
        requireNotNull<android.view.View>(findViewById(R.id.user_list_item_name))
        requireNotNull<android.view.View>(findViewById(R.id.user_list_item_name_secondary))
        requireNotNull<StatusView>(findViewById(R.id.user_list_item_status))
        isClickable = true
    }
}

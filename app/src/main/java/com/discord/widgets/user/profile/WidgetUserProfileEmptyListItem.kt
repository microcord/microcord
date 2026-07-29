package com.discord.widgets.user.profile

import android.util.TypedValue
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.discord.R
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder

class WidgetUserProfileEmptyListItem<
    T : MGRecyclerAdapter<D>,
    D : MGRecyclerDataPayload,
>(
    @LayoutRes layout: Int,
    adapter: T,
    @AttrRes backgroundImage: Int,
    @StringRes message: Int,
) : MGRecyclerViewHolder<T, D>(layout, adapter) {
    init {
        val themedImage = TypedValue().also {
            itemView.context.theme.resolveAttribute(backgroundImage, it, true)
        }.resourceId
        itemView.findViewById<ImageView>(R.id.user_profile_adapter_item_empty)
            .setBackgroundResource(themedImage)
        itemView.findViewById<TextView>(R.id.user_profile_adapter_item_empty_text).setText(message)
    }
}

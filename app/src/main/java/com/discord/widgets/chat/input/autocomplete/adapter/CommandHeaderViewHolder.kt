package com.discord.widgets.chat.input.autocomplete.adapter

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.widgets.chat.input.autocomplete.ApplicationPlaceholder

/**
 * Sticky section header naming the application whose commands follow.
 *
 * Rebinding is guarded on [currentItem]: the header is re-bound on every scroll frame while it is
 * pinned, and re-issuing the icon request each time would thrash Fresco.
 */
class CommandHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    private val avatar: ImageView = itemView.findViewById(R.id.chat_input_application_avatar)
    private val name: TextView = itemView.findViewById(R.id.chat_input_application_name)

    var currentItem: ApplicationPlaceholder? = null
        private set

    fun bind(item: ApplicationPlaceholder) {
        if (currentItem == item) return
        currentItem = item

        val application = item.application
        name.text = application.bot?.username ?: application.name

        val tint = ColorCompat.getThemedColor(itemView.context, R.attr.colorTextMuted)
        val iconRes = application.iconRes
        if (iconRes != null) {
            avatar.setImageResource(iconRes)
            avatar.setColorFilter(tint)
            return
        }

        val url = application.icon?.let { IconUtils.getApplicationIcon(application.id, it) }
        if (url == null) {
            avatar.setImageResource(R.drawable.ic_slash_command_24dp)
            avatar.setColorFilter(tint)
        } else {
            avatar.clearColorFilter()
            MGImages.setImage(avatar, url)
        }
    }

}

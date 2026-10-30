package com.discord.widgets.chat.input.autocomplete.adapter

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.models.commands.botUser
import com.discord.models.commands.displayName
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImagesBitmap
import com.discord.widgets.chat.input.autocomplete.ApplicationPlaceholder
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers

/**
 * Sticky section header naming the application whose commands follow.
 *
 * Rebinding is guarded on [currentItem]: the header is re-bound on every scroll frame while it is
 * pinned, and re-issuing the icon request each time would thrash Fresco.
 */
class CommandHeaderViewHolder(
    itemView: View,
    private val onIconLoaded: () -> Unit = {},
) : RecyclerView.ViewHolder(itemView), StickyHeaderHolder {

    private val avatar: ImageView = itemView.findViewById(R.id.chat_input_application_avatar)
    private val name: TextView = itemView.findViewById(R.id.chat_input_application_name)

    var currentItem: ApplicationPlaceholder? = null
        private set
    private var iconSubscription: Subscription? = null

    override fun getItemView(): View = itemView

    override fun bind(item: ApplicationPlaceholder) {
        if (currentItem == item) return
        currentItem = item

        val application = item.application
        val bot = application.botUser()
        name.text = application.displayName()

        val tint = ColorCompat.getThemedColor(itemView.context, R.attr.colorTextMuted)
        val iconRes = application.iconRes
        if (iconRes != null) {
            avatar.setImageResource(iconRes)
            avatar.setColorFilter(tint)
            return
        }

        // An app without its own icon shows its bot's avatar, as on desktop.
        val url = application.icon?.let { IconUtils.getApplicationIcon(application.id, it) }
            ?: bot?.let { IconUtils.getForUser(it) }
        if (url == null) {
            avatar.setImageResource(R.drawable.ic_slash_command_24dp)
            avatar.setColorFilter(tint)
        } else {
            avatar.clearColorFilter()
            avatar.setImageDrawable(null)
            loadIcon(url, item)
        }
    }

    /**
     * The bitmap is fetched and set directly rather than through a drawee controller: this header
     * is never added to the hierarchy — the decoration paints it onto the list's canvas — and
     * Fresco will not run a controller for a view it does not consider attached and visible.
     */
    private fun loadIcon(url: String, item: ApplicationPlaceholder) {
        iconSubscription?.unsubscribe()
        iconSubscription = MGImagesBitmap.getBitmap(url, imageIsCircle = true)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ bitmap ->
                if (currentItem != item) return@subscribe
                avatar.setImageBitmap(bitmap)
                onIconLoaded()
            }, {})
    }

}

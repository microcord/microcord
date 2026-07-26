package com.discord.widgets.servers

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.models.domain.ModelNotificationSettings
import com.discord.utilities.view.extensions.setTextAndVisibilityBy

/**
 * The per-channel notification overrides shown at the bottom of the server-notifications screen.
 *
 * Reference: `com.discord.widgets.servers.NotificationsOverridesAdapter`. A category override and a
 * channel override use different rows — the channel row additionally shows the category it lives in
 * — and both show the override's effective setting on the right ("Muted", "All", "@mentions",
 * "Nothing").
 *
 * Rows are diffed by channel id, so the swipe-to-delete in `WidgetServerNotifications` animates the
 * removed row out instead of redrawing the list.
 */
class NotificationsOverridesAdapter(
    private val onClick: (View, Item) -> Unit,
) : RecyclerView.Adapter<NotificationsOverridesAdapter.ViewHolder>() {

    /** One override: the channel it applies to, that channel's category, and the override itself. */
    data class Item(
        val channel: Channel,
        val parent: Channel?,
        val overrideSettings: ModelNotificationSettings.ChannelOverride,
    )

    var data: List<Item> = emptyList()
        set(value) {
            val diff = DiffUtil.calculateDiff(DiffCallback(value, field), true)
            field = value
            diff.dispatchUpdatesTo(this)
        }

    override fun getItemCount(): Int = data.size

    /** The row layout is chosen by channel type, so the view type *is* the channel type. */
    override fun getItemViewType(position: Int): Int = data[position].channel.type

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.onBind(data[position])

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            Channel.CATEGORY -> ViewHolder.CategoryOverridesViewHolder(
                inflater.inflate(R.layout.view_category_override_item, parent, false),
                onClick,
            )

            // Text, announcement and forum channels share the channel row. The reference throws for
            // every other type, voice included, and the override list is filtered to match.
            Channel.GUILD_TEXT, Channel.GUILD_ANNOUNCEMENT, Channel.GUILD_FORUM ->
                ViewHolder.ChannelOverridesViewHolder(
                    inflater.inflate(R.layout.view_channel_override_item, parent, false),
                    onClick,
                )

            else -> throw NotImplementedError("Type[$viewType] not implemented")
        }
    }

    private class DiffCallback(
        private val newItems: List<Item>,
        private val oldItems: List<Item>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldItems.size
        override fun getNewListSize(): Int = newItems.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            newItems[newItemPosition].channel.id == oldItems[oldItemPosition].channel.id

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            newItems[newItemPosition] == oldItems[oldItemPosition]
    }

    sealed class ViewHolder(
        itemView: View,
        private val onClick: (View, Item) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {

        open fun onBind(data: Item) {
            itemView.setOnClickListener { onClick(it, data) }
        }

        /** The label describing an override's message-notification frequency, `0` when unset. */
        @StringRes
        protected fun messageNotificationToString(messageNotificationLevel: Int): Int =
            when (messageNotificationLevel) {
                ModelNotificationSettings.FREQUENCY_ALL -> R.string.form_label_all_messages
                ModelNotificationSettings.FREQUENCY_MENTIONS -> R.string.form_label_only_mentions
                ModelNotificationSettings.FREQUENCY_NOTHING -> R.string.form_label_nothing
                else -> 0
            }

        /** "Muted" wins over the frequency: a muted override notifies for nothing regardless. */
        @StringRes
        private fun statusOf(data: Item): Int = if (data.overrideSettings.isMuted) {
            R.string.form_label_muted
        } else {
            messageNotificationToString(data.overrideSettings.messageNotifications)
        }

        /**
         * An override with neither a mute nor an explicit frequency has no status to show. The list
         * filters those out before they reach a row, so the blank is a safety net rather than a
         * state the user sees.
         */
        protected fun TextView.setStatus(data: Item) {
            val status = statusOf(data)
            text = if (status == 0) "" else context.getString(status)
        }

        class CategoryOverridesViewHolder(
            itemView: View,
            onClick: (View, Item) -> Unit,
        ) : ViewHolder(itemView, onClick) {
            private val name: TextView = itemView.findViewById(R.id.category_override_name)
            private val status: TextView = itemView.findViewById(R.id.category_override_status)

            override fun onBind(data: Item) {
                super.onBind(data)
                name.text = data.channel.getDisplayName()
                status.setStatus(data)
            }
        }

        class ChannelOverridesViewHolder(
            itemView: View,
            onClick: (View, Item) -> Unit,
        ) : ViewHolder(itemView, onClick) {
            private val name: TextView = itemView.findViewById(R.id.channel_override_name)
            private val categoryName: TextView =
                itemView.findViewById(R.id.channel_override_category_name)
            private val status: TextView = itemView.findViewById(R.id.channel_override_status)

            override fun onBind(data: Item) {
                super.onBind(data)
                name.text = data.channel.getDisplayName()
                categoryName.setTextAndVisibilityBy(data.parent?.getDisplayName())
                status.setStatus(data)
            }
        }
    }
}

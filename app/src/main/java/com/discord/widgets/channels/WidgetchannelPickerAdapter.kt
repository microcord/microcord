package com.discord.widgets.channels

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppComponent
import com.discord.databinding.ViewGuildRoleSubscriptionChannelItemBinding
import com.discord.utilities.recycler.DiffCreator

class WidgetchannelPickerAdapter(
    private val listener: OnItemClickListener,
    appComponent: AppComponent,
    private val diffCreator: DiffCreator<List<ChannelPickerAdapterItem>, ChannelPickerViewHolder> =
        DiffCreator(appComponent),
) : RecyclerView.Adapter<ChannelPickerViewHolder>() {
    private var items: List<ChannelPickerAdapterItem> = emptyList()

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        ChannelPickerAdapterItem.CreateChannelItem -> VIEW_TYPE_CREATE_CHANNEL
        is ChannelPickerAdapterItem.ChannelItem -> VIEW_TYPE_CHANNEL_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelPickerViewHolder {
        val binding = ViewGuildRoleSubscriptionChannelItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return when (viewType) {
            VIEW_TYPE_CREATE_CHANNEL -> ChannelPickerViewHolder.CreateChannelViewHolder(binding) {
                listener.onCreateChannelClick()
            }
            VIEW_TYPE_CHANNEL_ITEM -> ChannelPickerViewHolder.ChannelItemViewHolder(binding) {
                listener.onChannelItemClick(it)
            }
            else -> throw IllegalArgumentException(
                parent.context.getString(R.string.android_unknown_view_holder, viewType.toString()),
            )
        }
    }

    override fun onBindViewHolder(holder: ChannelPickerViewHolder, position: Int) {
        if (holder is ChannelPickerViewHolder.ChannelItemViewHolder) holder.configure(items[position])
    }

    fun setItems(newItems: List<ChannelPickerAdapterItem>) {
        diffCreator.dispatchDiffUpdatesAsync(this, { items = it }, items, newItems)
    }

    interface OnItemClickListener {
        fun onChannelItemClick(channelItem: ChannelPickerAdapterItem.ChannelItem)
        fun onCreateChannelClick()
    }

    private companion object {
        const val VIEW_TYPE_CREATE_CHANNEL = 0
        const val VIEW_TYPE_CHANNEL_ITEM = 1
    }
}

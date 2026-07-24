package com.discord.widgets.channels.list

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.stores.StoreStream
import com.facebook.drawee.view.SimpleDraweeView
import rx.Observable
import rx.Subscription
import java.text.NumberFormat

class WidgetChannelsList : Fragment(R.layout.widget_channels_list) {
    private lateinit var adapter: ChannelAdapter
    private var subscription: Subscription? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ChannelAdapter(
            { guildId, channelId -> channelClick?.invoke(guildId, channelId) },
            { guildId, categoryId ->
                val store = StoreStream.getCollapsedChannelCategories()
                val collapsed = categoryId in store.getCollapsedCategories()[guildId].orEmpty()
                store.setCollapsedCategory(guildId, categoryId, !collapsed)
            },
        )
        view.findViewById<RecyclerView>(R.id.channels_list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@WidgetChannelsList.adapter
        }
    }

    override fun onStart() {
        super.onStart()
        val selected = selectedGuilds ?: StoreStream.getGuildSelected().observeSelectedGuildId()
        subscription = selected.switchMap(::observeRows)
            .subscribe { state -> activity?.runOnUiThread { render(state) } }
    }

    override fun onStop() {
        subscription?.unsubscribe()
        subscription = null
        super.onStop()
    }

    private fun observeRows(guildId: Long): Observable<State> {
        val channels = StoreStream.getChannels().observeChannelsForGuild(guildId)
        val selected = StoreStream.getChannelsSelected().observeId()
        val collapsed = StoreStream.getCollapsedChannelCategories().observeCollapsedCategories(guildId)
        val readStates = StoreStream.getReadStates()
        val projection = Observable.combineLatest(
            readStates.getUnreadChannelIds(),
            readStates.getMentionCountsByChannel(),
        ) { unread, mentions -> Projection(unread, mentions) }
        if (guildId == 0L) return Observable.combineLatest(
            channels,
            Observable.combineLatest(projection, selected) { unread, selectedId -> unread to selectedId },
        ) { values, state ->
            State(0, null, ChannelListRows.private(values.values, state.first.unread, state.first.mentions, state.second))
        }
        val permissions = channelPermissions?.invoke(guildId)
            ?: StoreStream.getPermissions().observeChannelPermissionsForGuild(guildId)
        val rows = Observable.combineLatest(
            Observable.combineLatest(channels, permissions) { values, allowed -> values.values to allowed },
            Observable.combineLatest(
                Observable.combineLatest(projection, selected) { unread, selectedId -> unread to selectedId },
                collapsed,
            ) { unread, collapsedIds -> unread to collapsedIds },
        ) { channelState, categoryState ->
            val unreadState = categoryState.first
            ChannelListRows.guild(
                channelState.first,
                channelState.second,
                unreadState.first.unread,
                unreadState.first.mentions,
                unreadState.second,
                categoryState.second,
            )
        }
        return Observable.combineLatest(
            StoreStream.getGuilds().observeGuild(guildId),
            rows,
        ) { guild, rows -> State(guildId, guild?.name, rows) }
    }

    private fun render(state: State) {
        val root = requireView()
        val privateChannels = state.guildId == 0L
        root.findViewById<View>(R.id.app_bar_layout).visibility = if (privateChannels) View.GONE else View.VISIBLE
        root.findViewById<View>(R.id.channels_list_private_channels_header).visibility = if (privateChannels) View.VISIBLE else View.GONE
        root.findViewById<TextView>(R.id.channels_list_header).text = state.guildName.orEmpty()
        root.findViewById<View>(R.id.widget_channels_list_empty_friends_state_view).visibility =
            if (privateChannels && state.rows.isEmpty()) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.channels_list).visibility =
            if (privateChannels && state.rows.isEmpty()) View.GONE else View.VISIBLE
        adapter.guildId = state.guildId
        adapter.submitList(state.rows)
    }

    private data class State(val guildId: Long, val guildName: String?, val rows: List<ChannelListRow>)
    private data class Projection(val unread: Set<Long>, val mentions: Map<Long, Int>)

    companion object {
        var selectedGuilds: Observable<Long>? = null
        var channelPermissions: ((Long) -> Observable<Map<Long, Long>>)? = null
        var channelClick: ((Long, Long) -> Unit)? = StoreStream::selectChannel
    }
}

private class ChannelAdapter(
    private val click: (Long, Long) -> Unit,
    private val categoryClick: (Long, Long) -> Unit,
) : ListAdapter<ChannelListRow, ChannelAdapter.Holder>(DIFF) {
    var guildId = 0L
    init { setHasStableIds(true) }
    override fun getItemId(position: Int) = getItem(position).stableId
    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is ChannelListRow.Category -> 0
        is ChannelListRow.Text -> 1
        is ChannelListRow.Voice -> 2
        is ChannelListRow.Stage -> 3
        is ChannelListRow.Directory -> 4
        is ChannelListRow.Private -> 5
        is ChannelListRow.Thread -> 6
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = when (viewType) {
            0 -> R.layout.widget_channels_list_item_category
            1 -> R.layout.widget_channels_list_item_channel
            2 -> R.layout.widget_channels_list_item_channel_voice
            3 -> R.layout.widget_channels_list_item_channel_stage_voice
            4 -> R.layout.widget_channels_list_item_directory
            5 -> R.layout.widget_channels_list_item_channel_private
            else -> R.layout.widget_channels_list_item_thread
        }
        return Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val root = holder.itemView
        val nameView = root.findViewById<TextView>(when (row) {
            is ChannelListRow.Category -> R.id.channels_item_category_name
            is ChannelListRow.Text -> R.id.channels_item_channel_name
            is ChannelListRow.Voice -> R.id.channels_item_voice_channel_name
            is ChannelListRow.Stage -> R.id.stage_channel_item_voice_channel_name
            is ChannelListRow.Directory -> R.id.directory_channel_name
            is ChannelListRow.Private -> R.id.channels_list_item_private_name
            is ChannelListRow.Thread -> R.id.channels_item_thread_name
        })
        nameView.text = row.name
        root.contentDescription = row.name
        bindReadState(root, row)
        if (row is ChannelListRow.Private) bindPrivate(root, row.channel)
        if (row is ChannelListRow.Category) {
            root.isClickable = true
            root.setOnClickListener { categoryClick(guildId, row.channel.id) }
        } else {
            root.isClickable = true
            root.setOnClickListener { click(guildId, row.channel.id) }
        }
    }

    private fun bindReadState(root: View, row: ChannelListRow) {
        val unreadId = when (row) {
            is ChannelListRow.Text, is ChannelListRow.Voice -> R.id.channels_item_channel_unread
            is ChannelListRow.Thread -> R.id.channels_item_thread_unread
            else -> 0
        }
        if (unreadId != 0) root.findViewById<View>(unreadId)?.visibility = if (row.unread) View.VISIBLE else View.GONE
        val mentionsId = when (row) {
            is ChannelListRow.Text, is ChannelListRow.Voice -> R.id.channels_item_channel_mentions
            is ChannelListRow.Thread -> R.id.channels_item_thread_mentions
            is ChannelListRow.Private -> R.id.channels_list_item_private_mentions
            is ChannelListRow.Directory -> R.id.directory_channel_unread_count
            else -> 0
        }
        if (mentionsId != 0) root.findViewById<TextView>(mentionsId)?.apply {
            text = NumberFormat.getIntegerInstance().format(row.mentionCount)
            visibility = if (row.mentionCount > 0) View.VISIBLE else View.GONE
        }
    }

    private fun bindPrivate(root: View, channel: Channel) {
        root.findViewById<TextView>(R.id.channels_list_item_private_tag).visibility = View.GONE
        root.findViewById<TextView>(R.id.channels_list_item_private_desc).text = ""
        val avatar = channel.recipients.orEmpty().firstOrNull()
        root.findViewById<SimpleDraweeView>(R.id.channels_list_item_private_avatar).setImageURI(
            channel.icon?.let { "https://cdn.discordapp.com/channel-icons/${channel.id}/$it.webp?size=64" }
                ?: avatar?.avatar?.value?.let { "https://cdn.discordapp.com/avatars/${avatar.id}/$it.webp?size=64" },
        )
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view)
    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ChannelListRow>() {
            override fun areItemsTheSame(oldItem: ChannelListRow, newItem: ChannelListRow) =
                oldItem::class == newItem::class && oldItem.stableId == newItem.stableId
            override fun areContentsTheSame(oldItem: ChannelListRow, newItem: ChannelListRow) = oldItem == newItem
        }
    }
}

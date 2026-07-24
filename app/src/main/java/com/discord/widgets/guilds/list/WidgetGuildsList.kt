package com.discord.widgets.guilds.list

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import com.discord.R
import com.discord.stores.StoreExpandedGuildFolders
import com.discord.stores.StoreGuildSelected
import com.discord.stores.StoreGuildsSorted
import com.discord.stores.StoreStream
import com.discord.models.guild.Guild
import com.discord.api.channel.Channel
import com.discord.views.ServerFolderView
import com.facebook.drawee.view.SimpleDraweeView
import rx.Observable
import rx.Subscription
import java.text.NumberFormat

class WidgetGuildsList : Fragment(R.layout.widget_guilds_list) {
    private lateinit var adapter: GuildAdapter
    private var subscription: Subscription? = null
    private var selectedStore: StoreGuildSelected? = null
    private var expandedStore: StoreExpandedGuildFolders? = null
    private var currentEntries: List<StoreGuildsSorted.Entry> = emptyList()
    private var dragEntries: List<StoreGuildsSorted.Entry>? = null
    private var nextFolderId = -1000L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = GuildAdapter(::selectGuild, ::toggleFolder)
        view.findViewById<RecyclerView>(R.id.guild_list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@WidgetGuildsList.adapter
            ItemTouchHelper(
                GuildFolderDragCallback(
                    this@WidgetGuildsList.adapter::keyAt,
                    this@WidgetGuildsList.adapter::canDrag,
                    ::applyDrag,
                    {
                        val result = dragEntries ?: currentEntries
                        dragEntries = null
                        currentEntries = result
                        StoreStream.updateGuildFolders(result)
                    },
                    ::combineDrag,
                ),
            ).attachToRecyclerView(this)
        }
    }

    override fun onStart() {
        super.onStart()
        val sortedStore = StoreStream.getGuildsSorted()
        selectedStore = StoreStream.getGuildSelected()
        expandedStore = StoreStream.getExpandedGuildFolders()
        val readStates = StoreStream.getReadStates()
        val guildState = Observable.combineLatest(
            Observable.combineLatest(
                sortedStore.observeEntries(),
                Observable.combineLatest(
                    expandedStore!!.observeOpenFolderIds(),
                    selectedStore!!.observeSelectedGuildId(),
                ) { ids, selected -> ids to selected },
            ) { entries, state -> entries to state },
            Observable.combineLatest(
                readStates.getUnreadGuildIds(),
                readStates.getMentionCountsByGuild(),
            ) { unread, mentions -> unread to mentions },
        ) { state, readState -> Triple(state.first, state.second, readState) }
        val privateState = Observable.combineLatest(
            StoreStream.getChannels().observePrivateChannels(),
            StoreStream.getUserGuildSettings().observeGuildSettings(),
        ) { channels, settings -> channels.values to settings.values }
        subscription = Observable.combineLatest(guildState, privateState) { state, private ->
            val mentions = state.third.second
            val muted = private.second.flatMap { it.channelOverrides.orEmpty() }
                .filter { it.isMuted }.mapTo(HashSet()) { it.channelId }
            state.first to buildRows(
                state.first, state.second.first, state.second.second, state.third.first, mentions,
                private.first, muted,
            )
        }.subscribe { (entries, rows) -> activity?.runOnUiThread {
            if (dragEntries == null) currentEntries = entries
            adapter.submitList(rows)
        } }
    }

    override fun onStop() {
        subscription?.unsubscribe()
        subscription = null
        super.onStop()
    }

    private fun selectGuild(guildId: Long) {
        StoreStream.selectGuild(guildId)
    }

    private fun toggleFolder(folderId: Long) {
        val expanded = requireNotNull(expandedStore)
        if (folderId in expanded.getOpenFolderIds()) expanded.closeFolder(folderId) else expanded.openFolder(folderId)
    }

    private fun applyDrag(operation: GuildFolderReorder.Operation) {
        val result = GuildFolderReorder.apply(dragEntries ?: currentEntries, operation)
        dragEntries = result
        currentEntries = result
        StoreStream.getGuildsSorted().setPositions(result)
    }

    private fun combineDrag(source: GuildFolderReorder.EntryKey, target: GuildFolderReorder.EntryKey): Boolean {
        val sourceEntries = dragEntries ?: currentEntries
        val result = when {
            source is GuildFolderReorder.EntryKey.Guild && target is GuildFolderReorder.EntryKey.Guild ->
                GuildFolderReorder.createFolder(sourceEntries, source.id, target.id, { nextFolderId-- })
            source is GuildFolderReorder.EntryKey.Guild && target is GuildFolderReorder.EntryKey.Folder ->
                GuildFolderReorder.addGuildToFolder(
                    sourceEntries, source.id, target.id, expandedStore?.getOpenFolderIds().orEmpty(),
                )
            else -> sourceEntries
        }
        if (result == sourceEntries) return false
        dragEntries = result
        currentEntries = result
        StoreStream.getGuildsSorted().setPositions(result)
        return true
    }

    companion object {
        internal fun buildRows(
            entries: List<StoreGuildsSorted.Entry>,
            expanded: Set<Long>,
            selected: Long,
            unreadGuildIds: Set<Long> = emptySet(),
            mentionCounts: Map<Long, Int> = emptyMap(),
            privateChannels: Collection<Channel> = emptyList(),
            mutedPrivateChannelIds: Set<Long> = emptySet(),
        ): List<GuildRow> = buildList {
            add(GuildRow.Home(selected == 0L))
            privateChannels.filter { (mentionCounts[it.id] ?: 0) > 0 && it.id !in mutedPrivateChannelIds }
                .sortedWith(compareByDescending<Channel> { it.lastMessageId }.thenBy { it.id })
                .forEach { add(GuildRow.Private(it, mentionCounts[it.id] ?: 0)) }
            entries.forEach { entry ->
                when (entry) {
                    is StoreGuildsSorted.Entry.SingletonGuild ->
                        add(GuildRow.GuildItem(
                            entry.guild,
                            entry.guild.id == selected,
                            entry.guild.id in unreadGuildIds,
                            mentionCounts[entry.guild.id] ?: 0,
                        ))
                    is StoreGuildsSorted.Entry.Folder -> {
                        val guildIds = entry.guilds.mapTo(HashSet()) { it.id }
                        add(GuildRow.Folder(
                            entry.id,
                            entry.name ?: entry.guilds.joinToString(", ") { it.name },
                            entry.color,
                            entry.guilds,
                            entry.id in expanded,
                            guildIds.any { it in unreadGuildIds },
                            guildIds.sumOf { mentionCounts[it] ?: 0 },
                        ))
                        if (entry.id in expanded) {
                            addAll(entry.guilds.map {
                                GuildRow.GuildItem(it, it.id == selected, it.id in unreadGuildIds, mentionCounts[it.id] ?: 0)
                            })
                        }
                    }
                }
            }
        }

    }
}

internal sealed class GuildRow(val stableId: Long) {
    data class Home(val selected: Boolean) : GuildRow(0)
    data class GuildItem(
        val guild: Guild,
        val selected: Boolean,
        val unread: Boolean,
        val mentionCount: Int,
    ) : GuildRow(guild.id)
    data class Private(val channel: Channel, val mentionCount: Int) : GuildRow(channel.id xor Long.MIN_VALUE)
    data class Folder(
        val id: Long,
        val name: String,
        val color: Int?,
        val guilds: List<Guild>,
        val open: Boolean,
        val unread: Boolean,
        val mentionCount: Int,
    ) : GuildRow(id xor Long.MIN_VALUE)
}

private class GuildAdapter(
    private val guildClick: (Long) -> Unit,
    private val folderClick: (Long) -> Unit,
) : ListAdapter<GuildRow, RecyclerView.ViewHolder>(DIFF) {
    init { setHasStableIds(true) }
    fun keyAt(position: Int): GuildFolderReorder.EntryKey? = when (val row = currentList.getOrNull(position)) {
        is GuildRow.GuildItem -> GuildFolderReorder.EntryKey.Guild(row.guild.id)
        is GuildRow.Folder -> GuildFolderReorder.EntryKey.Folder(row.id)
        else -> null
    }
    fun canDrag(position: Int): Boolean = when (val row = currentList.getOrNull(position)) {
        is GuildRow.GuildItem -> true
        is GuildRow.Folder -> !row.open
        else -> false
    }
    override fun getItemId(position: Int) = getItem(position).stableId
    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is GuildRow.Home -> 0
        is GuildRow.GuildItem -> 1
        is GuildRow.Folder -> 2
        is GuildRow.Private -> 3
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = when (viewType) {
            0 -> R.layout.widget_guilds_list_item_profile
            1 -> R.layout.widget_guilds_list_item_guild_vertical
            2 -> R.layout.widget_guilds_list_item_folder
            else -> R.layout.widget_guilds_list_item_dm_vertical
        }
        return Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val root = holder.itemView
        when (val row = getItem(position)) {
            is GuildRow.Home -> {
                root.contentDescription = root.context.getString(R.string.direct_messages)
                root.findViewById<View>(R.id.guilds_item_profile_selected).visibility = if (row.selected) View.VISIBLE else View.GONE
                root.setOnClickListener { guildClick(0) }
            }
            is GuildRow.GuildItem -> {
                val guild = row.guild
                root.contentDescription = guild.name
                root.findViewById<SimpleDraweeView>(R.id.guilds_item_avatar).setImageURI(
                    guild.icon?.let { "https://cdn.discordapp.com/icons/${guild.id}/$it.webp?size=128" },
                )
                root.findViewById<TextView>(R.id.guilds_item_avatar_text).apply {
                    text = guild.shortName
                    visibility = if (guild.icon == null) View.VISIBLE else View.GONE
                }
                root.findViewById<ImageView>(R.id.guilds_item_selected).visibility = if (row.selected) View.VISIBLE else View.GONE
                root.findViewById<ImageView>(R.id.guilds_item_unread).visibility = if (row.unread) View.VISIBLE else View.GONE
                root.findViewById<TextView>(R.id.guilds_item_mentions).apply {
                    text = NumberFormat.getIntegerInstance().format(row.mentionCount)
                    visibility = if (row.mentionCount > 0) View.VISIBLE else View.GONE
                }
                root.setOnClickListener { guildClick(guild.id) }
            }
            is GuildRow.Folder -> {
                root.contentDescription = row.name
                root.findViewById<ServerFolderView>(R.id.guilds_item_folder).apply {
                    folderId = row.id
                    isOpen = row.open
                    folderImage.imageTintList = row.color?.let(ColorStateList::valueOf)
                    guildViews.forEachIndexed { index, view ->
                        val guild = row.guilds.getOrNull(index)
                        view.visibility = if (guild == null) View.INVISIBLE else View.VISIBLE
                        guild?.let {
                            view.configure(
                                it.icon?.let { hash -> "https://cdn.discordapp.com/icons/${it.id}/$hash.webp?size=64" },
                                it.shortName,
                            )
                        }
                    }
                    showCurrentState()
                }
                root.findViewById<ImageView>(R.id.guilds_item_unread).visibility = if (row.unread) View.VISIBLE else View.GONE
                root.findViewById<TextView>(R.id.guilds_item_mentions).apply {
                    text = NumberFormat.getIntegerInstance().format(row.mentionCount)
                    visibility = if (row.mentionCount > 0) View.VISIBLE else View.GONE
                }
                root.setOnClickListener { folderClick(row.id) }
            }
            is GuildRow.Private -> {
                val channel = row.channel
                root.contentDescription = channel.name ?: channel.recipients.orEmpty().joinToString(", ") { it.username }
                val recipient = channel.recipients.orEmpty().firstOrNull()
                root.findViewById<SimpleDraweeView>(R.id.guilds_item_dm_avatar).setImageURI(
                    channel.icon?.let { "https://cdn.discordapp.com/channel-icons/${channel.id}/$it.webp?size=64" }
                        ?: recipient?.avatar?.value?.let { "https://cdn.discordapp.com/avatars/${recipient.id}/$it.webp?size=64" },
                )
                root.findViewById<TextView>(R.id.guilds_item_dm_count).text = NumberFormat.getIntegerInstance().format(row.mentionCount)
                root.setOnClickListener { StoreStream.selectChannel(0, channel.id) }
            }
        }
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view)
    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<GuildRow>() {
            override fun areItemsTheSame(oldItem: GuildRow, newItem: GuildRow) = oldItem.stableId == newItem.stableId
            override fun areContentsTheSame(oldItem: GuildRow, newItem: GuildRow) = oldItem == newItem
        }
    }
}

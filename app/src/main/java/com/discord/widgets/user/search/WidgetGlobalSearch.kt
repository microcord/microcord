package com.discord.widgets.user.search

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.discord.app.AppFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.models.domain.ModelUserRelationship
import com.discord.stores.StoreStream
import com.discord.widgets.tabs.NavigationTab
import com.google.android.material.textfield.TextInputEditText
import rx.subscriptions.CompositeSubscription

class WidgetGlobalSearch : AppFragment(R.layout.widget_global_search) {
    private val adapter = Adapter(::select)
    private var query = ""
    private var subscriptions = CompositeSubscription()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.global_search_guild_list).visibility = View.GONE
        view.findViewById<View>(R.id.global_search_add_a_friend_btn).visibility = View.GONE
        view.findViewById<View>(R.id.global_search_join_guild_btn).visibility = View.GONE
        view.findViewById<RecyclerView>(R.id.global_search_recycler).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@WidgetGlobalSearch.adapter
        }
        view.findViewById<TextInputEditText>(R.id.global_search_bar_text).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty()
                render()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    override fun onStart() {
        super.onStart()
        subscriptions = CompositeSubscription()
        listOf(
            StoreStream.getUsers().observeAllUsers(),
            StoreStream.getGuilds().observeGuilds(),
            StoreStream.getChannels().observeAllChannels(),
            StoreStream.getPresences().observeAllPresences(),
            StoreStream.getReadStates().getMentionCountsByChannel(),
        ).forEach { source -> subscriptions.add(source.subscribe { view?.post(::render) }) }
        subscriptions.add(StoreStream.getUserRelationships().observe().subscribe { view?.post(::render) })
        render()
    }

    override fun onStop() {
        subscriptions.unsubscribe()
        super.onStop()
    }

    private fun render() {
        val root = view ?: return
        val raw = query.trim()
        val prefix = raw.firstOrNull()?.takeIf { it in "@#!*" }
        val filter = raw.removePrefix(prefix?.toString().orEmpty()).trim().lowercase()
        val allChannels = StoreStream.getChannels().getAllChannels()
        val guilds = StoreStream.getGuilds().getGuilds()
        val mentions = StoreStream.getReadStates().getMentionCountsByChannelSnapshot()
        val unread = StoreStream.getReadStates().getUnreadChannelIdsSnapshot()
        val relationships = StoreStream.getUserRelationships().getRelationships()
        val presences = StoreStream.getPresences().getPresences()
        val rows = buildList<WidgetGlobalSearchModel.ItemDataPayload> {
            if (prefix == null || prefix == '#' || prefix == '!') allChannels.values.filter { channel ->
                channel.type != Channel.CATEGORY && when (prefix) {
                    '#' -> channel.type !in setOf(Channel.GUILD_VOICE, Channel.GUILD_STAGE_VOICE)
                    '!' -> channel.type in setOf(Channel.GUILD_VOICE, Channel.GUILD_STAGE_VOICE)
                    else -> true
                }
            }.forEach { channel ->
                val label = channel.name ?: channel.recipients.orEmpty().joinToString(", ") { it.username }
                match(label, filter)?.let { add(WidgetGlobalSearchModel.ItemChannel(it, channel, allChannels[channel.parentId], guilds[channel.guildId], mentions[channel.id] ?: 0, channel.id in unread)) }
            }
            if (prefix == null || prefix == '*') guilds.values.forEach { guild ->
                match(guild.name, filter)?.let { add(WidgetGlobalSearchModel.ItemGuild(it, guild)) }
            }
            if (prefix == null || prefix == '@') StoreStream.getUsers().getUsers().values.forEach { user ->
                match(user.username, filter)?.let { add(WidgetGlobalSearchModel.ItemUser(it, user, emptyList(), relationships[user.id] == ModelUserRelationship.TYPE_FRIEND, presences[user.id])) }
            }
        }.sortedBy { it.matchedResult.value.toString().lowercase() }.take(100)
        adapter.items = rows
        root.findViewById<com.discord.app.AppViewFlipper>(R.id.global_search_view_flipper).displayedChild = when {
            rows.isNotEmpty() -> 0
            filter.isEmpty() -> 1
            else -> 2
        }
    }

    private fun select(item: WidgetGlobalSearchModel.ItemDataPayload) {
        when (item) {
            is WidgetGlobalSearchModel.ItemChannel -> StoreStream.selectChannel(item.channel.guildId, item.channel.id)
            is WidgetGlobalSearchModel.ItemGuild -> StoreStream.selectGuild(item.guild.id)
            is WidgetGlobalSearchModel.ItemUser -> StoreStream.openDm(item.user.id)
        }
        StoreStream.getTabsNavigation().selectHomeTab(com.discord.stores.StoreNavigation.PanelAction.CLOSE, true)
    }

    private class Adapter(private val click: (WidgetGlobalSearchModel.ItemDataPayload) -> Unit) : RecyclerView.Adapter<Holder>() {
        var items = emptyList<WidgetGlobalSearchModel.ItemDataPayload>()
            set(value) { field = value; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ViewGlobalSearchItem(parent.context))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            when (item) {
                is WidgetGlobalSearchModel.ItemChannel -> holder.view.onConfigure(item)
                is WidgetGlobalSearchModel.ItemGuild -> holder.view.onConfigure(item)
                is WidgetGlobalSearchModel.ItemUser -> holder.view.onConfigure(item)
            }
            holder.view.setOnClickListener { click(item) }
        }
    }

    private class Holder(val view: ViewGlobalSearchItem) : RecyclerView.ViewHolder(view)
}

internal fun match(value: String, filter: String): WidgetGlobalSearchModel.MatchedResult? {
    val index = value.lowercase().indexOf(filter)
    return index.takeIf { it >= 0 }?.let { WidgetGlobalSearchModel.MatchedResult(value, it, filter.length) }
}

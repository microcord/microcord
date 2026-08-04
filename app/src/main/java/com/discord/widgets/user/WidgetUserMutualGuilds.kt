package com.discord.widgets.user

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.discord.app.AppFragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppScreenRouter
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.i18n.I18nHooks
import com.discord.stores.StoreStream
import com.discord.widgets.tabs.NavigationTab
import com.facebook.drawee.view.SimpleDraweeView
import rx.Observable
import rx.Subscription
import com.discord.utilities.user.UserUtils.getDisplayName

class WidgetUserMutualGuilds : AppFragment(R.layout.widget_user_mutual_guilds) {
    sealed class State {
        object Loading : State()
        data class Loaded(val username: String?, val guilds: List<MutualGuild>) : State()
        data class Error(val message: String? = null) : State()
    }
    data class MutualGuild(val guild: Guild, val user: User? = null, val member: GuildMember? = null, val nick: String? = null)

    private val adapter = Adapter()
    private var subscription: Subscription? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setActionBarDisplayHomeAsUpEnabled()
        view.findViewById<RecyclerView>(R.id.user_mutual_guilds_recycler_view).apply {
            layoutManager = LinearLayoutManager(requireContext()); adapter = this@WidgetUserMutualGuilds.adapter
        }
    }
    override fun onStart() {
        super.onStart()
        subscription = (stateSource?.invoke(requireArguments().getLong(ARG_USER_ID)) ?: Observable.just(State.Loading))
            .distinctUntilChanged().subscribe { state ->
                view?.post { if (view != null && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) show(state) }
            }
    }
    override fun onStop() { subscription?.unsubscribe(); subscription = null; super.onStop() }

    private fun show(state: State) {
        when (state) {
            State.Loading -> { setActionBarTitle(R.string.mutual_guilds); adapter.rows = emptyList() }
            is State.Error -> adapter.rows = listOf(Row.Empty(state.message ?: getString(R.string.no_mutual_guilds)))
            is State.Loaded -> {
                setActionBarTitle(
                    I18nHooks.plural(requireContext(), R.plurals.user_profile_mutual_guilds_count, state.guilds.size),
                )
                setActionBarSubtitle(state.username)
                adapter.rows = state.guilds.map(Row::GuildRow).ifEmpty { listOf(Row.Empty(getString(R.string.no_mutual_guilds))) }
            }
        }
    }

    private sealed class Row(val stableId: Long) {
        data class GuildRow(val value: MutualGuild) : Row(value.guild.id)
        data class Empty(val text: String) : Row(Long.MIN_VALUE)
    }
    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var rows: List<Row> = emptyList(); set(value) { field = value; notifyDataSetChanged() }
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = rows[position].stableId
        override fun getItemViewType(position: Int) = if (rows[position] is Row.GuildRow) 1 else 0
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder = if (type == 1)
            GuildHolder(LayoutInflater.from(parent.context).inflate(R.layout.widget_user_profile_adapter_item_server, parent, false))
        else EmptyHolder(LayoutInflater.from(parent.context).inflate(R.layout.widget_user_profile_adapter_item_empty, parent, false))
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = when (val row = rows[position]) {
            is Row.GuildRow -> (holder as GuildHolder).bind(row.value)
            is Row.Empty -> (holder as EmptyHolder).bind(row.text)
        }
    }
    private inner class GuildHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(item: MutualGuild) {
            val guild = item.guild
            itemView.setOnClickListener {
                StoreStream.getTabsNavigation().selectTab(NavigationTab.HOME, true)
                StoreStream.selectGuild(guild.id)
                requireActivity().finish()
            }
            itemView.contentDescription = guild.name
            itemView.findViewById<TextView>(R.id.user_profile_adapter_item_server_name).text = guild.name
            itemView.findViewById<SimpleDraweeView>(R.id.user_profile_adapter_item_server_image).apply {
                visibility = if (guild.icon == null) View.GONE else View.VISIBLE
                setImageURI(guild.icon?.let { Uri.parse("https://cdn.discordapp.com/icons/${guild.id}/$it.webp?size=128") })
            }
            itemView.findViewById<TextView>(R.id.user_profile_adapter_item_server_text).apply {
                visibility = if (guild.icon == null) View.VISIBLE else View.GONE; text = guild.shortName
            }
            val hasGuildMemberAvatar = item.member?.hasAvatar() == true
            val identity = item.user?.getDisplayName(item.nick)
            itemView.findViewById<TextView>(R.id.user_profile_adapter_item_user_display_name).apply {
                text = identity
                visibility = if (item.nick != null || hasGuildMemberAvatar) View.VISIBLE else View.GONE
            }
            itemView.findViewById<SimpleDraweeView>(R.id.guild_member_avatar).apply {
                val hash = item.member?.avatarHash
                visibility = if (hasGuildMemberAvatar) View.VISIBLE else View.GONE
                setImageURI(if (hash == null || item.user == null) null else Uri.parse("https://cdn.discordapp.com/guilds/${guild.id}/users/${item.user.id}/avatars/$hash.webp?size=32"))
            }
        }
    }
    private class EmptyHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(value: String) {
            itemView.findViewById<TextView>(R.id.user_profile_adapter_item_empty_text).text = value
            val typed = android.util.TypedValue(); itemView.context.theme.resolveAttribute(R.attr.img_no_mutual_servers, typed, true)
            itemView.findViewById<ImageView>(R.id.user_profile_adapter_item_empty).setImageResource(typed.resourceId)
        }
    }

    companion object {
        private const val ARG_USER_ID = "com.discord.intent.extra.EXTRA_USER_ID"
        @JvmField var stateSource: ((Long) -> Observable<State>)? = null
        fun newInstance(userId: Long) = WidgetUserMutualGuilds().apply { arguments = Bundle().apply { putLong(ARG_USER_ID, userId) } }

        @JvmStatic
        fun show(context: Context, userId: Long) = AppScreenRouter.launch(
            context,
            WidgetUserMutualGuilds::class.java,
            Intent().putExtra(ARG_USER_ID, userId),
        )
    }
}

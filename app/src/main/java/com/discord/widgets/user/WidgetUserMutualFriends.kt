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
import com.discord.models.presence.Presence
import com.discord.models.user.User
import com.discord.views.AvatarDecorationView
import com.discord.stores.StoreStream
import com.discord.utilities.user.UserUtils.getDisplayName
import com.discord.i18n.I18nHooks
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.views.StatusView
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.facebook.drawee.view.SimpleDraweeView
import rx.Observable
import rx.Subscription

class WidgetUserMutualFriends : AppFragment(R.layout.widget_user_mutual_friends) {
    sealed class State {
        object Loading : State()
        data class Loaded(val username: String?, val friends: List<Friend>) : State()
        data class Error(val message: String? = null) : State()
    }
    data class Friend(
        val user: User,
        val presence: Presence? = null,
        val mutualGuilds: List<Guild> = emptyList(),
        val isApplicationStreaming: Boolean? = null,
    )

    private val adapter = Adapter()
    private var subscription: Subscription? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setActionBarDisplayHomeAsUpEnabled()
        view.findViewById<RecyclerView>(R.id.user_mutual_friends_recycler_view).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@WidgetUserMutualFriends.adapter
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
            State.Loading -> { setActionBarTitle(R.string.mutual_friends); adapter.rows = emptyList() }
            is State.Error -> adapter.rows = listOf(Row.Empty(state.message ?: getString(R.string.no_mutual_friends)))
            is State.Loaded -> {
                setActionBarTitle(
                    I18nHooks.plural(requireContext(), R.plurals.user_profile_mutual_friends_count, state.friends.size),
                )
                setActionBarSubtitle(state.username)
                adapter.rows = state.friends.map(Row::FriendRow).ifEmpty { listOf(Row.Empty(getString(R.string.no_mutual_friends))) }
            }
        }
    }

    private sealed class Row(val stableId: Long) {
        data class FriendRow(val value: Friend) : Row(value.user.id)
        data class Empty(val text: String) : Row(Long.MIN_VALUE)
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var rows: List<Row> = emptyList()
            set(value) { field = value; notifyDataSetChanged() }
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = rows[position].stableId
        override fun getItemViewType(position: Int) = if (rows[position] is Row.FriendRow) 1 else 0
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder = if (type == 1) {
            FriendHolder(LayoutInflater.from(parent.context).inflate(R.layout.widget_user_profile_adapter_item_friend, parent, false))
        } else EmptyHolder(LayoutInflater.from(parent.context).inflate(R.layout.widget_user_profile_adapter_item_empty, parent, false))
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = when (val row = rows[position]) {
            is Row.FriendRow -> (holder as FriendHolder).bind(row.value)
            is Row.Empty -> (holder as EmptyHolder).bind(row.text, R.attr.img_no_mutual_friends)
        }
    }

    private inner class FriendHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(friend: Friend) {
            itemView.setOnClickListener { WidgetUserSheet.show(parentFragmentManager, friend.user.id) }
            itemView.contentDescription = friend.user.username
            itemView.findViewById<TextView>(R.id.user_profile_adapter_item_friend_user_name).text =
                friend.user.getDisplayName(
                    StoreStream.getUserRelationships().getNickname(friend.user.id),
                )
            itemView.findViewById<StatusView>(R.id.user_profile_adapter_item_friend_status).setPresence(friend.presence)
            itemView.findViewById<SimpleDraweeSpanTextView>(R.id.user_profile_adapter_item_friend_user_game).apply {
                PresenceUtils.setStatusLineWithLeadingIcon(friend.presence, friend.isApplicationStreaming == true, this, true)
                visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            }
            itemView.findViewById<SimpleDraweeView>(R.id.user_profile_adapter_item_friend_avatar).setImageURI(avatarUrl(friend.user))
            itemView.findViewById<AvatarDecorationView>(R.id.user_profile_adapter_item_friend_avatar_decoration)
                .bind(friend.user.avatarDecoration)
            listOf(R.id.mutual_server_1, R.id.mutual_server_2, R.id.mutual_server_3, R.id.mutual_server_4).forEachIndexed { index, id ->
                val root = itemView.findViewById<View>(id)
                val guild = friend.mutualGuilds.getOrNull(index)
                root.visibility = if (guild == null) View.GONE else View.VISIBLE
                if (guild != null) {
                    root.contentDescription = guild.name
                    root.findViewById<SimpleDraweeView>(R.id.user_profile_adapter_item_friend_mutual_image).apply {
                        visibility = if (guild.icon == null) View.GONE else View.VISIBLE
                        setImageURI(guild.icon?.let { "https://cdn.discordapp.com/icons/${guild.id}/$it.webp?size=64" })
                    }
                    root.findViewById<TextView>(R.id.user_profile_adapter_item_friend_mutual_text).apply {
                        visibility = if (guild.icon == null) View.VISIBLE else View.GONE; text = guild.shortName
                    }
                }
            }
        }
    }

    private class EmptyHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(value: String, imageAttr: Int) {
            itemView.findViewById<TextView>(R.id.user_profile_adapter_item_empty_text).text = value
            val typed = android.util.TypedValue(); itemView.context.theme.resolveAttribute(imageAttr, typed, true)
            itemView.findViewById<ImageView>(R.id.user_profile_adapter_item_empty).setImageResource(typed.resourceId)
        }
    }

    private fun avatarUrl(user: User): String? = user.avatar?.let {
        "https://cdn.discordapp.com/avatars/${user.id}/$it.webp?size=128"
    }

    companion object {
        private const val ARG_USER_ID = "com.discord.intent.extra.EXTRA_USER_ID"
        @JvmField var stateSource: ((Long) -> Observable<State>)? = null
        fun newInstance(userId: Long) = WidgetUserMutualFriends().apply { arguments = Bundle().apply { putLong(ARG_USER_ID, userId) } }

        @JvmStatic
        fun show(context: Context, user: User) = AppScreenRouter.launch(
            context,
            WidgetUserMutualFriends::class.java,
            Intent().putExtra(ARG_USER_ID, user.id),
        )
    }
}

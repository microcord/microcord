package com.discord.widgets.user

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import com.discord.app.AppFragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.models.guild.Guild
import com.discord.models.presence.Presence
import com.discord.models.user.User
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.views.StatusView
import com.facebook.drawee.view.SimpleDraweeView
import rx.Observable
import rx.Subscription

class WidgetUserMutualFriends : AppFragment(R.layout.widget_user_mutual_friends) {
    sealed class State {
        object Loading : State()
        data class Loaded(val username: String?, val friends: List<Friend>) : State()
        data class Error(val message: String? = null) : State()
    }
    data class Friend(val user: User, val presence: Presence? = null, val mutualGuilds: List<Guild> = emptyList())

    private val adapter = Adapter()
    private var subscription: Subscription? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<Toolbar>(R.id.action_bar_toolbar)?.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
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
        val toolbar = requireView().findViewById<Toolbar>(R.id.action_bar_toolbar)
        when (state) {
            State.Loading -> { toolbar?.setTitle(R.string.mutual_friends); adapter.rows = emptyList() }
            is State.Error -> adapter.rows = listOf(Row.Empty(state.message ?: getString(R.string.no_mutual_friends)))
            is State.Loaded -> {
                toolbar?.title = resources.getQuantityString(R.plurals.user_profile_mutual_friends_count, state.friends.size, state.friends.size)
                toolbar?.subtitle = state.username
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
            itemView.setOnClickListener { onFriendSelected?.invoke(friend.user.id) }
            itemView.contentDescription = friend.user.username
            itemView.findViewById<TextView>(R.id.user_profile_adapter_item_friend_user_name).text = friend.user.username
            itemView.findViewById<StatusView>(R.id.user_profile_adapter_item_friend_status).setPresence(friend.presence)
            itemView.findViewById<SimpleDraweeSpanTextView>(R.id.user_profile_adapter_item_friend_user_game).apply {
                text = friend.presence?.activities?.firstOrNull()?.name.orEmpty()
                visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            }
            itemView.findViewById<SimpleDraweeView>(R.id.user_profile_adapter_item_friend_avatar).setImageURI(avatarUrl(friend.user))
            listOf(R.id.mutual_server_1, R.id.mutual_server_2, R.id.mutual_server_3, R.id.mutual_server_4).forEachIndexed { index, id ->
                val root = itemView.findViewById<View>(id)
                val guild = friend.mutualGuilds.getOrNull(index)
                root.visibility = if (guild == null) View.GONE else View.VISIBLE
                if (guild != null) {
                    root.contentDescription = guild.name
                    root.findViewById<SimpleDraweeView>(R.id.user_profile_adapter_item_friend_mutual_image).apply {
                        visibility = if (guild.icon == null) View.GONE else View.VISIBLE
                        setImageURI(guild.icon?.let { Uri.parse("https://cdn.discordapp.com/icons/${guild.id}/$it.webp?size=64") })
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

    private fun avatarUrl(user: User): Uri? = user.avatar?.let {
        Uri.parse("https://cdn.discordapp.com/avatars/${user.id}/$it.webp?size=128")
    }

    companion object {
        private const val ARG_USER_ID = "user_id"
        @JvmField var stateSource: ((Long) -> Observable<State>)? = null
        @JvmField var onFriendSelected: ((Long) -> Unit)? = null
        fun newInstance(userId: Long) = WidgetUserMutualFriends().apply { arguments = Bundle().apply { putLong(ARG_USER_ID, userId) } }
    }
}

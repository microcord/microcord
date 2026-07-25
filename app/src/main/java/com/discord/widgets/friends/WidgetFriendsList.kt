package com.discord.widgets.friends

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.presence.ClientStatus
import com.discord.app.AppViewFlipper
import com.discord.models.presence.Presence
import com.discord.views.StatusView
import com.facebook.drawee.view.SimpleDraweeView
import rx.Observable
import rx.Subscription

class WidgetFriendsList : Fragment(R.layout.widget_friends_list) {
    private var subscription: Subscription? = null
    private var latestInput = FriendsListInput()
    private var suggestionsExpanded = false
    private var pendingExpanded = false
    private val listAdapter = FriendsAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.title = getString(R.string.friends)
        view.findViewById<EmptyFriendsStateView>(R.id.friends_list_empty_friends_state_view).setOnAddFriendClicked {
            onAddFriend()
        }
        view.findViewById<RecyclerView>(R.id.friends_list_recycler).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = listAdapter
        }
        view.findViewById<RecyclerView>(R.id.friends_list_loading).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = LoadingAdapter()
        }
        listAdapter.onHeader = { section ->
            when (section) {
                FriendSection.SUGGESTIONS -> suggestionsExpanded = !suggestionsExpanded
                FriendSection.PENDING -> pendingExpanded = !pendingExpanded
                else -> Unit
            }
            if (section == FriendSection.SUGGESTIONS || section == FriendSection.PENDING) {
                show(FriendsListBuilder.build(latestInput.copy(suggestionsExpanded = suggestionsExpanded, pendingExpanded = pendingExpanded)))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        subscription = (stateSource?.invoke() ?: Observable.just(FriendsListInput()))
            .distinctUntilChanged()
            .subscribe { input ->
                latestInput = input
                view?.post {
                    if (view != null) show(
                        FriendsListBuilder.build(input.copy(suggestionsExpanded = suggestionsExpanded, pendingExpanded = pendingExpanded)),
                    )
                }
            }
    }

    override fun onStop() {
        subscription?.unsubscribe()
        subscription = null
        super.onStop()
    }

    private fun show(state: FriendsListState) {
        val flipper = requireView().findViewById<AppViewFlipper>(R.id.friends_list_flipper)
        flipper.displayedChild = when (state) {
            FriendsListState.Loading -> 2
            FriendsListState.Empty -> 1
            is FriendsListState.Loaded -> 0
        }
        if (state is FriendsListState.Loaded) listAdapter.submitList(state.rows)
    }

    private class LoadingAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = 8
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.widget_friends_list_adapter_item_loading, parent, false),
        )
        override fun onBindViewHolder(holder: Holder, position: Int) = Unit
    }

    private class FriendsAdapter : ListAdapter<FriendRow, Holder>(DIFF) {
        var onHeader: (FriendSection) -> Unit = {}

        init { setHasStableIds(true) }

        override fun getItemId(position: Int) = getItem(position).stableId
        override fun getItemViewType(position: Int) = when (getItem(position)) {
            is FriendRow.Header -> if ((getItem(position) as FriendRow.Header).section in setOf(FriendSection.ONLINE, FriendSection.OFFLINE)) 4 else 0
            is FriendRow.Friend -> 1
            is FriendRow.Pending -> 2
            is FriendRow.Suggestion -> 3
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val layout = when (viewType) {
                0 -> R.layout.widget_friends_list_expandable_header
                1 -> R.layout.widget_friends_list_adapter_item_friend
                2 -> R.layout.widget_friends_list_adapter_item_pending
                4 -> R.layout.widget_friends_list_adapter_item_header
                else -> R.layout.widget_friends_list_adapter_suggested_friend
            }
            return Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val row = getItem(position)) {
                is FriendRow.Header -> bindHeader(holder.itemView, row)
                is FriendRow.Friend -> bindFriend(holder.itemView, row.value)
                is FriendRow.Pending -> bindPending(holder.itemView, row.value)
                is FriendRow.Suggestion -> bindSuggestion(holder.itemView as SuggestedFriendView, row.value)
            }
        }

        private fun bindHeader(root: View, row: FriendRow.Header) {
            val title = when (row.section) {
                FriendSection.SUGGESTIONS -> root.context.getString(R.string.friends_friend_suggestions_header).replace("{count}", row.count.toString())
                FriendSection.PENDING -> root.context.getString(R.string.friends_pending_request_header).replace("{pendingRequestNumber}", row.count.toString())
                FriendSection.ONLINE -> root.context.getString(R.string.friends_online_header).replace("{online}", row.count.toString())
                FriendSection.OFFLINE -> root.context.getString(R.string.friends_offline_header).replace("{offline}", row.count.toString())
            }
            root.findViewById<TextView>(R.id.friends_list_item_header_text)?.text = title
            root.findViewById<TextView>(R.id.friends_list_pending_item_header_text)?.text = title
            root.findViewById<TextView>(R.id.friends_list_expandable_header_button)?.apply {
                visibility = if (row.expandable) View.VISIBLE else View.GONE
                text = context.getString(if (row.expanded) R.string.friends_pending_request_expand_collapse else R.string.friends_pending_request_expand)
                setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (row.expanded) R.drawable.ic_arrow_up_24dp else R.drawable.ic_arrow_right_24dp, 0)
                contentDescription = "$text, $title"
                setOnClickListener { onHeader(row.section) }
            }
        }

        private fun bindFriend(root: View, value: FriendRelationshipUi) {
            bindUser(root, value)
            root.findViewById<AppCompatImageView>(R.id.friends_list_item_chat_button).setOnClickListener { onMessage(value.user) }
            root.findViewById<AppCompatImageView>(R.id.friends_list_item_call_button).setOnClickListener { onCall(value.user) }
        }

        private fun bindPending(root: View, value: FriendRelationshipUi) {
            bindUser(root, value)
            val incoming = value.relationship == FriendRelationship.INCOMING
            root.findViewById<TextView>(R.id.friends_list_item_activity).setText(
                if (incoming) R.string.incoming_friend_request else R.string.outgoing_friend_request,
            )
            root.findViewById<AppCompatImageView>(R.id.friends_list_item_accept_button).apply {
                visibility = if (incoming) View.VISIBLE else View.GONE
                contentDescription = context.getString(R.string.accept)
                setOnClickListener { onAccept(value.user) }
            }
            root.findViewById<AppCompatImageView>(R.id.friends_list_item_decline_button).apply {
                contentDescription = context.getString(if (incoming) R.string.decline else R.string.cancel)
                setOnClickListener { if (incoming) onDecline(value.user) else onCancel(value.user) }
            }
        }

        private fun bindUser(root: View, value: FriendRelationshipUi) {
            root.contentDescription = value.user.username
            root.setOnClickListener { onProfile(value.user) }
            root.findViewById<TextView>(R.id.friends_list_item_name).text = value.user.username
            root.findViewById<TextView>(R.id.friends_list_item_activity).apply {
                text = value.activity.orEmpty()
                visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            }
            root.findViewById<SimpleDraweeView>(R.id.friends_list_item_avatar).setImageURI(value.user.avatarUrl)
            root.findViewById<StatusView>(R.id.friends_list_item_status).setPresence(
                Presence(
                    when (value.presence) {
                        FriendPresence.ONLINE -> ClientStatus.ONLINE
                        FriendPresence.IDLE -> ClientStatus.IDLE
                        FriendPresence.DND -> ClientStatus.DND
                        FriendPresence.OFFLINE -> ClientStatus.OFFLINE
                    },
                    null,
                    null,
                ),
            )
        }

        private fun bindSuggestion(root: SuggestedFriendView, value: FriendSuggestionUi) {
            root.setUser(value.user, value.publicName)
            root.setOnClickListener { onProfile(value.user) }
            root.setOnSendClicked { onAccept(value.user) }
            root.setOnDeclineClicked { onDecline(value.user) }
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<FriendRow>() {
                override fun areItemsTheSame(oldItem: FriendRow, newItem: FriendRow) = oldItem.stableId == newItem.stableId
                override fun areContentsTheSame(oldItem: FriendRow, newItem: FriendRow) = oldItem == newItem
            }
        }
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view)

    companion object {
        @JvmField var stateSource: (() -> Observable<FriendsListInput>)? = null
        @JvmField var onAddFriend: () -> Unit = {}
        @JvmField var onAccept: (FriendUserUi) -> Unit = {}
        @JvmField var onDecline: (FriendUserUi) -> Unit = {}
        @JvmField var onCancel: (FriendUserUi) -> Unit = {}
        @JvmField var onMessage: (FriendUserUi) -> Unit = {}
        @JvmField var onCall: (FriendUserUi) -> Unit = {}
        @JvmField var onProfile: (FriendUserUi) -> Unit = {}
    }
}

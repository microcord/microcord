package com.discord.widgets.settings.account

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppViewFlipper
import com.discord.widgets.friends.BlockedUserUi
import com.discord.widgets.friends.BlockedUsersState
import com.discord.widgets.friends.FriendUserUi
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton
import rx.Observable
import rx.Subscription

class WidgetSettingsBlockedUsers : Fragment(R.layout.widget_settings_blocked_users) {
    private var subscription: Subscription? = null
    private val listAdapter = Adapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.title = getString(R.string.user_settings_blocked_users)
        view.findViewById<RecyclerView>(R.id.blocked_users_list_recycler).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = listAdapter
        }
    }

    override fun onStart() {
        super.onStart()
        subscription = (stateSource?.invoke() ?: Observable.just(BlockedUsersState.Loaded(emptyList())))
            .distinctUntilChanged()
            .subscribe { state -> view?.post { if (view != null) show(state) } }
    }

    override fun onStop() {
        subscription?.unsubscribe()
        subscription = null
        super.onStop()
    }

    private fun show(state: BlockedUsersState) {
        val flipper = requireView().findViewById<AppViewFlipper>(R.id.blocked_users_flipper)
        when (state) {
            BlockedUsersState.Loading -> flipper.visibility = View.INVISIBLE
            is BlockedUsersState.Loaded -> {
                flipper.visibility = View.VISIBLE
                flipper.displayedChild = if (state.users.isEmpty()) 1 else 0
                listAdapter.submitList(state.users)
            }
        }
    }

    private class Adapter : ListAdapter<BlockedUserUi, Holder>(DIFF) {
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = getItem(position).user.id
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.widget_settings_item_blocked_user, parent, false),
        )
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val user = getItem(position).user
            holder.itemView.contentDescription = user.username
            holder.itemView.setOnClickListener { onProfile(user) }
            holder.itemView.findViewById<SimpleDraweeView>(R.id.blocked_user_item_avatar).setImageURI(user.avatarUrl)
            holder.itemView.findViewById<TextView>(R.id.blocked_user_item_name).text = user.username
            holder.itemView.findViewById<MaterialButton>(R.id.unblock_user_button).setOnClickListener { onUnblock(user) }
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<BlockedUserUi>() {
                override fun areItemsTheSame(oldItem: BlockedUserUi, newItem: BlockedUserUi) = oldItem.user.id == newItem.user.id
                override fun areContentsTheSame(oldItem: BlockedUserUi, newItem: BlockedUserUi) = oldItem == newItem
            }
        }
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view)

    companion object {
        @JvmField var stateSource: (() -> Observable<BlockedUsersState>)? = null
        @JvmField var onUnblock: (FriendUserUi) -> Unit = {}
        @JvmField var onProfile: (FriendUserUi) -> Unit = {}
    }
}

package com.discord.widgets.friends

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.models.presence.Presence
import com.discord.models.user.User
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.user.UserUtils.discriminatorWithPadding
import com.discord.views.StatusView
import com.discord.widgets.user.search.WidgetGlobalSearchModel
import com.facebook.drawee.view.SimpleDraweeView

/**
 * The result list shared by the nearby scan and the add-friend search.
 *
 * A row shows one of four mutually exclusive trailing controls, decided entirely by the
 * relationship the viewer already has with that user: a green check (already friends), accept +
 * decline (they have a request pending with us), decline alone (we have one pending with them), or
 * a send button (no relationship yet). That is why [addFriendRequestUserIds] exists — the pending
 * request ids come from a different store than the rows themselves, and arrive separately.
 */
class WidgetFriendsAddUserAdapter(recycler: RecyclerView) :
    MGRecyclerAdapterSimple<WidgetFriendsAddUserAdapter.ItemUser>(recycler) {

    private val outgoingRequestUserIds = HashSet<Long>()
    private val incomingRequestUserIds = HashSet<Long>()

    private var sendHandler: ((String, Int) -> Unit)? = null
    private var acceptHandler: ((Long) -> Unit)? = null
    private var declineHandler: ((Long, Boolean) -> Unit)? = null
    private var onItemClick: (Long) -> Unit = {}

    data class ItemUser(
        val user: User,
        val aliases: List<CharSequence>,
        val presence: Presence? = null,
        val isFriend: Boolean,
    ) : MGRecyclerDataPayload {
        override val type: Int = TYPE_USER
        override val key: String = user.id.toString()

        companion object {
            fun create(item: WidgetGlobalSearchModel.ItemUser): ItemUser =
                ItemUser(item.user, item.aliases, item.presence, item.isFriend)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder =
        if (viewType == TYPE_USER) UserViewHolder(this) else throw invalidViewTypeException(viewType)

    fun setSendHandler(handler: (String, Int) -> Unit) {
        sendHandler = handler
    }

    fun setAcceptHandler(handler: (Long) -> Unit) {
        acceptHandler = handler
    }

    fun setDeclineHandler(handler: (Long, Boolean) -> Unit) {
        declineHandler = handler
    }

    fun setOnItemClick(onItemClick: (Long) -> Unit) {
        this.onItemClick = onItemClick
    }

    /** Replaces both pending-request sets and rebinds; the sets change together or not at all. */
    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun addFriendRequestUserIds(outgoingIds: Collection<Long>, incomingIds: Collection<Long>) {
        outgoingRequestUserIds.clear()
        outgoingRequestUserIds.addAll(outgoingIds)
        incomingRequestUserIds.clear()
        incomingRequestUserIds.addAll(incomingIds)
        notifyDataSetChanged()
    }

    class UserViewHolder(adapter: WidgetFriendsAddUserAdapter) :
        MGRecyclerViewHolder<WidgetFriendsAddUserAdapter, ItemUser>(
            R.layout.widget_add_friend_user_list_item,
            adapter,
        ) {

        private val avatar: SimpleDraweeView = itemView.findViewById(R.id.add_friend_user_item_avatar)
        private val name: TextView = itemView.findViewById(R.id.add_friend_user_item_name)
        private val nameSecondary: TextView =
            itemView.findViewById(R.id.add_friend_user_item_name_secondary)
        private val status: StatusView = itemView.findViewById(R.id.add_friend_user_item_status)
        private val sendButton: AppCompatImageView =
            itemView.findViewById(R.id.add_friend_user_send_button)
        private val checkImage: AppCompatImageView =
            itemView.findViewById(R.id.add_friend_user_check_image)
        private val acceptButton: AppCompatImageView =
            itemView.findViewById(R.id.add_friend_user_accept_button)
        private val declineButton: AppCompatImageView =
            itemView.findViewById(R.id.add_friend_user_decline_button)

        override fun onConfigure(position: Int, data: ItemUser) {
            super.onConfigure(position, data)
            val user = data.user
            IconUtils.setIcon(avatar, user)
            // The last alias is the most specific match (nickname over global name over username).
            name.text = data.aliases.lastOrNull() ?: user.username
            nameSecondary.text = user.discriminatorWithPadding
            status.setPresence(data.presence)
            status.visibility = if (data.presence != null) View.VISIBLE else View.GONE

            val userId = user.id
            val outgoing = userId in adapter.outgoingRequestUserIds
            val incoming = userId in adapter.incomingRequestUserIds

            acceptButton.setOnClickListener { adapter.acceptHandler?.invoke(userId) }
            declineButton.setOnClickListener { adapter.declineHandler?.invoke(userId, incoming) }
            sendButton.setOnClickListener {
                adapter.sendHandler?.invoke(user.username, user.discriminator)
            }
            itemView.setOnClickListener { adapter.onItemClick(userId) }

            checkImage.visibility = if (data.isFriend) View.VISIBLE else View.GONE
            sendButton.visibility =
                if (!data.isFriend && !outgoing && !incoming) View.VISIBLE else View.GONE
            acceptButton.visibility = if (!data.isFriend && incoming) View.VISIBLE else View.GONE
            declineButton.visibility =
                if (!data.isFriend && (outgoing || incoming)) View.VISIBLE else View.GONE
        }
    }

    private companion object {
        private const val TYPE_USER = 1
    }
}

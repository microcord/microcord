package com.discord.widgets.friends

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.i18n.FormatUtils
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.user.User
import com.discord.utilities.drawable.setCompoundDrawablesCompat
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.views.AvatarDecorationView
import com.discord.views.StatusView
import com.discord.views.VitoImageView
import com.discord.stores.StoreStream
import com.discord.utilities.user.UserUtils.displayName
import com.discord.utilities.user.UserUtils.getDisplayName

/**
 * Rows for the Friends tab.
 *
 * Reconstruction of the reference `com.discord.widgets.friends.WidgetFriendsListAdapter`. Every
 * interaction is a callback the fragment installs; the adapter itself performs no navigation and
 * makes no REST calls, which is what lets the whole list be driven by
 * [FriendsListViewModel.ViewState].
 */
class WidgetFriendsListAdapter(recycler: RecyclerView) :
    MGRecyclerAdapterSimple<FriendsListViewModel.Item>(recycler) {

    var onClickUserProfile: (View, User) -> Unit = { _, _ -> }
    var onClickCall: (User) -> Unit = {}
    var onClickChat: (User) -> Unit = {}
    var onClickAcceptFriend: (User) -> Unit = {}
    var onClickDeclineFriend: (User, Int) -> Unit = { _, _ -> }
    var onClickPendingHeaderExpand: () -> Unit = {}
    var onClickSuggestedHeaderExpandCollapse: () -> Unit = {}
    var onClickApproveSuggestion: (User) -> Unit = {}
    var onClickRemoveSuggestion: (Long) -> Unit = {}
    var onClickContactSyncUpsell: () -> Unit = {}
    var onClickContactSyncUpsellLongClick: (View) -> Unit = {}

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, FriendsListViewModel.Item> = when (viewType) {
        FriendsListViewModel.Item.TYPE_FRIEND -> ItemUser(this)
        FriendsListViewModel.Item.TYPE_PENDING_FRIEND -> ItemPendingUser(this)
        FriendsListViewModel.Item.TYPE_PENDING_HEADER -> ItemPendingHeader(this)
        FriendsListViewModel.Item.TYPE_HEADER -> ItemHeader(this)
        FriendsListViewModel.Item.TYPE_SUGGESTED_FRIEND_HEADER -> ItemSuggestedFriendHeader(this)
        FriendsListViewModel.Item.TYPE_SUGGESTED_FRIEND -> ItemSuggestedFriend(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.widget_friends_list_adapter_suggested_friend, parent, false)
                as SuggestedFriendView,
            this,
        )
        FriendsListViewModel.Item.TYPE_CONTACT_SYNC_UPSELL -> ItemContactSyncUpsell(this)
        else -> throw invalidViewTypeException(viewType)
    }

    /** Common base so every holder is typed against the shared adapter and item hierarchy. */
    abstract class Item : MGRecyclerViewHolder<WidgetFriendsListAdapter, FriendsListViewModel.Item> {
        constructor(layout: Int, adapter: WidgetFriendsListAdapter) : super(layout, adapter)
        constructor(view: View, adapter: WidgetFriendsListAdapter) : super(view, adapter)
    }

    class ItemUser(adapter: WidgetFriendsListAdapter) :
        Item(R.layout.widget_friends_list_adapter_item_friend, adapter) {

        private val name: TextView = itemView.findViewById(R.id.friends_list_item_name)
        private val activity: SimpleDraweeSpanTextView =
            itemView.findViewById(R.id.friends_list_item_activity)
        private val avatar: VitoImageView = itemView.findViewById(R.id.friends_list_item_avatar)
        private val status: StatusView = itemView.findViewById(R.id.friends_list_item_status)
        private val avatarDecoration: AvatarDecorationView =
            itemView.findViewById(R.id.friends_list_item_avatar_decoration)
        private val callButton: AppCompatImageView =
            itemView.findViewById(R.id.friends_list_item_call_button)
        private val chatButton: AppCompatImageView =
            itemView.findViewById(R.id.friends_list_item_chat_button)

        override fun onConfigure(position: Int, data: FriendsListViewModel.Item) {
            super.onConfigure(position, data)
            val item = data as FriendsListViewModel.Item.Friend
            itemView.setOnClickListener { view -> adapter.onClickUserProfile(view, item.user) }
            name.text = item.user.getDisplayName(
                StoreStream.getUserRelationships().getNickname(item.user.id),
            )
            status.setPresence(item.presence)
            PresenceUtils.setStatusLineWithLeadingIcon(
                item.presence,
                item.isApplicationStreaming,
                activity,
                showFallbackStatusText = true,
            )
            IconUtils.setIcon(avatar, item.user, R.dimen.avatar_size_standard)
            avatarDecoration.bind(item.user.avatarDecoration)
            callButton.setOnClickListener { adapter.onClickCall(item.user) }
            chatButton.setOnClickListener { adapter.onClickChat(item.user) }
        }
    }

    class ItemPendingUser(adapter: WidgetFriendsListAdapter) :
        Item(R.layout.widget_friends_list_adapter_item_pending, adapter) {

        private val name: TextView = itemView.findViewById(R.id.friends_list_item_name)
        private val activity: SimpleDraweeSpanTextView =
            itemView.findViewById(R.id.friends_list_item_activity)
        private val avatar: VitoImageView = itemView.findViewById(R.id.friends_list_item_avatar)
        private val status: StatusView = itemView.findViewById(R.id.friends_list_item_status)
        private val avatarDecoration: AvatarDecorationView =
            itemView.findViewById(R.id.friends_list_item_avatar_decoration)
        private val acceptButton: AppCompatImageView =
            itemView.findViewById(R.id.friends_list_item_accept_button)
        private val declineButton: AppCompatImageView =
            itemView.findViewById(R.id.friends_list_item_decline_button)

        override fun onConfigure(position: Int, data: FriendsListViewModel.Item) {
            super.onConfigure(position, data)
            val item = data as FriendsListViewModel.Item.PendingFriendRequest
            itemView.setOnClickListener { view -> adapter.onClickUserProfile(view, item.user) }
            name.text = item.user.username
            status.setPresence(item.presence)

            val incoming = item.relationshipType == ModelUserRelationship.TYPE_INVITE_INCOMING
            activity.setTextAndVisibilityBy(
                itemView.context.getString(
                    if (incoming) R.string.incoming_friend_request else R.string.outgoing_friend_request,
                ),
            )
            // Only an incoming request can be accepted; an outgoing one can only be cancelled.
            acceptButton.visibility = if (incoming) View.VISIBLE else View.GONE
            acceptButton.contentDescription = itemView.context.getString(
                R.string.friends_row_action_button_accessibility_label_accept,
            )
            declineButton.contentDescription = itemView.context.getString(
                if (incoming) {
                    R.string.friends_row_action_button_accessibility_label_decline
                } else {
                    R.string.friends_row_action_button_accessibility_label_cancel
                },
            )

            IconUtils.setIcon(avatar, item.user, R.dimen.avatar_size_standard)
            avatarDecoration.bind(item.user.avatarDecoration)
            acceptButton.setOnClickListener { adapter.onClickAcceptFriend(item.user) }
            declineButton.setOnClickListener {
                adapter.onClickDeclineFriend(item.user, item.relationshipType)
            }
        }
    }

    class ItemHeader(adapter: WidgetFriendsListAdapter) :
        Item(R.layout.widget_friends_list_adapter_item_header, adapter) {

        private val headerText: TextView =
            itemView.findViewById(R.id.friends_list_item_header_text)

        override fun onConfigure(position: Int, data: FriendsListViewModel.Item) {
            super.onConfigure(position, data)
            val item = data as FriendsListViewModel.Item.Header
            headerText.text =
                formatCount(itemView.context, item.titleStringResId, item.count)
        }
    }

    class ItemPendingHeader(adapter: WidgetFriendsListAdapter) :
        Item(R.layout.widget_friends_list_expandable_header, adapter) {

        private val headerText: TextView =
            itemView.findViewById(R.id.friends_list_pending_item_header_text)
        private val expandButton: TextView =
            itemView.findViewById(R.id.friends_list_expandable_header_button)

        override fun onConfigure(position: Int, data: FriendsListViewModel.Item) {
            super.onConfigure(position, data)
            val item = data as FriendsListViewModel.Item.PendingHeader
            headerText.text = formatCount(itemView.context, item.titleStringResId, item.count)
            bindExpandButton(
                expandButton,
                showExpandButton = item.showExpandButton,
                isExpanded = item.isPendingSectionExpanded,
                onClick = adapter.onClickPendingHeaderExpand,
            )
        }
    }

    class ItemSuggestedFriendHeader(adapter: WidgetFriendsListAdapter) :
        Item(R.layout.widget_friends_list_expandable_header, adapter) {

        private val headerText: TextView =
            itemView.findViewById(R.id.friends_list_pending_item_header_text)
        private val expandButton: TextView =
            itemView.findViewById(R.id.friends_list_expandable_header_button)

        override fun onConfigure(position: Int, data: FriendsListViewModel.Item) {
            super.onConfigure(position, data)
            val item = data as FriendsListViewModel.Item.SuggestedFriendsHeader
            headerText.text = formatCount(
                itemView.context,
                R.string.friends_friend_suggestions_header,
                item.count,
            )
            bindExpandButton(
                expandButton,
                showExpandButton = item.showExpandButton,
                isExpanded = item.isExpanded,
                onClick = adapter.onClickSuggestedHeaderExpandCollapse,
            )
        }
    }

    class ItemSuggestedFriend(
        private val suggestedFriendView: SuggestedFriendView,
        adapter: WidgetFriendsListAdapter,
    ) : Item(suggestedFriendView, adapter) {

        override fun onConfigure(position: Int, data: FriendsListViewModel.Item) {
            super.onConfigure(position, data)
            val item = data as FriendsListViewModel.Item.SuggestedFriend
            val user = item.suggestion.user
            suggestedFriendView.setOnClickListener { view ->
                adapter.onClickUserProfile(view, user)
            }
            suggestedFriendView.setAvatarUrl(user.id, user.discriminator, user.avatar)
            suggestedFriendView.setUsername(user.username)
            suggestedFriendView.setPublicName(item.suggestion.publicName)
            suggestedFriendView.setOnSendClicked { adapter.onClickApproveSuggestion(user) }
            suggestedFriendView.setOnDeclineClicked { adapter.onClickRemoveSuggestion(user.id) }
        }
    }

    /**
     * The first row when the contact-sync experiment is on and the user has not connected contacts.
     * Long-pressing it opens the dismiss menu, which is the only way to get rid of the row.
     */
    class ItemContactSyncUpsell(adapter: WidgetFriendsListAdapter) :
        Item(R.layout.widget_friends_list_contact_sync_upsell, adapter) {

        private val card: View =
            itemView.findViewById(R.id.friends_list_contact_sync_upsell_container)

        override fun onConfigure(position: Int, data: FriendsListViewModel.Item) {
            super.onConfigure(position, data)
            card.setOnClickListener { adapter.onClickContactSyncUpsell() }
            card.setOnLongClickListener { view ->
                adapter.onClickContactSyncUpsellLongClick(view)
                true
            }
        }
    }

    private companion object {

        /**
         * Section titles carry a single Discord `{token}` placeholder whose name differs per string
         * (`{online}`, `{offline}`, `{pendingRequestNumber}`, `{count}`). The reference renders them
         * positionally; [FormatUtils] is name-keyed, so the token actually present is discovered and
         * filled with the count.
         */
        fun formatCount(context: Context, @StringRes titleStringResId: Int, count: Int): String {
            val template = context.getString(titleStringResId)
            val token = PLACEHOLDER.find(template)?.groupValues?.get(1)
            return if (token == null) {
                FormatUtils.format(template, emptyMap())
            } else {
                FormatUtils.format(template, token to count)
            }
        }

        fun bindExpandButton(
            button: TextView,
            showExpandButton: Boolean,
            isExpanded: Boolean,
            onClick: () -> Unit,
        ) {
            if (!showExpandButton) {
                button.visibility = View.GONE
                return
            }
            button.visibility = View.VISIBLE
            button.setText(
                if (isExpanded) {
                    R.string.friends_pending_request_expand_collapse
                } else {
                    R.string.friends_pending_request_expand
                },
            )
            button.setCompoundDrawablesCompat(
                end = if (isExpanded) R.drawable.ic_arrow_up_24dp else R.drawable.ic_arrow_right_24dp,
            )
            button.setOnClickListener { onClick() }
        }

        val PLACEHOLDER = Regex("""\{(\S+?)\}""")
    }
}

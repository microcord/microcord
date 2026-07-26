package com.discord.widgets.contact_sync

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.widgets.contact_sync.WidgetContactSyncViewModel.Item
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.checkbox.MaterialCheckBox

/**
 * The "we found these people" list at the end of contact sync.
 *
 * Every row starts checked; the user unchecks the people they do not want to send a request to.
 * Nothing is sent until the submit button is pressed — this adapter only reports selection changes.
 *
 * PRIVACY: the nickname line is the name the user's own address book holds for that person. It is
 * rendered and never logged; [Item.FriendSuggestionItem] redacts it from `toString`.
 */
class ContactSyncFriendSuggestionListAdapter(
    recycler: RecyclerView,
) : MGRecyclerAdapterSimple<Item>(recycler) {

    var onClickFriendSuggestion: (Long, Boolean) -> Unit = { _, _ -> }

    private val spacing = recycler.context.resources.getDimensionPixelSize(R.dimen.suggestion_spacing)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MGRecyclerViewHolder<*, Item> =
        ItemFriendSuggestion(this)

    class ItemFriendSuggestion(
        adapter: ContactSyncFriendSuggestionListAdapter,
    ) : MGRecyclerViewHolder<ContactSyncFriendSuggestionListAdapter, Item>(
        R.layout.view_selectable_friend_suggestion,
        adapter,
    ) {
        private val container: ConstraintLayout = itemView.findViewById(R.id.friend_suggestion_container)
        private val avatar: SimpleDraweeView = itemView.findViewById(R.id.friend_suggestion_avatar)
        private val name: TextView = itemView.findViewById(R.id.friend_suggestion_name)
        private val discriminator: TextView = itemView.findViewById(R.id.friend_suggestion_discriminator)
        private val nickname: TextView = itemView.findViewById(R.id.friend_suggestion_nickname)
        private val selected: MaterialCheckBox = itemView.findViewById(R.id.friend_suggestion_selected)

        @SuppressLint("SetTextI18n")
        override fun onConfigure(position: Int, data: Item) {
            super.onConfigure(position, data)
            val item = data as? Item.FriendSuggestionItem ?: return
            val user = item.suggestion.suggestedUser

            // The layout cannot express the inter-row gap, so it is applied per row. The bottom
            // padding is the only edge that changes; left/right come from the layout.
            container.setPadding(container.paddingLeft, 0, container.paddingRight, adapter.spacing)

            name.text = user.username
            discriminator.text = "#${user.discriminator}"

            val contactName = item.suggestion.reasons
                .firstOrNull { it.platformType == CONTACTS_PLATFORM_TYPE }
                ?.name
            if (contactName == null) {
                nickname.visibility = View.GONE
            } else {
                nickname.text = contactName
                nickname.visibility = if (contactName.isBlank()) View.GONE else View.VISIBLE
            }

            IconUtils.setIcon(
                avatar,
                IconUtils.getForUser(user.id, user.avatar?.value, user.discriminator.toIntOrNull()),
                R.dimen.avatar_size_standard,
            )

            // DELIBERATE DIVERGENCE. The reference assigns the listener *after* `setChecked`, but a
            // recycled holder still carries the previous row's listener at that moment: flipping the
            // box for the new user fires the old row's callback with the old user id, so the wrong
            // person gets selected. Detaching first makes the bind inert, which matters because the
            // selection decides who receives a friend request.
            selected.setOnCheckedChangeListener(null)
            selected.isChecked = item.selected
            selected.setOnCheckedChangeListener { _, isChecked ->
                adapter.onClickFriendSuggestion(user.id, isChecked)
            }
        }

        private companion object {
            const val CONTACTS_PLATFORM_TYPE = "contacts"
        }
    }
}

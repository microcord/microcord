package com.discord.widgets.user.search

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.utilities.user.UserUtils.getDisplayName
import com.discord.R
import com.discord.chips_view.ChipsView
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.presence.Presence
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.views.StatusView
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import rx.Observable
import rx.Subscription
import java.util.Locale
import com.discord.utilities.icon.IconUtils

/**
 * Picks the friends for a new group DM.
 *
 * Previously this lived inside `WidgetGlobalSearchDialog`, which in the reference is the
 * quick-switcher host and nothing else. The picker is unchanged; only its home moved. Its reference
 * counterpart is `widgets/guilds/invite/WidgetGroupInviteFriends`, which is not reconstructed yet —
 * fold this into it when it lands.
 */
class WidgetGroupDmPickerDialog : DialogFragment() {

    private data class Recipient(val user: User, val presence: Presence?)

    private class RecipientChip(val user: User) : ChipsView.a {
        override fun getDisplayString() = user.username
    }

    private var subscription: Subscription? = null
    private var recipients = emptyList<Recipient>()
    private val selected = linkedMapOf<Long, User>()
    private var query = ""
    private var creating = false
    private var recipientAdapter: RecipientAdapter? = null
    private var chips: ChipsView<Long, RecipientChip>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.AppTheme_GlobalSearchDialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.widget_group_invite_friends, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findToolbar()?.apply {
            title = getString(R.string.new_group_dm)
            setNavigationIcon(R.drawable.ic_close_grey_24dp)
            setNavigationOnClickListener { dismiss() }
        }
        chips = view.findViewById<ChipsView<Long, RecipientChip>>(R.id.group_invite_friends_chips).apply {
            textChangedListener = { value ->
                query = value.toString()
                renderRecipients()
            }
            chipDeletedListener = { chip ->
                selected.remove(chip.user.id)
                renderRecipients()
            }
        }
        recipientAdapter = RecipientAdapter(::toggleRecipient)
        view.findViewById<RecyclerView>(R.id.group_invite_friends_recycler).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = recipientAdapter
        }
        view.findViewById<FloatingActionButton>(R.id.group_invite_friends_save_fab)
            .setOnClickListener { createDm() }
        renderRecipients()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        subscription = observeRecipients()
    }

    override fun onStop() {
        subscription?.unsubscribe()
        subscription = null
        super.onStop()
    }

    private fun observeRecipients(): Subscription = Observable.combineLatest(
        StoreStream.getUsers().observeAllUsers(),
        StoreStream.getUserRelationships().observe(),
        StoreStream.getPresences().observeAllPresences(),
    ) { users, relationships, presences ->
        relationships.asSequence()
            .filter { it.value == ModelUserRelationship.TYPE_FRIEND }
            .mapNotNull { users[it.key]?.let { user -> Recipient(user, presences[user.id]) } }
            .sortedBy { it.user.username.lowercase(Locale.ROOT) }
            .toList()
    }.subscribe { values ->
        view?.post {
            recipients = values
            // A friend removed while the sheet is open must not stay selected.
            val friendIds = values.mapTo(hashSetOf()) { it.user.id }
            selected.keys.retainAll(friendIds)
            chips?.d(selected.keys)
            renderRecipients()
        }
    }

    private fun renderRecipients() {
        val root = view ?: return
        val filtered = recipients.filter {
            recipientMatches(it.user.username, it.user.discriminator, query)
        }
        recipientAdapter?.items = filtered.map { it to (it.user.id in selected) }
        root.findViewById<com.discord.app.AppViewFlipper>(R.id.group_invite_friends_view_flipper)
            .displayedChild = if (filtered.isEmpty()) 1 else 0
        root.findViewById<FloatingActionButton>(R.id.group_invite_friends_save_fab).apply {
            visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
            isEnabled = !creating
        }
        val remaining = MAX_RECIPIENTS - selected.size
        root.findToolbar()?.subtitle = if (remaining == 0) {
            getString(R.string.group_dm_invite_will_fill_mobile)
        } else {
            resources.getQuantityString(R.plurals.group_dm_invite_remaining_number, remaining)
                .replace("{number}", remaining.toString())
        }
    }

    private fun toggleRecipient(recipient: Recipient) {
        val user = recipient.user
        if (selected.remove(user.id) != null) {
            chips?.d(selected.keys)
        } else {
            if (selected.size >= MAX_RECIPIENTS) {
                Toast.makeText(requireContext(), R.string.group_dm_invite_full_sub, Toast.LENGTH_SHORT).show()
                return
            }
            selected[user.id] = user
            val chipName = user.getDisplayName(
                StoreStream.getUserRelationships().getNickname(user.id),
            )
            chips?.b(
                chipName,
                getString(R.string.remove_role_or_user).replace("!!{name}!!", chipName),
                user.id,
                RecipientChip(user),
            )
        }
        renderRecipients()
    }

    private fun createDm() {
        val recipientIds = selected.keys.toList()
        if (creating || recipientIds.isEmpty() || recipientIds.size > MAX_RECIPIENTS) return
        creating = true
        renderRecipients()
        StoreStream.createPrivateChannel(recipientIds) { result ->
            activity?.runOnUiThread {
                creating = false
                if (result.isSuccess) {
                    dismissAllowingStateLoss()
                } else {
                    renderRecipients()
                    Toast.makeText(context, R.string.error_occurred_try_again, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private class RecipientAdapter(
        private val click: (Recipient) -> Unit,
    ) : RecyclerView.Adapter<RecipientAdapter.Holder>() {
        var items = emptyList<Pair<Recipient, Boolean>>()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int) = items[position].first.user.id
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.widget_group_invite_friends_item, parent, false),
        )

        override fun onBindViewHolder(holder: Holder, position: Int) =
            holder.bind(items[position], click)

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            fun bind(value: Pair<Recipient, Boolean>, click: (Recipient) -> Unit) {
                val recipient = value.first
                val user = recipient.user
                itemView.findViewById<TextView>(R.id.friends_list_item_name).text = user.username
                itemView.findViewById<CheckBox>(R.id.user_selected_checkbox).isChecked = value.second
                itemView.findViewById<StatusView>(R.id.friends_list_item_status)
                    .setPresence(recipient.presence)
                itemView.findViewById<TextView>(R.id.friends_list_item_game).apply {
                    text = recipient.presence?.activityText().orEmpty()
                    visibility = if (text.isBlank()) View.GONE else View.VISIBLE
                }
                itemView.findViewById<SimpleDraweeView>(R.id.friends_list_item_avatar)
                    .setImageURI(userAvatar(user))
                itemView.findViewById<View>(R.id.friend_container).setOnClickListener { click(recipient) }
                itemView.contentDescription = user.username
            }
        }
    }

    companion object {
        private const val MAX_RECIPIENTS = 9

        fun show(manager: FragmentManager) =
            WidgetGroupDmPickerDialog().show(manager, WidgetGroupDmPickerDialog::class.java.name)
    }
}

internal fun recipientMatches(username: String, discriminator: Int, query: String): Boolean {
    val filter = query.trim()
    return filter.isEmpty() ||
        username.contains(filter, true) ||
        "$username#$discriminator".contains(filter, true)
}

private fun Presence.activityText() =
    activities.orEmpty().firstOrNull()?.let { it.state ?: it.details ?: it.name }

private fun userAvatar(user: User): String = user.avatar?.let {
    val extension = if (it.startsWith("a_")) "gif" else "webp"
    "https://cdn.discordapp.com/avatars/${user.id}/$it.$extension?size=64"
} ?: IconUtils.getDefaultAvatar(IconUtils.getDefaultAvatarIndex(user.id, user.discriminator, user.isWebhook))

private fun View.findToolbar(): Toolbar? {
    if (this is Toolbar) return this
    if (this !is ViewGroup) return null
    repeat(childCount) { getChildAt(it).findToolbar()?.let { found -> return found } }
    return null
}

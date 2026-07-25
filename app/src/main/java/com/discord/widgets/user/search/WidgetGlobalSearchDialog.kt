package com.discord.widgets.user.search

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.chips_view.ChipsView
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.presence.Presence
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.views.StatusView
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import rx.Observable
import rx.Subscription
import java.util.Locale

class WidgetGlobalSearchDialog : DialogFragment() {
    private data class DmCandidate(
        val channel: Channel,
        val label: String,
        val searchText: String,
        val presence: Presence?,
        val mentions: Int,
        val unread: Boolean,
    )

    private data class Recipient(val user: User, val presence: Presence?)

    private class RecipientChip(val user: User) : ChipsView.a {
        override fun getDisplayString() = user.username
    }

    private var subscription: Subscription? = null
    private var dmCandidates = emptyList<DmCandidate>()
    private var recipients = emptyList<Recipient>()
    private val selected = linkedMapOf<Long, User>()
    private var query = ""
    private var creating = false
    private var searchAdapter: DmAdapter? = null
    private var recipientAdapter: RecipientAdapter? = null
    private var chips: ChipsView<Long, RecipientChip>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.AppTheme_GlobalSearchDialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(if (newDm()) R.layout.widget_group_invite_friends else R.layout.widget_global_search, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (newDm()) setupRecipientPicker(view) else setupQuickSwitcher(view)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        subscription = if (newDm()) observeRecipients() else observeDms()
    }

    override fun onStop() {
        subscription?.unsubscribe()
        subscription = null
        super.onStop()
    }

    private fun setupQuickSwitcher(view: View) {
        view.findViewById<View>(R.id.global_search_guild_list).visibility = View.GONE
        view.findViewById<View>(R.id.global_search_add_a_friend_btn).visibility = View.GONE
        view.findViewById<View>(R.id.global_search_join_guild_btn).visibility = View.GONE
        searchAdapter = DmAdapter { channel ->
            StoreStream.selectChannel(0, channel.id)
            dismiss()
        }
        view.findViewById<RecyclerView>(R.id.global_search_recycler).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = searchAdapter
        }
        view.findViewById<TextInputLayout>(R.id.global_search_bar).apply {
            setStartIconOnClickListener { dismiss() }
            setEndIconOnClickListener { editText?.text?.clear() }
        }
        view.findViewById<TextInputEditText>(R.id.global_search_bar_text).apply {
            setHint(R.string.dm_search_placeholder)
            setText("@")
            setSelection(length())
            addTextChangedListener(textWatcher { value ->
                query = value
                renderDms()
            })
        }
    }

    private fun setupRecipientPicker(view: View) {
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
        view.findViewById<FloatingActionButton>(R.id.group_invite_friends_save_fab).setOnClickListener { createDm() }
        renderRecipients()
    }

    private fun observeDms(): Subscription {
        val channelsAndPresences = Observable.combineLatest(
            StoreStream.getChannels().observePrivateChannels(),
            StoreStream.getPresences().observeAllPresences(),
        ) { channels, presences -> channels.values to presences }
        val readState = Observable.combineLatest(
            StoreStream.getReadStates().getUnreadChannelIds(),
            StoreStream.getReadStates().getMentionCountsByChannel(),
        ) { unread, mentions -> unread to mentions }
        return Observable.combineLatest(channelsAndPresences, readState) { state, read ->
            state.first.map { channel ->
                val recipient = channel.recipients.orEmpty().firstOrNull()
                val label = channel.name?.takeIf(String::isNotBlank)
                    ?: channel.recipients.orEmpty().joinToString(", ") { it.username }
                DmCandidate(
                    channel = channel,
                    label = label,
                    searchText = buildString {
                        append(label)
                        channel.recipients.orEmpty().forEach { append(' ').append(it.username).append('#').append(it.discriminator) }
                    },
                    presence = recipient?.let { state.second[it.id] },
                    mentions = read.second[channel.id] ?: 0,
                    unread = channel.id in read.first,
                )
            }.sortedByDescending { it.channel.lastMessageId }
        }.subscribe { values -> view?.post { dmCandidates = values; renderDms() } }
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
    }.subscribe { values -> view?.post {
        recipients = values
        val friendIds = values.mapTo(hashSetOf()) { it.user.id }
        selected.keys.retainAll(friendIds)
        chips?.d(selected.keys)
        renderRecipients()
    } }

    private fun renderDms() {
        val root = view ?: return
        val filter = normalizedDmQuery(query)
        val items = dmCandidates.filter { filter.isEmpty() || it.searchText.contains(filter, true) }.map {
            WidgetGlobalSearchModel.ItemChannel(
                matchedResult = dmMatchedResult(it.label, filter),
                channel = it.channel,
                mentions = it.mentions,
                unread = it.unread,
                presence = it.presence,
            )
        }
        searchAdapter?.items = items
        root.findViewById<com.discord.app.AppViewFlipper>(R.id.global_search_view_flipper).displayedChild = when {
            items.isNotEmpty() -> 0
            filter.isEmpty() -> 1
            else -> 2
        }
    }

    private fun renderRecipients() {
        val root = view ?: return
        val filtered = recipients.filter { recipientMatches(it.user.username, it.user.discriminator, query) }
        recipientAdapter?.items = filtered.map { it to (it.user.id in selected) }
        root.findViewById<com.discord.app.AppViewFlipper>(R.id.group_invite_friends_view_flipper).displayedChild =
            if (filtered.isEmpty()) 1 else 0
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
            chips?.b(
                user.username,
                getString(R.string.remove_role_or_user).replace("!!{name}!!", user.username),
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
                if (result.isSuccess) dismissAllowingStateLoss()
                else {
                    renderRecipients()
                    Toast.makeText(context, R.string.error_occurred_try_again, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun newDm() = requireArguments().getBoolean(ARG_NEW_DM)

    private class DmAdapter(private val click: (Channel) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items = emptyList<WidgetGlobalSearchModel.ItemChannel>()
            set(value) { field = value; notifyDataSetChanged() }

        override fun getItemCount() = if (items.isEmpty()) 0 else items.size + 1
        override fun getItemViewType(position: Int) = if (position == 0) TYPE_HEADER else TYPE_DM
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == TYPE_HEADER) HeaderHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.widget_channel_search_item_header, parent, false) as TextView,
            ) else DmHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.widget_channel_search_item, parent, false) as ViewGlobalSearchItem,
            )

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is HeaderHolder) holder.text.setText(R.string.direct_messages)
            else (holder as DmHolder).bind(items[position - 1], click)
        }

        private class HeaderHolder(val text: TextView) : RecyclerView.ViewHolder(text)
        private class DmHolder(private val item: ViewGlobalSearchItem) : RecyclerView.ViewHolder(item) {
            fun bind(value: WidgetGlobalSearchModel.ItemChannel, click: (Channel) -> Unit) {
                item.onConfigure(value)
                item.setOnClickListener { click(value.channel) }
            }
        }

        companion object { const val TYPE_HEADER = 0; const val TYPE_DM = 1 }
    }

    private class RecipientAdapter(private val click: (Recipient) -> Unit) : RecyclerView.Adapter<RecipientAdapter.Holder>() {
        var items = emptyList<Pair<Recipient, Boolean>>()
            set(value) { field = value; notifyDataSetChanged() }

        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = items[position].first.user.id
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.widget_group_invite_friends_item, parent, false),
        )
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position], click)

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            fun bind(value: Pair<Recipient, Boolean>, click: (Recipient) -> Unit) {
                val recipient = value.first
                val user = recipient.user
                itemView.findViewById<TextView>(R.id.friends_list_item_name).text = user.username
                itemView.findViewById<CheckBox>(R.id.user_selected_checkbox).isChecked = value.second
                itemView.findViewById<StatusView>(R.id.friends_list_item_status).setPresence(recipient.presence)
                itemView.findViewById<TextView>(R.id.friends_list_item_game).apply {
                    text = recipient.presence?.activityText().orEmpty()
                    visibility = if (text.isBlank()) View.GONE else View.VISIBLE
                }
                itemView.findViewById<SimpleDraweeView>(R.id.friends_list_item_avatar).setImageURI(userAvatar(user))
                itemView.findViewById<View>(R.id.friend_container).setOnClickListener { click(recipient) }
                itemView.contentDescription = user.username
            }
        }
    }

    companion object {
        private const val ARG_NEW_DM = "new_dm"
        private const val MAX_RECIPIENTS = 9

        fun show(manager: FragmentManager, newDm: Boolean) = WidgetGlobalSearchDialog().apply {
            arguments = Bundle().apply { putBoolean(ARG_NEW_DM, newDm) }
        }.show(manager, WidgetGlobalSearchDialog::class.java.name)
    }
}

internal fun normalizedDmQuery(value: String) = value.trim().removePrefix("@").trim()

internal fun dmMatchedResult(label: String, query: String): WidgetGlobalSearchModel.MatchedResult {
    val index = if (query.isEmpty()) -1 else label.indexOf(query, ignoreCase = true)
    return WidgetGlobalSearchModel.MatchedResult(label, index, query.length)
}

internal fun recipientMatches(username: String, discriminator: Int, query: String): Boolean {
    val filter = query.trim()
    return filter.isEmpty() || username.contains(filter, true) || "$username#$discriminator".contains(filter, true)
}

private fun Presence.activityText() = activities.orEmpty().firstOrNull()?.let { it.state ?: it.details ?: it.name }

private fun userAvatar(user: User): String = user.avatar?.let {
    val extension = if (it.startsWith("a_")) "gif" else "webp"
    "https://cdn.discordapp.com/avatars/${user.id}/$it.$extension?size=64"
} ?: "asset://asset/images/default_avatar_${user.discriminator.rem(5)}.png"

private fun View.findToolbar(): Toolbar? {
    if (this is Toolbar) return this
    if (this !is ViewGroup) return null
    repeat(childCount) { getChildAt(it).findToolbar()?.let { found -> return found } }
    return null
}

private fun textWatcher(changed: (String) -> Unit) = object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = changed(s?.toString().orEmpty())
    override fun afterTextChanged(s: Editable?) = Unit
}

package com.discord.widgets.chat.pins

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.models.message.Message
import com.discord.stores.StorePinnedMessages
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.model.ChatListRows
import rx.Subscription

class WidgetChannelPinnedMessages : Fragment(R.layout.widget_channel_pinned_messages) {
    interface Host {
        fun pinnedMessagesStore(): StorePinnedMessages
        fun channelForPins(channelId: Long): Channel?
        fun jumpToPinnedMessage(channelId: Long, messageId: Long)
        fun onPinnedMessageLongPress(message: Message): Boolean = false
    }

    private var subscription: Subscription? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val host = host()
        val channelId = requireArguments().getLong(ARG_CHANNEL_ID)
        val channel = host.channelForPins(channelId)
        val adapter = WidgetChatListAdapter(
            onClick = { host.jumpToPinnedMessage(channelId, it.id) },
            onLongClick = host::onPinnedMessageLongPress,
        )
        view.findViewById<RecyclerView>(R.id.channel_pinned_messages_recycler).adapter = adapter
        view.findDescendant(Toolbar::class.java)?.apply {
            setTitle(R.string.pinned_messages)
            subtitle = channel?.name
            setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (parentFragmentManager.backStackEntryCount > 0) parentFragmentManager.popBackStack()
                else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        subscription = host.pinnedMessagesStore().observeForChannel(channelId).subscribe(
            { messages -> view.post {
                adapter.submitList(rows(channelId, channel, messages))
            } },
            { view.post { adapter.submitList(listOf(ChatListEntry.EmptyPins(channelId, channel?.type == Channel.DM))) } },
        )
    }

    override fun onDestroyView() {
        subscription?.unsubscribe()
        subscription = null
        super.onDestroyView()
    }

    private fun host(): Host = (parentFragment as? Host) ?: (activity as? Host)
        ?: error("WidgetChannelPinnedMessages host must implement Host")

    private fun <T : View> View.findDescendant(type: Class<T>): T? {
        if (type.isInstance(this)) return type.cast(this)
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) getChildAt(index).findDescendant(type)?.let { return it }
        return null
    }

    companion object {
        private const val ARG_CHANNEL_ID = "channel_id"
        internal fun rows(channelId: Long, channel: Channel?, messages: List<Message>?): List<ChatListEntry> = when {
            messages == null -> listOf(ChatListEntry.Loading(channelId))
            messages.isEmpty() -> listOf(ChatListEntry.EmptyPins(channelId, channel?.type == Channel.DM))
            else -> messages.flatMapIndexed { index, message ->
                ChatListRows.pinnedMessage(message).toMutableList().apply {
                    if (index < messages.lastIndex) add(ChatListEntry.Divider("$channelId:${message.id}"))
                }
            }
        }
        fun newInstance(channelId: Long) = WidgetChannelPinnedMessages().apply {
            arguments = Bundle().apply { putLong(ARG_CHANNEL_ID, channelId) }
        }
    }
}

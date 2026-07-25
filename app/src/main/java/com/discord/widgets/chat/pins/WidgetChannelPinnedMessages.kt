package com.discord.widgets.chat.pins

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.models.message.Message
import com.discord.stores.StorePinnedMessages
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
        val adapter = PinnedAdapter(
            onClick = { host.jumpToPinnedMessage(channelId, it.id) },
            onLongClick = host::onPinnedMessageLongPress,
        )
        view.findViewById<RecyclerView>(R.id.channel_pinned_messages_recycler).adapter = adapter
        view.findDescendant(Toolbar::class.java)?.apply {
            setTitle(R.string.pinned_messages)
            subtitle = channel?.name
            setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        }
        adapter.showLoading()
        subscription = host.pinnedMessagesStore().observeForChannel(channelId).subscribe(
            { messages -> view.post { adapter.submit(messages, channel?.type == Channel.DM) } },
            { view.post { adapter.showEmpty(channel?.type == Channel.DM) } },
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

    private class PinnedAdapter(
        private val onClick: (Message) -> Unit,
        private val onLongClick: (Message) -> Boolean,
    ) : RecyclerView.Adapter<PinnedAdapter.Holder>() {
        private var rows: List<Row> = listOf(Row.Status(R.string.loading))

        sealed class Row {
            data class Item(val message: Message) : Row()
            data class Status(val text: Int) : Row()
        }

        class Holder(val title: TextView, val body: TextView, view: View) : RecyclerView.ViewHolder(view)

        fun showLoading() = setRows(listOf(Row.Status(R.string.loading)))
        fun showEmpty(dm: Boolean) = setRows(listOf(Row.Status(if (dm) R.string.no_pins_in_dm else R.string.no_pins_in_channel)))
        fun submit(messages: List<Message>, dm: Boolean) =
            if (messages.isEmpty()) showEmpty(dm) else setRows(messages.map(Row::Item))

        private fun setRows(value: List<Row>) {
            rows = value
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return Holder(view.findViewById(android.R.id.text1), view.findViewById(android.R.id.text2), view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val row = rows[position]) {
                is Row.Status -> {
                    holder.title.setText(row.text)
                    holder.body.text = ""
                    holder.itemView.setOnClickListener(null)
                    holder.itemView.setOnLongClickListener(null)
                }
                is Row.Item -> {
                    val message = row.message
                    holder.title.text = message.author?.username.orEmpty()
                    holder.body.text = message.content.orEmpty()
                    holder.itemView.contentDescription = listOf(holder.title.text, holder.body.text)
                        .filter(CharSequence::isNotEmpty).joinToString(", ")
                    holder.itemView.setOnClickListener { onClick(message) }
                    holder.itemView.setOnLongClickListener { onLongClick(message) }
                }
            }
        }
    }

    companion object {
        private const val ARG_CHANNEL_ID = "channel_id"
        fun newInstance(channelId: Long) = WidgetChannelPinnedMessages().apply {
            arguments = Bundle().apply { putLong(ARG_CHANNEL_ID, channelId) }
        }
    }
}

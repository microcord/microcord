package com.discord.widgets.chat.list.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.models.message.Message
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.flexbox.FlexboxLayout
import com.discord.views.UploadProgressView
import com.discord.stores.StoreMessageUploads
import com.discord.api.message.reaction.MessageReactionEmoji
import java.text.DateFormat
import java.util.Date

class WidgetChatListAdapter(
    private val onClick: (Message) -> Unit = {},
    private val onLongClick: (Message) -> Boolean = { false },
    private val onCancelUpload: (String) -> Unit = {},
    private val onReactionClick: (Message, MessageReactionEmoji) -> Unit = { _, _ -> },
) : ListAdapter<ChatListEntry, WidgetChatListAdapter.Holder>(DIFF) {
    init {
        setHasStableIds(true)
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    val oldestMessageId: Long?
        get() = currentList.mapNotNull(ChatListEntry::messageId).minOrNull()

    override fun getItemId(position: Int) = getItem(position).stableId
    override fun getItemViewType(position: Int) = viewType(getItem(position))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(layoutFor(viewType), parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        when (row) {
            is ChatListEntry.Loading, is ChatListEntry.Spacer -> Unit
            is ChatListEntry.Start -> bindStart(holder.itemView, row.channel)
            is ChatListEntry.DayDivider -> bindDivider(holder.itemView, formatDate(row.timestamp), false)
            is ChatListEntry.UnreadDivider -> bindDivider(holder.itemView, holder.itemView.context.getString(R.string.new_messages_divider), true)
            is ChatListEntry.Header -> bindHeader(holder.itemView, row)
            is ChatListEntry.Minimal -> bindMinimal(holder.itemView, row)
            is ChatListEntry.System -> bindSystem(holder.itemView, row)
            is ChatListEntry.Failed -> bindFailed(holder.itemView, row)
            is ChatListEntry.Attachment -> bindAttachment(holder.itemView, row)
            is ChatListEntry.Reactions -> bindReactions(holder.itemView, row)
            is ChatListEntry.UploadProgress -> bindUploadProgress(holder.itemView, row)
        }
        (row as? ChatListEntry.MessageRow)?.let { messageRow ->
            holder.itemView.setOnClickListener { onClick(messageRow.message) }
            holder.itemView.setOnLongClickListener { onLongClick(messageRow.message) }
        } ?: run {
            holder.itemView.setOnClickListener(null)
            holder.itemView.setOnLongClickListener(null)
        }
    }

    fun positionForMessage(messageId: Long): Int =
        currentList.indexOfFirst { it.messageId == messageId }

    private fun bindHeader(root: View, row: ChatListEntry.Header) {
        val author = row.message.author
        root.findViewById<TextView>(R.id.chat_list_adapter_item_text_name).text = author?.username ?: "Unknown User"
        root.findViewById<TextView>(R.id.chat_list_adapter_item_text).text = row.text
        root.findViewById<TextView>(R.id.chat_list_adapter_item_text_timestamp).text = formatTimestamp(row.message)
        root.findViewById<View>(R.id.chat_list_adapter_item_communication_disabled_icon).visibility = View.GONE
        root.findViewById<View>(R.id.chat_list_adapter_item_text_role_icon).visibility = View.GONE
        root.findViewById<View>(R.id.chat_list_adapter_item_text_tag).visibility = View.GONE
        root.findViewById<View>(R.id.chat_list_adapter_item_text_decorator).visibility = View.GONE
        root.findViewById<SimpleDraweeView>(R.id.chat_list_adapter_item_text_avatar).setImageURI(avatarUrl(author?.id, author?.avatar?.value))
        root.contentDescription = accessibility(row.message, row.text)
    }

    private fun bindMinimal(root: View, row: ChatListEntry.Minimal) {
        root.findViewById<TextView>(R.id.chat_list_adapter_item_text).text = row.text
        root.contentDescription = accessibility(row.message, row.text)
    }

    private fun bindSystem(root: View, row: ChatListEntry.System) {
        root.findViewById<TextView>(R.id.system_text).text = row.text
        root.findViewById<TextView>(R.id.system_timestamp).text = formatTimestamp(row.message)
        root.contentDescription = accessibility(row.message, row.text)
    }

    private fun bindFailed(root: View, row: ChatListEntry.Failed) {
        val author = row.message.author
        root.findViewById<TextView>(R.id.chat_list_adapter_item_text_name).text = author?.username ?: "Unknown User"
        root.findViewById<TextView>(R.id.chat_list_adapter_item_text).text = row.text
        root.findViewById<SimpleDraweeView>(R.id.chat_list_adapter_item_text_avatar).setImageURI(avatarUrl(author?.id, author?.avatar?.value))
        root.contentDescription = "${accessibility(row.message, row.text)}. ${root.context.getString(R.string.send_message_failure)}"
    }

    private fun bindAttachment(root: View, row: ChatListEntry.Attachment) {
        root.findViewById<View>(R.id.chat_list_item_attachment_inline_media).visibility = View.GONE
        root.findViewById<View>(R.id.chat_list_item_attachment_card).visibility = View.VISIBLE
        root.findViewById<TextView>(R.id.chat_list_item_attachment_name).text = row.attachment.filename
        root.findViewById<TextView>(R.id.chat_list_item_attachment_description).text = "${row.attachment.size} bytes"
        root.contentDescription = "Attachment ${row.attachment.filename}"
    }

    private fun bindReactions(root: View, row: ChatListEntry.Reactions) {
        root.findViewById<FlexboxLayout>(R.id.chat_list_item_reactions).apply {
            removeAllViews()
            row.reactions.forEach { reaction ->
                val label = "${reaction.emoji.name ?: reaction.emoji.id ?: "Reaction"} ${reaction.count}"
                addView(TextView(context).apply {
                    text = label
                    setPadding(12, 6, 12, 6)
                    contentDescription = label
                    setOnClickListener { onReactionClick(row.message, reaction.emoji) }
                })
            }
        }
        root.contentDescription = row.reactions.joinToString(prefix = "Reactions: ") {
            "${it.emoji.name ?: it.emoji.id ?: "Reaction"} ${it.count}"
        }
    }

    private fun bindUploadProgress(root: View, row: ChatListEntry.UploadProgress) {
        val view = root.findViewById<UploadProgressView>(R.id.upload_progress_1)
        val nonce = row.message.nonce.orEmpty()
        when (val state = row.state) {
            StoreMessageUploads.MessageUploadState.None -> view.configure("", -2)
            is StoreMessageUploads.MessageUploadState.Preprocessing -> view.configure(
                state.displayName ?: root.context.getString(R.string.uploading_files).replace("{count}", state.numFiles.toString()),
                -1,
            )
            is StoreMessageUploads.MessageUploadState.Uploading -> view.configure(
                state.uploads.singleOrNull()?.name ?: root.context.getString(R.string.uploading_files)
                    .replace("{count}", state.uploads.size.toString()),
                (state.progress.fraction * 100).toInt(),
            )
        }
        listOf(R.id.progress_cancel_top, R.id.progress_cancel_centered).forEach { id ->
            root.findViewById<View>(id).setOnClickListener { onCancelUpload(nonce) }
        }
    }

    private fun bindDivider(root: View, text: String, unread: Boolean) {
        root.findViewById<TextView>(R.id.divider_text).apply {
            this.text = text
            if (unread) setTextColor(ContextCompat.getColor(context, R.color.status_red_500))
        }
        root.contentDescription = text
    }

    private fun bindStart(root: View, channel: Channel) {
        val privateChannel = channel.type == Channel.DM || channel.type == Channel.GROUP_DM
        if (privateChannel) {
            val recipient = channel.recipients.orEmpty().firstOrNull()
            root.findViewById<TextView>(R.id.chat_list_adapter_item_private_channel_start_header).text =
                channel.name ?: recipient?.username ?: "Direct Message"
            root.findViewById<TextView>(R.id.private_channel_start_text).text = if (channel.type == Channel.GROUP_DM) {
                "Welcome to the beginning of this group."
            } else {
                "This is the beginning of your direct message history."
            }
            root.findViewById<SimpleDraweeView>(R.id.private_channel_start_image).setImageURI(
                avatarUrl(recipient?.id, recipient?.avatar?.value),
            )
        } else {
            val name = channel.name ?: "channel"
            root.findViewById<TextView>(R.id.chat_list_adapter_item_header).text = "#$name"
            root.findViewById<TextView>(R.id.chat_list_adapter_item_subheader2).text = "This is the start of the $name channel."
            root.findViewById<View>(R.id.chat_list_adapter_item_subheader1).visibility = View.GONE
            root.findViewById<View>(R.id.chat_list_adapter_item_edit_channel).visibility = View.GONE
            root.findViewById<View>(R.id.chat_list_adapter_tags).visibility = View.GONE
        }
        root.contentDescription = "Start of ${channel.name ?: "chat"}"
    }

    private fun accessibility(message: Message, text: String): String = buildString {
        append(message.author?.username ?: "System")
        append(", ")
        append(text)
        append(", ")
        append(formatTimestamp(message))
        if (message.isFailed()) append(", failed to send")
    }

    private fun formatTimestamp(message: Message): String {
        val time = message.timestamp?.dateTimeMillis ?: 0L
        val rendered = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))
        return if (message.editedTimestamp != null) "$rendered (${messageTextEdited})" else rendered
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view)

    companion object {
        private const val LOADING = 0
        private const val START = 1
        private const val PRIVATE_START = 2
        private const val SPACER = 3
        private const val DIVIDER = 4
        private const val HEADER = 5
        private const val MINIMAL = 6
        private const val SYSTEM = 7
        private const val FAILED = 8
        private const val ATTACHMENT = 9
        private const val REACTIONS = 10
        private const val UPLOAD = 11
        private const val messageTextEdited = "edited"

        internal fun viewType(row: ChatListEntry) = when (row) {
            is ChatListEntry.Loading -> LOADING
            is ChatListEntry.Start -> if (row.channel.type == Channel.DM || row.channel.type == Channel.GROUP_DM) {
                PRIVATE_START
            } else {
                START
            }
            is ChatListEntry.Spacer -> SPACER
            is ChatListEntry.DayDivider, is ChatListEntry.UnreadDivider -> DIVIDER
            is ChatListEntry.Header -> HEADER
            is ChatListEntry.Minimal -> MINIMAL
            is ChatListEntry.System -> SYSTEM
            is ChatListEntry.Failed -> FAILED
            is ChatListEntry.Attachment -> ATTACHMENT
            is ChatListEntry.Reactions -> REACTIONS
            is ChatListEntry.UploadProgress -> UPLOAD
        }

        internal fun layoutFor(type: Int) = when (type) {
            LOADING -> R.layout.widget_chat_list_adapter_item_loading
            START -> R.layout.widget_chat_list_adapter_item_start
            PRIVATE_START -> R.layout.widget_chat_list_adapter_item_private_channel_start
            SPACER -> R.layout.widget_chat_list_adapter_item_spacer
            DIVIDER -> R.layout.widget_chat_list_adapter_item_text_divider
            HEADER -> R.layout.widget_chat_list_adapter_item_text
            MINIMAL -> R.layout.widget_chat_list_adapter_item_minimal
            SYSTEM -> R.layout.widget_chat_list_adapter_item_system
            FAILED -> R.layout.widget_chat_list_adapter_item_failed
            ATTACHMENT -> R.layout.widget_chat_list_adapter_item_attachment
            REACTIONS -> R.layout.widget_chat_list_adapter_item_reactions
            UPLOAD -> R.layout.widget_chat_list_adapter_item_upload_progress
            else -> error("Unknown chat row type: $type")
        }

        private fun formatDate(timestamp: Long) =
            DateFormat.getDateInstance(DateFormat.LONG).format(Date(timestamp))

        private fun avatarUrl(userId: Long?, hash: String?) =
            if (userId != null && hash != null) "https://cdn.discordapp.com/avatars/$userId/$hash.webp?size=96" else null
    }

}

private val DIFF = object : DiffUtil.ItemCallback<ChatListEntry>() {
    override fun areItemsTheSame(oldItem: ChatListEntry, newItem: ChatListEntry) = oldItem.stableId == newItem.stableId
    override fun areContentsTheSame(oldItem: ChatListEntry, newItem: ChatListEntry) = oldItem == newItem
}

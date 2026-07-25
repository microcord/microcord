package com.discord.widgets.chat.list.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import android.util.TypedValue
import android.text.Spannable
import android.text.SpannableString
import android.text.style.LeadingMarginSpan
import android.view.Gravity
import androidx.core.text.BidiFormatter
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.models.message.Message
import com.discord.models.member.GuildMember
import com.discord.stores.StoreStream
import com.discord.utilities.textprocessing.MessageTextRenderer
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.flexbox.FlexboxLayout
import com.discord.views.UploadProgressView
import com.discord.stores.StoreMessageUploads
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.api.message.reaction.MessageReaction
import com.discord.views.ReactionView
import java.text.DateFormat
import java.util.Date

class WidgetChatListAdapter(
    private val onClick: (Message) -> Unit = {},
    private val onLongClick: (Message) -> Boolean = { false },
    private val onCancelUpload: (String) -> Unit = {},
    private val onReactionClick: (Message, MessageReactionEmoji, Boolean) -> Unit = { _, _, _ -> },
    private val onReactionLongClick: (Message, MessageReaction) -> Boolean = { _, _ -> false },
    private val onAddReaction: (Message) -> Unit = {},
    private val onAuthorClick: (Long) -> Unit = {},
    private val onAuthorMention: (Long) -> Unit = {},
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
            is ChatListEntry.Loading, is ChatListEntry.Spacer, is ChatListEntry.Divider -> Unit
            is ChatListEntry.EmptyPins -> holder.itemView.findViewById<TextView>(R.id.chat_list_empty_pins_text)
                .setText(if (row.dm) R.string.no_pins_in_dm else R.string.no_pins_in_channel)
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
        root.findViewById<View>(R.id.chat_list_adapter_item_thread_embed_spine)?.visibility = View.GONE
        root.findViewById<View>(R.id.chat_list_adapter_item_highlighted_bg)?.visibility = View.GONE
        root.findViewById<View>(R.id.chat_list_adapter_item_gutter_bg)?.visibility = View.GONE
        val author = row.message.author
        root.findViewById<TextView>(R.id.chat_list_adapter_item_text_name).text = author?.username ?: "Unknown User"
        val plainText = bindMessageText(root.findViewById(R.id.chat_list_adapter_item_text), row.message, row.text)
        root.findViewById<TextView>(R.id.chat_list_adapter_item_text_timestamp).text = formatTimestamp(row.message)
        root.findViewById<View>(R.id.chat_list_adapter_item_communication_disabled_icon).visibility = View.GONE
        root.findViewById<View>(R.id.chat_list_adapter_item_text_role_icon).visibility = View.GONE
        root.findViewById<View>(R.id.chat_list_adapter_item_text_tag).visibility = View.GONE
        root.findViewById<View>(R.id.chat_list_adapter_item_text_decorator).visibility = View.GONE
        bindReply(root, row.message)
        root.findViewById<SimpleDraweeView>(R.id.chat_list_adapter_item_text_avatar).setImageURI(avatarUrl(author?.id, author?.avatar?.value))
        root.findViewById<View>(R.id.chat_list_adapter_item_text_avatar).setOnClickListener {
            author?.id?.let(onAuthorClick)
        }
        root.findViewById<View>(R.id.chat_list_adapter_item_text_name).setOnClickListener {
            author?.id?.let(onAuthorMention)
        }
        root.contentDescription = accessibility(row.message, plainText)
    }

    private fun bindReply(root: View, message: Message) {
        val holder = root.findViewById<View>(R.id.chat_list_adapter_item_text_decorator)
        val link = root.findViewById<View>(R.id.chat_list_adapter_item_text_decorator_reply_link_icon)
        val shown = message.type == 19 && message.messageReference != null
        holder.visibility = if (shown) View.VISIBLE else View.GONE
        link.visibility = holder.visibility
        if (!shown) return

        val reply = message.referencedMessage
        val icon = root.findViewById<ImageView>(R.id.chat_list_adapter_item_text_decorator_reply_icon)
        val avatar = root.findViewById<SimpleDraweeView>(R.id.chat_list_adapter_item_text_decorator_avatar)
        val name = root.findViewById<TextView>(R.id.chat_list_adapter_item_text_decorator_reply_name)
        val content = root.findViewById<SimpleDraweeSpanTextView>(R.id.chat_list_adapter_item_text_reply_content)
        val author = reply?.author
        val member = message.guildId?.let { guildId -> author?.id?.let { StoreStream.getGuilds().getMember(guildId, it) } }
        icon.visibility = if (author == null) View.VISIBLE else View.GONE
        avatar.visibility = if (author == null) View.GONE else View.VISIBLE
        avatar.setImageURI(avatarUrl(author?.id, author?.avatar?.value))
        name.text = author?.let {
            val displayName = member?.nick?.takeUnless(String::isBlank) ?: reply.member?.nick?.takeUnless(String::isBlank) ?: it.username
            if (message.mentions.orEmpty().any { mentioned -> mentioned.id == it.id }) "@$displayName" else displayName
        }.orEmpty()
        name.visibility = if (name.text.isEmpty()) View.GONE else View.VISIBLE
        name.setTextColor(GuildMember.getColor(member, themedColor(root, R.attr.colorHeaderPrimary)))
        val replyText = when {
            reply == null -> root.context.getString(R.string.reply_quote_message_not_loaded)
            !reply.content.isNullOrEmpty() -> reply.content.orEmpty()
            !reply.stickers.isNullOrEmpty() || !reply.stickerItems.isNullOrEmpty() -> root.context.getString(R.string.reply_quote_sticker_mobile)
            !reply.attachments.isNullOrEmpty() || !reply.embeds.isNullOrEmpty() -> root.context.getString(R.string.reply_quote_no_text_content_mobile)
            else -> ""
        }
        val leading = root.findViewById<View>(R.id.chat_list_adapter_item_reply_leading_views).run {
            measure(0, 0)
            LeadingMarginSpan.Standard(measuredWidth, 0)
        }
        val replyPlain = if (reply != null && !reply.content.isNullOrEmpty()) {
            val rendered = MessageTextRenderer.render(Message(reply).copy(guildId = message.guildId), content)
            rendered.text.setSpan(leading, 0, rendered.text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            content.setDraweeSpanStringBuilder(rendered.text)
            rendered.plainText
        } else {
            content.text = SpannableString(replyText).apply {
                setSpan(leading, 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            replyText
        }
        holder.layoutDirection = if (BidiFormatter.getInstance().isRtl(content.text)) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        holder.setOnClickListener(reply?.let { quoted -> View.OnClickListener { onClick(Message(quoted)) } })
        holder.contentDescription = if (author == null) replyPlain else listOf(name.text, replyPlain).joinToString(", ")
    }

    private fun bindMinimal(root: View, row: ChatListEntry.Minimal) {
        root.findViewById<View>(R.id.chat_list_adapter_item_highlighted_bg)?.visibility = View.GONE
        root.findViewById<View>(R.id.chat_list_adapter_item_gutter_bg)?.visibility = View.GONE
        val plainText = bindMessageText(root.findViewById(R.id.chat_list_adapter_item_text), row.message, row.text)
        root.contentDescription = accessibility(row.message, plainText)
    }

    private fun bindSystem(root: View, row: ChatListEntry.System) {
        root.findViewById<View>(R.id.system_welcome_cta_button)?.visibility = View.GONE
        root.findViewById<TextView>(R.id.system_text).text = row.text
        root.findViewById<TextView>(R.id.system_timestamp).text = formatTimestamp(row.message)
        root.contentDescription = accessibility(row.message, row.text)
    }

    private fun bindFailed(root: View, row: ChatListEntry.Failed) {
        root.findViewById<View>(R.id.chat_list_adapter_item_failed_upload_list)?.visibility =
            if (row.message.localAttachments.isNullOrEmpty()) View.GONE else View.VISIBLE
        val author = row.message.author
        root.findViewById<TextView>(R.id.chat_list_adapter_item_text_name).text = author?.username ?: "Unknown User"
        val plainText = bindMessageText(root.findViewById(R.id.chat_list_adapter_item_text), row.message, row.text)
        root.findViewById<SimpleDraweeView>(R.id.chat_list_adapter_item_text_avatar).setImageURI(avatarUrl(author?.id, author?.avatar?.value))
        root.contentDescription = "${accessibility(row.message, plainText)}. ${root.context.getString(R.string.send_message_failure)}"
    }

    private fun bindAttachment(root: View, row: ChatListEntry.Attachment) {
        root.findViewById<View>(R.id.chat_list_adapter_item_highlighted_bg)?.visibility = View.GONE
        root.findViewById<View>(R.id.chat_list_adapter_item_gutter_bg)?.visibility = View.GONE
        root.findViewById<View>(R.id.chat_list_item_attachment_inline_media).visibility = View.GONE
        root.findViewById<View>(R.id.chat_list_item_attachment_card).visibility = View.VISIBLE
        root.findViewById<TextView>(R.id.chat_list_item_attachment_name).text = row.attachment.filename
        root.findViewById<TextView>(R.id.chat_list_item_attachment_description).text = "${row.attachment.size} bytes"
        root.contentDescription = "Attachment ${row.attachment.filename}"
    }

    private fun bindReactions(root: View, row: ChatListEntry.Reactions) {
        root.findViewById<View>(R.id.chat_list_adapter_item_highlighted_bg)?.visibility = View.GONE
        root.findViewById<View>(R.id.chat_list_adapter_item_gutter_bg)?.visibility = View.GONE
        val reactions = root.findViewById<FlexboxLayout>(R.id.chat_list_item_reactions)
        val quickAdd = reactions.findViewById<ImageView>(R.id.reaction_quick_add)
        if (quickAdd != null) reactions.removeView(quickAdd)
        val existing = reactions.childCount
        row.reactions.forEachIndexed { index, reaction ->
            val view = if (index < existing) reactions.getChildAt(index) as ReactionView else ReactionView(reactions.context).also {
                val horizontalPadding = dp(root, 6)
                it.setPadding(horizontalPadding, it.paddingTop, horizontalPadding, it.paddingBottom)
                it.background = ContextCompat.getDrawable(it.context, themedResource(it, R.attr.theme_chat_reaction_background))
                it.gravity = Gravity.CENTER
                reactions.addView(it)
                it.layoutParams.height = dp(root, 26)
            }
            view.visibility = View.VISIBLE
            view.configure(reaction, row.message.id, row.animateEmojis)
            view.contentDescription = root.context.getString(R.string.reaction_tooltip_n)
                .replace("{n}", reaction.count.toString())
                .replace("!!", "")
                .replace("{emojiName}", reaction.emoji.name ?: reaction.emoji.id ?: "")
            view.setOnClickListener { onReactionClick(row.message, reaction.emoji, row.canAddReactions) }
            view.setOnLongClickListener {
                onReactionLongClick(row.message, reaction)
                true
            }
        }
        for (index in row.reactions.size until existing) reactions.getChildAt(index).visibility = View.GONE
        if (row.reactions.size < REACTION_LIMIT && row.canCreateReactions) {
            val add = LayoutInflater.from(reactions.context).inflate(R.layout.reaction_quick_add, reactions, false)
            add.setOnClickListener { onAddReaction(row.message) }
            reactions.addView(add)
        }
        root.contentDescription = null
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
        root.findViewById<View>(R.id.progress_cancel_top).visibility = View.GONE
        root.findViewById<View>(R.id.progress_cancel_centered).visibility = View.VISIBLE
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

    private fun bindMessageText(view: SimpleDraweeSpanTextView, message: Message, text: String): String =
        MessageTextRenderer.render(message.copy(content = text), view).also {
            view.setDraweeSpanStringBuilder(it.text)
        }.plainText

    private fun themedColor(view: View, attribute: Int) = TypedValue().let {
        view.context.theme.resolveAttribute(attribute, it, true)
        it.data
    }

    private fun themedResource(view: View, attribute: Int) = TypedValue().let {
        view.context.theme.resolveAttribute(attribute, it, true)
        it.resourceId
    }

    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).toInt()

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
        private const val EMPTY_PINS = 12
        private const val PIN_DIVIDER = 13
        private const val messageTextEdited = "edited"
        internal const val REACTION_LIMIT = 20

        internal fun viewType(row: ChatListEntry) = when (row) {
            is ChatListEntry.Loading -> LOADING
            is ChatListEntry.EmptyPins -> EMPTY_PINS
            is ChatListEntry.Divider -> PIN_DIVIDER
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
            EMPTY_PINS -> R.layout.widget_chat_list_adapter_item_empty_pins
            PIN_DIVIDER -> R.layout.widget_chat_list_adapter_item_divider
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

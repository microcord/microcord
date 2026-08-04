package com.discord.widgets.chat.list.adapter

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.models.user.CoreUser
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessagePreprocessor
import com.discord.utilities.textprocessing.MessageTextRenderer
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.views.ThemedColor
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.ThreadEmbedEntry
import com.facebook.drawee.view.SimpleDraweeView

/**
 * The thread preview card that hangs off the message which started the thread.
 *
 * The card always shows the thread name and reply count; the second line is a preview of the newest
 * reply when there is one to show, and otherwise an italic explanation of why there is not — the
 * thread is archived, empty, or its newest message is something (a sticker, an image) that cannot
 * be quoted as text.
 */
class WidgetChatListAdapterItemThreadEmbed(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_thread_embed, adapter) {

    private val highlightedBackground: View =
        itemView.findViewById(R.id.chat_list_adapter_item_highlighted_bg)
    private val gutterBackground: View = itemView.findViewById(R.id.chat_list_adapter_item_gutter_bg)
    private val container: View = itemView.findViewById(R.id.thread_embed_container)
    private val name: TextView = itemView.findViewById(R.id.thread_embed_name)
    private val messagesCount: TextView = itemView.findViewById(R.id.thread_embed_messages_count)
    private val messagesCountChevron: ImageView =
        itemView.findViewById(R.id.thread_embed_messages_count_chevron)
    private val icon: ImageView = itemView.findViewById(R.id.thread_embed_icon)
    private val messageHeader: View = itemView.findViewById(R.id.thread_embed_most_recent_message_header)
    private val messageAvatar: SimpleDraweeView =
        itemView.findViewById(R.id.thread_embed_most_recent_message_avatar)
    private val messageName: TextView = itemView.findViewById(R.id.thread_embed_most_recent_message_name)
    private val messageContent: SimpleDraweeSpanTextView =
        itemView.findViewById(R.id.thread_embed_most_recent_message_content)

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as? ThreadEmbedEntry ?: return

        name.text = entry.thread.name
        container.setOnClickListener { adapter.onThreadClicked(entry.thread) }
        itemView.setOnLongClickListener {
            adapter.onThreadLongClicked(entry.thread)
            true
        }
        configureMessageCount(entry.threadMessageCount)
        configureMostRecentMessageText(entry)
        configureCellHighlight(entry.parentMessage, highlightedBackground, gutterBackground, entry.isPendingReply)
    }

    /** A thread past fifty replies stops counting; the exact number stops being informative. */
    private fun configureMessageCount(count: Int) {
        messagesCount.visibility = if (count == 0) View.GONE else View.VISIBLE
        if (count > 0) {
            messagesCount.text = if (count >= MAX_DISPLAYED_MESSAGE_COUNT) {
                "$MAX_DISPLAYED_MESSAGE_COUNT+"
            } else {
                count.toString()
            }
        }
        // With no replies the chevron is decoration; with replies it reads as a link.
        val chevronAttr = if (count == 0) R.attr.colorInteractiveNormal else R.attr.colorTextLink
        messagesCountChevron.imageTintList =
            ColorStateList.valueOf(ThemedColor.resolve(messagesCountChevron.context, chevronAttr))
    }

    private fun configureMostRecentMessageText(entry: ThreadEmbedEntry) {
        val context = container.context
        val mostRecent = entry.mostRecentMessage
        messageHeader.visibility = View.GONE

        val author = mostRecent?.author
        val member = entry.mostRecentMessageGuildMember
        when {
            entry.thread.threadMetadata?.archived == true -> setNoMostRecentMessage(
                context.getString(R.string.thread_archived),
                nickOrUsernames = entry.nickOrUsernames,
                icon = themedDrawable(context, R.attr.ic_thread_archived_clock),
            )
            entry.threadMessageCount == 0 -> setNoMostRecentMessage(
                context.getString(R.string.no_thread_messages),
                nickOrUsernames = entry.nickOrUsernames,
            )
            mostRecent?.content == null || author == null || member == null -> setNoMostRecentMessage(
                context.getString(R.string.no_recent_thread_messages),
                nickOrUsernames = entry.nickOrUsernames,
            )
            mostRecent.hasStickers() -> setNoMostRecentMessage(
                context.getString(R.string.reply_quote_sticker_mobile),
                mostRecentMessage = mostRecent,
                nickOrUsernames = entry.nickOrUsernames,
                authorGuildMember = member,
            )
            !mostRecent.hasAttachments() && !mostRecent.hasEmbeds() ->
                setMostRecentMessage(mostRecent, entry, member)
            else -> setNoMostRecentMessage(
                context.getString(R.string.reply_quote_no_text_content_mobile),
                mostRecentMessage = mostRecent,
                nickOrUsernames = entry.nickOrUsernames,
                authorGuildMember = member,
                icon = themedDrawable(context, R.attr.ic_flex_input_image),
            )
        }
    }

    private fun setMostRecentMessage(
        mostRecentMessage: Message,
        entry: ThreadEmbedEntry,
        authorGuildMember: GuildMember,
    ) {
        // The preview borrows the reply rules — one line, no block quotes — and is capped so a wall
        // of text in the thread cannot make the card grow without bound.
        val renderContext = MessageTextRenderer
            .createRenderContext(messageContent.context, mostRecentMessage)
            .copy(
                isAnimationEnabled = entry.animateEmojis,
                userNames = entry.nickOrUsernames,
                roles = entry.roleMentions,
            )
        val text = DiscordParser.parseChannelMessage(
            messageContent.context,
            mostRecentMessage.content,
            renderContext,
            MessagePreprocessor(
                myUserId = renderContext.myId,
                visibleSpoilerNodeIndices = null,
                embeds = null,
                shouldJumboify = false,
                maxNodes = MAX_THREAD_MESSAGE_AST_NODES,
            ),
            DiscordParser.ParserOptions.REPLY,
        )
        setAuthorAndAvatar(mostRecentMessage, entry.nickOrUsernames, authorGuildMember)
        text.setSpan(
            leadingMarginSpan(showAuthor = true),
            0,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        messageContent.setDraweeSpanStringBuilder(text)
        messageContent.setTypeface(messageContent.typeface, Typeface.NORMAL)
        setIcon(null)
    }

    private fun setNoMostRecentMessage(
        messageText: String,
        mostRecentMessage: Message? = null,
        nickOrUsernames: Map<Long, String>,
        authorGuildMember: GuildMember? = null,
        icon: Drawable? = null,
    ) {
        // The trailing space keeps the italic tail clear of the card's right edge.
        val text = SpannableString("$messageText ")
        setAuthorAndAvatar(mostRecentMessage, nickOrUsernames, authorGuildMember)
        val showAuthor = mostRecentMessage != null && authorGuildMember != null
        text.setSpan(
            leadingMarginSpan(showAuthor),
            0,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        messageContent.text = text
        messageContent.setTypeface(messageContent.typeface, Typeface.ITALIC)
        setIcon(icon)
    }

    private fun setAuthorAndAvatar(
        mostRecentMessage: Message?,
        nickOrUsernames: Map<Long, String>,
        authorGuildMember: GuildMember?,
    ) {
        val author = mostRecentMessage?.author ?: return
        if (authorGuildMember == null) return
        messageHeader.visibility = View.VISIBLE
        messageName.text = nickOrUsernames[author.id]
        messageName.setTextColor(
            GuildMember.getColor(
                authorGuildMember,
                ThemedColor.resolve(itemView.context, R.attr.colorHeaderPrimary),
            ),
        )
        IconUtils.setIcon(
            messageAvatar,
            CoreUser(author),
            R.dimen.avatar_size_small,
            guildMember = authorGuildMember,
        )
    }

    private fun setIcon(icon: Drawable?) {
        this.icon.visibility = if (icon != null) View.VISIBLE else View.GONE
        this.icon.setImageDrawable(icon)
    }

    /**
     * The preview text flows around the author chip rather than starting under it, so the first
     * line is indented by exactly the chip's width.
     */
    private fun leadingMarginSpan(showAuthor: Boolean): LeadingMarginSpan {
        messageHeader.measure(0, 0)
        val margin = if (showAuthor) messageHeader.measuredWidth else 0
        return LeadingMarginSpan.Standard(margin, 0)
    }

    private fun themedDrawable(context: Context, attribute: Int): Drawable? {
        val resourceId = context.getThemedDrawableRes(attribute)
        return if (resourceId == 0) null else ContextCompat.getDrawable(context, resourceId)
    }

    companion object {
        /** The preview is truncated at this many AST nodes, matching the reference. */
        const val MAX_THREAD_MESSAGE_AST_NODES: Int = 50

        private const val MAX_DISPLAYED_MESSAGE_COUNT = 50
    }
}

package com.discord.widgets.chat.list.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import com.discord.R
import com.discord.i18n.FormatUtils
import com.discord.models.member.GuildMember
import com.discord.utilities.time.TimeUtils
import com.discord.views.ThemedColor
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry

/**
 * The header row above a message produced by a slash or context-menu command: "*name* used
 * `/command`", with the invocation arguments in a collapsible card underneath.
 *
 * The row exists because the *response* is an ordinary message row; this one attributes it to the
 * person who ran the command rather than to the bot that answered.
 */
class WidgetChatListAdapterItemApplicationCommand(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_application_command, adapter) {

    private val name: TextView = itemView.findViewById(R.id.chat_list_adapter_item_text_name)
    private val timestamp: TextView = itemView.findViewById(R.id.chat_list_adapter_item_text_timestamp)
    private val command: TextView = itemView.findViewById(R.id.chat_list_adapter_item_text_command)
    private val decorator: CardView = itemView.findViewById(R.id.chat_list_adapter_item_text_decorator)
    private val decoratorReplyIcon: FrameLayout =
        itemView.findViewById(R.id.chat_list_adapter_item_text_decorator_reply_link_icon)

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as? MessageEntry ?: return
        val message = entry.message

        val (commandName, commandArguments) = splitInvocation(message.content)
        val authorName = message.author?.id?.let(entry.nickOrUsernames::get).orEmpty()
        val authorColor = GuildMember.getColor(
            entry.author,
            ThemedColor.resolve(itemView.context, R.attr.colorHeaderPrimary),
        )
        val commandColor = ThemedColor.resolve(itemView.context, R.attr.color_brand_500)

        name.movementMethod = LinkMovementMethod.getInstance()
        name.text = ChatRowText.format(
            itemView.context,
            R.string.system_message_application_command_used_short_mobile,
            mapOf("username" to authorName, "commandName" to commandName),
            mapOf(
                "usernameOnClick" to ChatRowText.Hook(
                    spans = listOf(StyleSpan(Typeface.BOLD), ForegroundColorSpan(authorColor)),
                    onClick = {
                        adapter.eventHandler.onMessageAuthorNameClicked(message, adapter.data.guildId)
                    },
                ),
                // The command name is styled like a link but the reference deliberately makes it
                // inert: there is no "run this command again" surface on mobile.
                "commandNameOnClick" to ChatRowText.Hook(
                    spans = listOf(StyleSpan(Typeface.BOLD), ForegroundColorSpan(commandColor)),
                    onClick = {},
                ),
            ),
        )

        // Only a slash command prints its own name in front of the arguments; a context-menu
        // command was invoked from a message, so there is nothing to prefix.
        val displayedName = if (message.type == APPLICATION_COMMAND_MESSAGE_TYPE) commandName else ""
        command.maxLines = COLLAPSED_MAX_LINES
        command.text = FormatUtils.format(
            itemView.context.getString(R.string.command_display_string),
            "commandName" to displayedName,
            "commandText" to commandArguments,
        )
        command.setOnClickListener {
            command.maxLines =
                if (command.maxLines == COLLAPSED_MAX_LINES) Int.MAX_VALUE else COLLAPSED_MAX_LINES
        }
        command.copyTextOnLongPress()

        val hasArguments = commandArguments.isNotBlank()
        decorator.visibility = if (hasArguments) View.VISIBLE else View.GONE
        decoratorReplyIcon.visibility = if (hasArguments) View.VISIBLE else View.GONE

        timestamp.text = TimeUtils.toReadableTimeString(
            itemView.context,
            message.timestamp?.dateTimeMillis ?: 0L,
            adapter.clock,
        )
    }

    /**
     * Splits `</command:id> arguments` into its two halves.
     *
     * A message that does not carry the command mention at all still renders, with a bare `/` in
     * place of the name — that is what the reference falls back to.
     */
    private fun splitInvocation(content: String?): Pair<String, String> {
        val match = COMMAND_MENTION.matchEntire(content.orEmpty())
            ?: return DEFAULT_COMMAND_NAME to ""
        return match.groupValues[1] to match.groupValues[3]
    }

    private fun TextView.copyTextOnLongPress() = setOnLongClickListener {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(text, text))
        Toast.makeText(context, R.string.copied_text, Toast.LENGTH_SHORT).show()
        false
    }

    private companion object {
        /** `MessageTypes.APPLICATION_COMMAND`; the context-menu variant (23) has no name prefix. */
        private const val APPLICATION_COMMAND_MESSAGE_TYPE = 20
        private const val COLLAPSED_MAX_LINES = 1
        private const val DEFAULT_COMMAND_NAME = "/"

        /** `</name:id> rest`, the wire form of a command mention. */
        private val COMMAND_MENTION = Regex("^<(/[^:]+):([0-9]*)>(.*)", RegexOption.DOT_MATCHES_ALL)
    }
}

package com.discord.widgets.chat.list.entries

class MentionFooterEntry : ChatListEntry() {
    override val type: Int = ChatListEntry.MENTION_FOOTER
    override val key: String = ChatListEntry.MENTION_FOOTER.toString()
}

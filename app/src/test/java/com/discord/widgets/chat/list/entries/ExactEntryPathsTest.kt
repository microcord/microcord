package com.discord.widgets.chat.list.entries

import org.junit.Assert.assertEquals
import org.junit.Test

class ExactEntryPathsTest {
    @Test
    fun entryClassesKeepTheirExactReferenceNames() {
        listOf(
            ChatListEntry::class.java,
            MessageEntry::class.java,
            ThreadEmbedEntry::class.java,
            ThreadDraftFormEntry::class.java,
            BotUiComponentEntry::class.java,
            StartOfChatEntry::class.java,
            StartOfPrivateChatEntry::class.java,
            GuildWelcomeEntry::class.java,
            StickerGreetEntry::class.java,
            StickerGreetCompactEntry::class.java,
            InviteEntry::class.java,
            StageInviteEntry::class.java,
            GuildScheduledEventInviteEntry::class.java,
            GuildTemplateEntry::class.java,
            GiftEntry::class.java,
            GameInviteEntry::class.java,
            SpotifyListenTogetherEntry::class.java,
            AttachmentEntry::class.java,
            EmbedEntry::class.java,
            AutoModSystemMessageEmbedEntry::class.java,
            StickerEntry::class.java,
            LoadingEntry::class.java,
            SpacerEntry::class.java,
            DividerEntry::class.java,
            TimestampEntry::class.java,
            NewMessagesEntry::class.java,
            ThreadStarterDividerEntry::class.java,
            EmptyPinsEntry::class.java,
            MessageHeaderEntry::class.java,
            MentionFooterEntry::class.java,
            SearchResultCountEntry::class.java,
            SearchIndexingEntry::class.java,
            SearchEmptyEntry::class.java,
            SearchErrorEntry::class.java,
            BlockedMessagesEntry::class.java,
            UploadProgressEntry::class.java,
            ReactionsEntry::class.java,
            EphemeralMessageEntry::class.java,
        ).forEach { type ->
            assertEquals("com.discord.widgets.chat.list.entries.${type.simpleName}", type.name)
        }
    }
}

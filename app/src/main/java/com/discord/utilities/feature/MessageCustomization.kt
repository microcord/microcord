package com.discord.utilities.feature

import androidx.annotation.StringRes
import com.discord.R
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.widgets.chat.list.entries.AttachmentEntry
import com.discord.widgets.chat.list.entries.BotUiComponentEntry
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.chat.list.entries.ReactionsEntry
import com.discord.widgets.chat.list.entries.StickerEntry
import com.discord.widgets.chat.list.entries.StickerGreetCompactEntry
import com.discord.widgets.chat.list.entries.StickerGreetEntry
import com.discord.widgets.chat.list.entries.ThreadEmbedEntry
import com.discord.widgets.chat.list.entries.ThreadStarterDividerEntry

object MessageCustomization {
    val enabled: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        FeatureFlags.messageCustomizationsEnabled()
    }

    enum class Section(@param:StringRes val label: Int) {
        CORE(R.string.message_customization_core),
        MEDIA(R.string.media),
        EMBEDS(R.string.message_customization_embeds),
        STICKERS(R.string.message_customization_stickers),
        COMPONENTS(R.string.message_customization_components),
        CARDS(R.string.message_customization_cards),
        REACTIONS(R.string.reactions),
        THREAD(R.string.threads),
    }

    enum class CoreElement(@param:StringRes val label: Int) {
        AVATAR(R.string.header_customization_avatar),
        DECORATION_STATUS(R.string.message_customization_decoration_status),
        AUTHOR(R.string.message_customization_author),
        TAGS(R.string.message_customization_tags),
        GUILD_TAG(R.string.message_customization_guild_tag),
        TIMESTAMP(R.string.message_customization_timestamp),
        REPLY(R.string.message_action_reply),
        CONTENT(R.string.message_customization_content),
    }

    data class Entry(val section: Section, val visible: Boolean = true)

    private const val LAYOUT_KEY = "message_customization_layout"
    private const val CORE_PREFIX = "message_customization_core_"
    private val defaults = Section.entries.map(::Entry)
    @Volatile private var layoutCache: Layout? = null
    @Volatile private var coreCache: BooleanArray? = null

    fun get(): List<Entry> = layout().entries

    fun save(entries: List<Entry>) {
        layoutCache = Layout(entries.toList())
        SharedPreferencesProvider.get().edit().putString(LAYOUT_KEY, encode(entries)).apply()
    }

    fun coreVisible(element: CoreElement) = core()[element.ordinal]

    internal fun coreVisibility(): BooleanArray = core()

    fun setCoreVisible(element: CoreElement, visible: Boolean) {
        coreCache = core().copyOf().apply { this[element.ordinal] = visible }
        SharedPreferencesProvider.get().edit()
            .putBoolean(CORE_PREFIX + element.name.lowercase(), visible)
            .apply()
    }

    fun apply(rows: List<ChatListEntry>): List<ChatListEntry> = apply(rows, layout())

    internal fun apply(rows: List<ChatListEntry>, entries: List<Entry>): List<ChatListEntry> =
        apply(rows, Layout(entries))

    private fun apply(rows: List<ChatListEntry>, layout: Layout): List<ChatListEntry> = buildList(rows.size) {
        layout.visibleEntries.forEach { entry ->
            rows.filterTo(this) { section(it) == entry.section }
        }
    }

    fun section(entry: ChatListEntry): Section = when (entry) {
        is MessageEntry -> Section.CORE
        is AttachmentEntry -> Section.MEDIA
        is EmbedEntry -> Section.EMBEDS
        is StickerEntry, is StickerGreetEntry, is StickerGreetCompactEntry -> Section.STICKERS
        is BotUiComponentEntry -> Section.COMPONENTS
        is ReactionsEntry -> Section.REACTIONS
        is ThreadEmbedEntry, is ThreadStarterDividerEntry -> Section.THREAD
        else -> Section.CARDS
    }

    internal fun encode(entries: List<Entry>) = entries.joinToString(",") {
        "${it.section.name}:${it.visible}"
    }

    internal fun decode(value: String?): List<Entry> {
        val parsed = value.orEmpty().split(',').mapNotNull { item ->
            val parts = item.split(':', limit = 2)
            val section = parts.getOrNull(0)?.let { runCatching { Section.valueOf(it) }.getOrNull() }
                ?: return@mapNotNull null
            val visible = parts.getOrNull(1)?.toBooleanStrictOrNull() ?: return@mapNotNull null
            Entry(section, visible)
        }.distinctBy(Entry::section)
        return parsed + defaults.filter { fallback -> parsed.none { it.section == fallback.section } }
    }

    private fun layout(): Layout = layoutCache ?: synchronized(this) {
        layoutCache ?: Layout(
            decode(SharedPreferencesProvider.get().getString(LAYOUT_KEY, null)),
        ).also { layoutCache = it }
    }

    private fun core(): BooleanArray = coreCache ?: synchronized(this) {
        coreCache ?: SharedPreferencesProvider.get().let { preferences ->
            BooleanArray(CoreElement.entries.size) { index ->
                preferences.getBoolean(CORE_PREFIX + CoreElement.entries[index].name.lowercase(), true)
            }
        }.also { coreCache = it }
    }

    private data class Layout(val entries: List<Entry>) {
        val visibleEntries = entries.filter(Entry::visible)
    }
}

package com.discord.widgets.chat.input.emoji

import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import com.discord.app.AppViewModel
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.EmojiCategory
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import com.discord.utilities.rx.ui
import com.discord.widgets.chat.input.emoji.WidgetEmojiAdapter.Item
import java.util.Locale
import rx.Observable
import rx.Subscription

/**
 * Builds the emoji picker's flat item list and the category rail that indexes it.
 *
 * The list is assembled once per store emission and then only *filtered* while the user types, so
 * a keystroke never re-reads the catalog or recomputes guild membership. The rail's
 * [EmojiCategoryItem.categoryRange] values are derived from the same pass that builds the list,
 * which is the only way the two can be guaranteed to agree.
 *
 * Reference: `com.discord.widgets.chat.input.emoji.EmojiPickerViewModel`. Two of its inputs have no
 * source in this project and are therefore absent rather than faked:
 *
 * * **Favourites and frequently-used** come from `StoreEmoji`/`StoreEmojiCustom`; those categories
 *   are omitted from the rail entirely instead of showing empty tabs.
 * * **The Nitro upsell section** needs `EmojiAutocompletePremiumUpsellFeatureFlag` and the locked
 *   emoji set. `Item.HeaderItem.StringHeaderItem` exists for it and nothing emits one yet.
 */
class EmojiPickerViewModel(
    private val context: Context,
    private val contextType: EmojiPickerContextType = EmojiPickerContextType.Chat,
    private val catalogSource: (Context) -> Observable<Map<EmojiCategory, List<Emoji>>> = EmojiCatalog::observe,
    private val guildsSource: () -> Observable<Map<Long, Guild>> = { StoreStream.getGuilds().observeGuilds() },
    private val animationsSource: () -> Observable<Boolean> =
        { StoreStream.getUserSettings().observeIsAnimatedEmojisEnabled() },
) : AppViewModel<EmojiPickerViewModel.ViewState>(ViewState.Loading) {

    sealed class ViewState {

        /** The bundled catalog is still being read off the main thread. */
        object Loading : ViewState()

        data class Results(
            val items: List<Item>,
            val categories: List<EmojiCategoryItem>,
            val isSearching: Boolean,
        ) : ViewState() {
            /** Drives the picker's view flipper: an empty grid shows the "no results" page. */
            val isEmpty: Boolean = items.none { it is Item.EmojiItem }

            /**
             * The first pure-Unicode category, which the "jump to Unicode emoji" shortcut targets.
             * `null` when the user has no custom emoji at all, in which case the shortcut is hidden
             * because the grid already starts at Unicode.
             */
            val firstUnicodeCategoryItem: EmojiCategoryItem? =
                categories.firstOrNull { it.containsOnlyUnicodeEmoji() }
                    ?.takeIf { categories.firstOrNull() !== it }
        }
    }

    private var subscription: Subscription? = null

    /** Everything the picker can show, before the search filter. Never published directly. */
    private var unfiltered: List<Item> = emptyList()
    private var searchText: String = ""
    private var selectedCategoryId: Long? = null

    init {
        subscription = Observable.combineLatest(
            catalogSource(context.applicationContext),
            guildsSource(),
            animationsSource(),
        ) { unicode, guilds, animationsEnabled ->
            buildItems(unicode, guilds, animationsEnabled, contextType)
        }.ui(this).subscribe({ onItemsChanged(it) }, { /* keep whatever is already on screen */ })
    }

    @MainThread
    private fun onItemsChanged(items: List<Item>) {
        unfiltered = items
        publish()
    }

    /** Filters the grid. An empty or blank query restores the full, headed list. */
    @MainThread
    fun setSearchText(text: String) {
        val normalized = text.trim()
        if (normalized == searchText) return
        searchText = normalized
        publish()
    }

    /**
     * Marks [item] selected. The fragment also scrolls the grid; selection is stored here so it
     * survives the state being rebuilt by an unrelated store emission mid-scroll.
     */
    @MainThread
    fun selectCategory(item: EmojiCategoryItem) {
        selectedCategoryId = item.stableId
        publish()
    }

    /** Called from the grid's scroll listener with the topmost visible adapter position. */
    @MainThread
    fun onGridScrolledTo(position: Int) {
        val categories = categoriesFor(unfiltered, selectedCategoryId)
        val owner = categories.firstOrNull { position in it.categoryRange } ?: return
        if (owner.stableId == selectedCategoryId) return
        selectedCategoryId = owner.stableId
        publish()
    }

    private fun publish() {
        val isSearching = searchText.isNotEmpty()
        val items = if (isSearching) filter(unfiltered, searchText) else unfiltered
        // While searching there are no headers, so no category owns a range: the rail is still
        // drawn (it is the only way back out of search) but nothing is highlighted.
        val categories = categoriesFor(
            if (isSearching) emptyList() else items,
            if (isSearching) null else selectedCategoryId,
        )
        updateViewState(ViewState.Results(items, categories, isSearching))
    }

    override fun onCleared() {
        subscription?.unsubscribe()
        subscription = null
        super.onCleared()
    }

    companion object {

        /**
         * Matches on *any* of an emoji's names, case-insensitively, as a substring.
         *
         * A substring rather than a prefix because Discord's names are compound
         * (`smiling_face_with_heart_eyes`) and users search for the part they remember.
         */
        @VisibleForTesting
        fun filter(items: List<Item>, query: String): List<Item> {
            // Locale.ROOT, not the device locale: emoji names are ASCII identifiers, and in
            // Turkish `"GRIN".lowercase()` is "grın" — a dotless i that matches no emoji name, so
            // the picker's search silently returns nothing. The reference folds emoji names with
            // `Locale.ROOT` for the same reason (`StoreEmoji.kt:207-217` in the decompile).
            val normalized = query.lowercase(Locale.ROOT)
            return items.filterIsInstance<Item.EmojiItem>()
                .filter { item -> item.emoji.names.any { normalized in it.lowercase(Locale.ROOT) } }
        }

        /**
         * Walks the flat list once, recording the span each category owns.
         *
         * The header *is* the start of its category's range, so tapping a rail icon scrolls the
         * header into view rather than the first emoji under it — which is what makes the section
         * boundary visible after the jump.
         */
        @VisibleForTesting
        fun categoriesFor(items: List<Item>, selectedId: Long?): List<EmojiCategoryItem> {
            val result = mutableListOf<EmojiCategoryItem>()
            var pendingHeader: Item.HeaderItem? = null
            var start = 0

            fun close(endExclusive: Int) {
                val header = pendingHeader ?: return
                val range = start until endExclusive
                when (header) {
                    is Item.HeaderItem.StandardHeaderItem ->
                        result += EmojiCategoryItem.StandardItem(header.emojiCategory, range, false)
                    is Item.HeaderItem.GuildHeaderItem ->
                        result += EmojiCategoryItem.GuildItem(header.guild, range, false)
                    // A string header names no category, so it is folded into the preceding one.
                    is Item.HeaderItem.StringHeaderItem -> Unit
                }
                pendingHeader = null
            }

            items.forEachIndexed { index, item ->
                if (item is Item.HeaderItem && item !is Item.HeaderItem.StringHeaderItem) {
                    close(index)
                    pendingHeader = item
                    start = index
                }
            }
            close(items.size)

            val effectiveSelection = selectedId ?: result.firstOrNull()?.stableId
            return result.map {
                when (it) {
                    is EmojiCategoryItem.StandardItem -> it.copy(isSelected = it.stableId == effectiveSelection)
                    is EmojiCategoryItem.GuildItem -> it.copy(isSelected = it.stableId == effectiveSelection)
                }
            }
        }

        /**
         * Guild uploads first, then the Unicode groups in catalog order.
         *
         * Guilds come first because they are what the user is most likely to want and because the
         * Unicode block is thousands of rows long — starting there would put every custom emoji off
         * the bottom of the list.
         */
        @VisibleForTesting
        fun buildItems(
            unicodeEmojis: Map<EmojiCategory, List<Emoji>>,
            guilds: Map<Long, Guild>,
            allowEmojisToAnimate: Boolean,
            contextType: EmojiPickerContextType,
        ): List<Item> {
            val items = mutableListOf<Item>()

            val eligibleGuilds = guilds.values
                .filter { guild ->
                    when (contextType) {
                        is EmojiPickerContextType.Guild -> guild.id == contextType.guildId
                        else -> true
                    }
                }
                .filter { it.emojis.any { emoji -> emoji.available != false } }
                .sortedBy { it.name.lowercase(Locale.getDefault()) }

            for (guild in eligibleGuilds) {
                items += Item.HeaderItem.GuildHeaderItem(guild)
                items += guild.emojis
                    .filter { it.available != false }
                    .map { guildEmoji ->
                        val emoji = ModelEmojiCustom(guildEmoji, guild.id)
                        Item.EmojiItem(
                            guildName = guild.name,
                            emoji = emoji,
                            emojiName = emoji.firstName,
                            allowEmojisToAnimate = allowEmojisToAnimate,
                        )
                    }
            }

            if (contextType !is EmojiPickerContextType.Guild) {
                for ((category, emojis) in unicodeEmojis) {
                    if (emojis.isEmpty()) continue
                    items += Item.HeaderItem.StandardHeaderItem(category)
                    items += emojis.map { emoji ->
                        Item.EmojiItem(
                            guildName = null,
                            emoji = emoji,
                            emojiName = emoji.firstName,
                            allowEmojisToAnimate = allowEmojisToAnimate,
                        )
                    }
                }
            }

            return items
        }
    }
}

package com.discord.stores

import android.content.Context
import com.discord.api.permission.Permission
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.EmojiCategory
import com.discord.models.domain.emoji.EmojiSet
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.utilities.collections.ShallowPartitionMap
import com.discord.utilities.media.MediaFrecencyTracker
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.persister.Persister
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.textprocessing.Rules
import com.discord.utilities.user.UserUtils.isPremium
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.util.Locale
import java.util.regex.Pattern
import rx.Observable

/**
 * The emoji catalog: the built-in unicode set plus whichever guild uploads the current account may
 * actually use, assembled for one picker context at a time.
 *
 * This is also the app's [Rules.EmojiDataProvider] — `Rules.setEmojiDataProvider(storeEmoji)` must
 * run during application start, **before the first message is parsed**, because `DiscordParser`
 * builds its rule set once and the unicode-emoji rule captures [unicodeEmojisPattern] at that
 * moment. Miss it and named/unicode emoji render as literal text everywhere.
 *
 * Not a [StoreV2]: it owns no dispatcher-mutated state. The unicode half is immutable once
 * [initBlocking] has read it off disk, and the custom half is derived on demand from
 * [StoreEmojiCustom], so there is nothing to snapshot.
 */
class StoreEmoji(
    private val customEmojiStore: StoreEmojiCustom,
    private val userStore: StoreUser,
    private val permissionsStore: StorePermissions,
    private val sortedGuildsStore: StoreGuildsSorted,
    /**
     * `StoreMediaFavorites.observeFavorites(Favorite.EmojiTypes)`, as a set of
     * [Emoji.getUniqueId] values. That store has not been reconstructed yet; until it is, the
     * favourites row is empty rather than wrong.
     */
    private val observeFavoriteEmojiIds: () -> Observable<Set<String>> = { Observable.just(emptySet()) },
    private val frecencyCache: Persister<MediaFrecencyTracker> =
        Persister(EMOJI_FRECENCY_CACHE_KEY, MediaFrecencyTracker()),
) : Rules.EmojiDataProvider {

    private val frecency: MediaFrecencyTracker = frecencyCache.get()

    private var unicodeEmojisByCategory: Map<EmojiCategory, List<Emoji>> = emptyMap()
    private var namesMap: Map<String, ModelEmojiUnicode> = emptyMap()
    private var surrogateMap: Map<String, ModelEmojiUnicode> = emptyMap()
    private var surrogatesPattern: Pattern = NEVER_MATCHES

    /**
     * Where the picker is being opened from. This decides two things at once: which guild's emoji
     * sort first, and whether an emoji counts as "external" (and therefore Nitro-gated).
     */
    sealed class EmojiContext {
        /** The composer for [channelId]; [guildId] is 0 in a DM. */
        data class Chat(val guildId: Long, val channelId: Long) : EmojiContext()

        /** No guild in particular — nothing is external, everything is offered. */
        object Global : EmojiContext()

        data class Guild(val guildId: Long) : EmojiContext()

        /** Guild profile editor: unicode emoji are excluded entirely, only uploads are offered. */
        data class GuildProfile(val guildId: Long) : EmojiContext()
    }

    // region Rules.EmojiDataProvider

    override val unicodeEmojisNamesMap: Map<String, ModelEmojiUnicode> get() = namesMap
    override val unicodeEmojiSurrogateMap: Map<String, ModelEmojiUnicode> get() = surrogateMap
    override val unicodeEmojisPattern: Pattern get() = surrogatesPattern

    // endregion

    /**
     * Reads `assets/data/emojis.json` on the calling thread.
     *
     * Blocking is deliberate and matches the reference: message parsing cannot start until the
     * catalog exists, so deferring it to a background thread would only create a window in which
     * emoji silently fail to render.
     */
    fun initBlocking(context: Context) {
        handleLoadedUnicodeEmojis(loadUnicodeEmojisFromDisk(context))
    }

    /** Resolves a custom-status/reaction emoji id without knowing which guild owns it. */
    @StoreThread
    fun getCustomEmojiInternal(emojiId: Long): ModelEmojiCustom? {
        for (guildEmoji in customEmojiStore.getAllGuildEmojiInternal().values) {
            guildEmoji[emojiId]?.let { return it }
        }
        return null
    }

    @JvmOverloads
    fun getEmojiSet(
        guildId: Long,
        channelId: Long,
        includeUnusableEmojis: Boolean = false,
        includeUnavailableEmojis: Boolean = false,
    ): Observable<EmojiSet> = getEmojiSet(
        EmojiContext.Chat(guildId, channelId),
        includeUnusableEmojis,
        includeUnavailableEmojis,
    )

    fun getEmojiSet(
        emojiContext: EmojiContext,
        includeUnusableEmojis: Boolean,
        includeUnavailableEmojis: Boolean,
    ): Observable<EmojiSet> {
        val hasExternalEmojiPermission: Observable<Boolean> = when {
            emojiContext is EmojiContext.Chat && emojiContext.guildId != 0L ->
                permissionsStore.observePermissionsForChannel(emojiContext.channelId)
                    .map { PermissionUtils.can(Permission.USE_EXTERNAL_EMOJIS, it) }
            // A guild-scoped picker only ever offers that guild's own emoji, so "external" is
            // never granted; everywhere else (DMs, the global picker) nothing is external at all.
            emojiContext is EmojiContext.Guild -> Observable.just(false)
            else -> Observable.just(true)
        }

        return Observable.combineLatest(
            userStore.observeMe().map { it.isPremium },
            hasExternalEmojiPermission,
            sortedGuildsStore.observeOrderedGuilds().map { it.keys.toList() },
            observeFavoriteEmojiIds(),
        ) { isPremium, hasExternalEmoji, sortedGuildIds, favorites ->
            Inputs(isPremium, hasExternalEmoji, sortedGuildIds, favorites)
        }
            .distinctUntilChanged()
            .switchMap { inputs ->
                customEmojiStore.observeAllowedGuildEmoji()
                    .computationLatest()
                    .map { allowedCustomEmoji ->
                        buildUsableEmojiSet(
                            allCustomEmojis = allowedCustomEmoji,
                            emojiContext = emojiContext,
                            sortedGuildIds = inputs.sortedGuildIds,
                            isMePremium = inputs.isPremium,
                            includeUnusableEmojis = includeUnusableEmojis,
                            includeUnavailableEmojis = includeUnavailableEmojis,
                            hasExternalEmojiPermission = inputs.hasExternalEmojiPermission,
                            favoriteEmojiIds = inputs.favoriteEmojiIds,
                        )
                    }
            }
    }

    fun onEmojiUsed(emoji: Emoji) = onEmojiUsed(emoji.getUniqueId())

    fun onEmojiUsed(emojiKey: String) {
        frecency.track(emojiKey)
        frecencyCache.set(frecency)
    }

    @StoreThread
    fun handlePreLogout() {
        frecencyCache.clear()
    }

    // region internals

    /** The four picker inputs that are independent of the custom-emoji map itself. */
    private data class Inputs(
        val isPremium: Boolean,
        val hasExternalEmojiPermission: Boolean,
        val sortedGuildIds: List<Long>,
        val favoriteEmojiIds: Set<String>,
    )

    internal fun buildUsableEmojiSet(
        allCustomEmojis: Map<Long, Map<Long, ModelEmojiCustom>>,
        emojiContext: EmojiContext,
        sortedGuildIds: List<Long>,
        isMePremium: Boolean,
        includeUnusableEmojis: Boolean,
        includeUnavailableEmojis: Boolean,
        hasExternalEmojiPermission: Boolean,
        favoriteEmojiIds: Set<String>,
    ): EmojiSet {
        val isExternalEmoji: (Long) -> Boolean = { guildId ->
            when (emojiContext) {
                is EmojiContext.Chat -> emojiContext.guildId != guildId
                is EmojiContext.Global -> true
                is EmojiContext.GuildProfile -> emojiContext.guildId != guildId
                is EmojiContext.Guild -> emojiContext.guildId != guildId
            }
        }
        val isExternalEmojiRestricted: (Boolean) -> Boolean = { isExternal ->
            (emojiContext is EmojiContext.Chat || emojiContext is EmojiContext.Guild) &&
                isExternal && !hasExternalEmojiPermission
        }

        // Guilds we hold emoji for that the sorted list does not mention (unavailable, or a
        // GUILD_CREATE that has not reached StoreGuildsSorted yet). They are processed last.
        val unsortedGuildIds = allCustomEmojis.keys.filterNot(sortedGuildIds::contains)

        var usableCustomEmojiCount = 0
        for (guildId in sortedGuildIds + unsortedGuildIds) {
            val emojis = allCustomEmojis[guildId]
            if (emojis != null && !isExternalEmojiRestricted(isExternalEmoji(guildId))) {
                usableCustomEmojiCount += emojis.size
            }
        }
        var unicodeEmojiCount = 0
        for (categoryEmojis in unicodeEmojisByCategory.values) unicodeEmojiCount += categoryEmojis.size

        // Sized up front because the picker rebuilds this on every keystroke of its search box.
        val emojiNameCounts: MutableMap<String, Int> =
            ShallowPartitionMap.create<String, Int>(usableCustomEmojiCount + namesMap.size)
        val usableCustomEmojis = HashMap<Long, List<Emoji>>(sortedGuildIds.size + unsortedGuildIds.size)
        val emojiIdsMap: MutableMap<String, Emoji> =
            ShallowPartitionMap.create<String, Emoji>(usableCustomEmojiCount + unicodeEmojiCount)

        if (emojiContext !is EmojiContext.GuildProfile) {
            for (categoryEmojis in unicodeEmojisByCategory.values) {
                for (emoji in categoryEmojis) emojiIdsMap[emoji.getUniqueId()] = emoji
            }
        }
        // Seed the name counter with every unicode name, so a guild upload called "smile" is
        // disambiguated to "smile-1" instead of shadowing the unicode emoji in autocomplete.
        for (name in namesMap.keys) emojiNameCounts.increment(name)

        val processGuildEmojis: (Long) -> Unit = processGuildEmojis@{ guildId ->
            val emojis = allCustomEmojis[guildId]
            if (emojis == null) return@processGuildEmojis
            val isExternal = isExternalEmoji(guildId)
            if (isExternalEmojiRestricted(isExternal)) return@processGuildEmojis

            val usable = ArrayList<ModelEmojiCustom>()
            for (emoji in emojis.values) {
                if (!includeUnavailableEmojis && !emoji.isAvailable()) continue
                // A managed (integration-owned, e.g. Twitch) emoji is not treated as external
                // outside the global picker — those are granted by the subscription, not Nitro.
                val isManagedOutsideGlobal = emoji.isManaged && emojiContext !is EmojiContext.Global
                val isUsable = isMePremium ||
                    !(emoji.isAnimated || (isExternal && !isManagedOutsideGlobal))
                if (!includeUnusableEmojis && !isUsable) continue

                val nameCount = emojiNameCounts[emoji.name]
                // The first usable emoji with a given name keeps the original instance; anything
                // colliding (or unusable) is re-stamped with its disambiguation index.
                val stamped = if (nameCount == null && isUsable) {
                    emoji
                } else {
                    ModelEmojiCustom(emoji, nameCount ?: 0, isUsable)
                }
                usable += stamped
                emojiNameCounts.increment(emoji.name)
                emojiIdsMap[emoji.getUniqueId()] = stamped
            }
            if (usable.isNotEmpty()) {
                // Reference behaviour, reproduced verbatim: the comparator is a
                // `sortedByDescending` over the lowercased name, so each guild's section is
                // reverse-alphabetical. Confirmed from the synthesized comparator class name and
                // its argument order in both the JADX and Vineflower decompiles.
                usableCustomEmojis[guildId] = usable.sortedByDescending { it.name.lowercase(Locale.ROOT) }
            }
        }

        // The context guild goes first so its own emoji win every name collision.
        val contextGuildId = if (emojiContext is EmojiContext.Chat) emojiContext.guildId else 0L
        processGuildEmojis(contextGuildId)
        sortedGuildIds.filter { it != contextGuildId }.forEach(processGuildEmojis)
        unsortedGuildIds.forEach(processGuildEmojis)

        val favoriteEmoji = favoriteEmojiIds.mapNotNull(emojiIdsMap::get).toSet()

        return EmojiSet(
            unicodeEmojis = unicodeEmojisByCategory,
            customEmojis = usableCustomEmojis,
            emojiIndex = emojiIdsMap,
            recentEmojis = getFrequentlyUsedEmojis(emojiIdsMap),
            favoriteEmoji = favoriteEmoji,
        )
    }

    /**
     * The "Frequently Used" row: frecency first, topped up with [DEFAULT_FREQUENT_EMOJIS] so a
     * fresh install still shows something.
     */
    private fun getFrequentlyUsedEmojis(emojiIdsMap: Map<String, Emoji>): List<Emoji> {
        val tracked = frecency.getSortedKeys()
            .mapNotNull(emojiIdsMap::get)
            .take(MAX_FREQUENTLY_USED_EMOJIS)
        if (tracked.size >= MAX_FREQUENTLY_USED_EMOJIS) return tracked
        val defaults = DEFAULT_FREQUENT_EMOJIS.mapNotNull(namesMap::get)
        return (tracked + defaults.take(MAX_FREQUENTLY_USED_EMOJIS - tracked.size)).distinct()
    }

    /** Visible for tests, which cannot read Android assets on the JVM. */
    internal fun handleLoadedUnicodeEmojis(bundle: Map<EmojiCategory, List<ModelEmojiUnicode>>) {
        val byCategory = LinkedHashMap<EmojiCategory, List<Emoji>>()
        val names = HashMap<String, ModelEmojiUnicode>()
        val surrogates = HashMap<String, ModelEmojiUnicode>()

        fun index(emoji: ModelEmojiUnicode) {
            surrogates[emoji.surrogates] = emoji
            emoji.names.forEach { name -> names[name] = emoji }
            // A sequence joined by ZWJ must keep its variation selectors or it stops being that
            // emoji. Anything else gets a second, selector-free key, so a bare U+2764 in message
            // content resolves to the same emoji as the U+2764 U+FE0F the picker inserts.
            if (!emoji.surrogates.contains(ZERO_WIDTH_JOINER)) {
                val withoutVariationSelector = emoji.surrogates.replace(VARIATION_SELECTOR_16, "")
                if (withoutVariationSelector != emoji.surrogates) {
                    surrogates[withoutVariationSelector] = emoji
                }
            }
        }

        for ((category, categoryEmojis) in bundle) {
            byCategory[category] = categoryEmojis
            for (emoji in categoryEmojis) {
                index(emoji)
                emoji.asDiverse.forEach(::index)
            }
        }

        unicodeEmojisByCategory = byCategory
        namesMap = names
        surrogateMap = surrogates
        surrogatesPattern = compileSurrogatesPattern()
    }

    /**
     * One alternation over every known surrogate sequence, **longest first** — otherwise a
     * skin-toned emoji would match its base form and leave the modifier behind as stray text.
     */
    private fun compileSurrogatesPattern(): Pattern {
        // An empty alternation compiles to a pattern that matches the empty string everywhere,
        // which would make every message look like it started with an emoji. The reference never
        // loads an empty catalog and so never hits this; failing closed is the safe reading.
        if (surrogateMap.isEmpty()) return NEVER_MATCHES
        return Pattern.compile(
            surrogateMap.keys
                .sortedBy { -it.length }
                .joinToString("|") { Pattern.quote(it) },
        )
    }

    private fun loadUnicodeEmojisFromDisk(context: Context): Map<EmojiCategory, List<ModelEmojiUnicode>> {
        val root = context.assets.open(EMOJI_ASSET_PATH).reader(Charsets.UTF_8).use { reader ->
            JsonParser.parseReader(reader).asJsonObject
        }
        val bundle = LinkedHashMap<EmojiCategory, List<ModelEmojiUnicode>>()
        for ((key, value) in root.entrySet()) {
            val category = EmojiCategory.getByString(key) ?: continue
            bundle[category] = value.asJsonArray.map { parseUnicodeEmoji(it) }
        }
        return bundle
    }

    /**
     * Hand-rolled rather than reflective: `ModelEmojiUnicode` carries a `by lazy` delegate and a
     * recursive child list, and the asset also holds fields (`unicodeVersion`, `diversity`) the
     * model does not model. The reference streams the same shape through its own
     * `ModelEmojiUnicode.Bundle`, which this project has not reconstructed.
     */
    private fun parseUnicodeEmoji(element: JsonElement): ModelEmojiUnicode {
        val json = element.asJsonObject
        fun flag(name: String): Boolean =
            json.get(name)?.takeUnless(JsonElement::isJsonNull)?.asBoolean ?: false
        return ModelEmojiUnicode(
            names = json.getAsJsonArray("names")?.map { it.asString }.orEmpty(),
            surrogates = json.get("surrogates")?.takeUnless(JsonElement::isJsonNull)?.asString.orEmpty(),
            hasDiversity = flag("hasDiversity"),
            hasMultiDiversity = flag("hasMultiDiversity"),
            hasDiversityParent = flag("hasDiversityParent"),
            hasMultiDiversityParent = flag("hasMultiDiversityParent"),
            diversityChildren = json.getAsJsonArray("diversityChildren")?.map { parseUnicodeEmoji(it) },
        )
    }

    private fun <K> MutableMap<K, Int>.increment(key: K) {
        this[key] = (this[key] ?: 0) + 1
    }

    // endregion

    companion object {
        const val EMOJI_FRECENCY_CACHE_KEY = "EMOJI_HISTORY_V4"

        private const val EMOJI_ASSET_PATH = "data/emojis.json"
        private const val MAX_FREQUENTLY_USED_EMOJIS = 40

        /**
         * Built from code points rather than written literally: both characters are invisible in
         * an editor, so a literal is impossible to review and trivially lost to a stray edit.
         */
        private val ZERO_WIDTH_JOINER: Char = Char(0x200D)
        private val VARIATION_SELECTOR_16: String = Char(0xFE0F).toString()

        private val NEVER_MATCHES: Pattern = Pattern.compile("(?!)")

        /** Shown in "Frequently Used" until the user has actually used forty emoji. */
        @JvmField
        val DEFAULT_FREQUENT_EMOJIS: Array<String> = arrayOf(
            "bread", "fork_and_knife", "yum", "weary", "tired_face", "poop", "thumbsup", "100",
        )
    }
}

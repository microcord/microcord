package com.discord.stores

import com.discord.api.emoji.GuildEmoji
import com.discord.api.emoji.GuildEmojisUpdate
import com.discord.api.guildhash.GuildHashes
import com.discord.api.guildmember.GuildMember as ApiGuildMember
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.emoji.EmojiCategory
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.schedulers.TestScheduler

class EmojiStoresTest {

    private val directory = Files.createTempDirectory("emoji-stores-test").toFile()
    private val scheduler = TestScheduler()
    private val dispatcher = Dispatcher(scheduler)
    private val deck = ObservationDeck()

    @Before fun setUp() = Persister.init(directory)
    @After fun tearDown() { directory.deleteRecursively() }

    // region StoreEmojiCustom

    @Test fun readyFilesEmojiUnderTheRoleThatGrantsThemAndFlattensThemOnSnapshot() {
        val store = newCustomStore()
        register(store)

        dispatcher.schedule { store.handleConnectionOpen(readyPayload()) }
        scheduler.triggerActions()

        // Every emoji is visible for rendering...
        assertEquals(setOf(1L, 2L, 3L), store.getEmojiForGuild(GUILD_ID).keys)
        // ...but only the two our roles grant are usable.
        assertEquals(setOf(1L, 2L), allowedIdsFor(store, GUILD_ID))
    }

    @Test fun losingTheGrantingRoleRemovesTheEmojiFromTheAllowedSet() {
        val store = newCustomStore()
        register(store)
        dispatcher.schedule { store.handleConnectionOpen(readyPayload()) }
        scheduler.triggerActions()
        assertEquals(setOf(1L, 2L), allowedIdsFor(store, GUILD_ID))

        dispatcher.schedule { store.handleGuildMemberAdd(memberWithNoRoles()) }
        scheduler.triggerActions()

        // The unrestricted emoji survives; the role-gated one does not.
        assertEquals(setOf(1L), allowedIdsFor(store, GUILD_ID))
        // Both are still known — they just cannot all be used.
        assertEquals(setOf(1L, 2L, 3L), store.getEmojiForGuild(GUILD_ID).keys)
    }

    @Test fun emojiUpdateWithoutAMemberRecordIsDroppedRatherThanPublished() {
        val store = newCustomStore()
        register(store)
        dispatcher.schedule { store.handleConnectionOpen(readyPayload()) }
        scheduler.triggerActions()

        // Nothing taught StoreGuilds about this guild, so our roles are unknown and the update
        // cannot be evaluated. Publishing the global list anyway would offer emoji we may not send.
        dispatcher.schedule {
            store.handleEmojiUpdate(
                GuildEmojisUpdate(GUILD_ID, listOf(apiEmoji(9, "new")), GuildHashes(null, null, null, 0)),
            )
        }
        scheduler.triggerActions()

        assertEquals(setOf(1L, 2L, 3L), store.getEmojiForGuild(GUILD_ID).keys)
    }

    @Test fun guildRemoveDropsBothMaps() {
        val store = newCustomStore()
        register(store)
        dispatcher.schedule { store.handleConnectionOpen(readyPayload()) }
        scheduler.triggerActions()

        dispatcher.schedule { store.handleGuildRemove(GUILD_ID) }
        scheduler.triggerActions()

        assertTrue(store.getEmojiForGuild(GUILD_ID).isEmpty())
        assertTrue(allowedIdsFor(store, GUILD_ID).isEmpty())
    }

    // endregion

    // region StoreEmoji — unicode catalog

    @Test fun surrogateIndexAddsAVariationSelectorFreeAliasButNotForZwjSequences() {
        val store = newEmojiStore()
        val heart = unicode(listOf("heart"), HEART_WITH_SELECTOR)
        val family = unicode(listOf("family"), FAMILY_ZWJ_SEQUENCE)
        store.handleLoadedUnicodeEmojis(mapOf(EmojiCategory.PEOPLE to listOf(heart, family)))

        assertEquals(heart, store.unicodeEmojiSurrogateMap[HEART_WITH_SELECTOR])
        assertEquals(heart, store.unicodeEmojiSurrogateMap[HEART_WITHOUT_SELECTOR])
        assertEquals(family, store.unicodeEmojiSurrogateMap[FAMILY_ZWJ_SEQUENCE])
        // A ZWJ sequence must keep its selectors, so no stripped alias is registered for it.
        assertEquals(3, store.unicodeEmojiSurrogateMap.size)
        assertEquals(heart, store.unicodeEmojisNamesMap["heart"])
    }

    @Test fun surrogatePatternMatchesTheLongestSequenceFirst() {
        val store = newEmojiStore()
        val plain = unicode(listOf("thumbsup"), THUMBS_UP)
        val toned = unicode(listOf("thumbsup_tone1"), THUMBS_UP_TONED)
        store.handleLoadedUnicodeEmojis(mapOf(EmojiCategory.PEOPLE to listOf(plain, toned)))

        val matcher = store.unicodeEmojisPattern.matcher(THUMBS_UP_TONED)
        assertTrue(matcher.find())
        assertEquals(THUMBS_UP_TONED, matcher.group())
    }

    // endregion

    // region StoreEmoji — emoji set assembly

    @Test fun collidingNamesAcrossGuildsAreDisambiguatedWithTheContextGuildWinning() {
        val store = newEmojiStore()
        val set = store.buildUsableEmojiSet(
            allCustomEmojis = mapOf(
                CONTEXT_GUILD_ID to mapOf(1L to emoji(1, "blob", guildId = CONTEXT_GUILD_ID)),
                GUILD_ID to mapOf(2L to emoji(2, "blob", guildId = GUILD_ID)),
            ),
            emojiContext = StoreEmoji.EmojiContext.Chat(CONTEXT_GUILD_ID, CHANNEL_ID),
            sortedGuildIds = listOf(CONTEXT_GUILD_ID, GUILD_ID),
            isMePremium = true,
            includeUnusableEmojis = false,
            includeUnavailableEmojis = false,
            hasExternalEmojiPermission = true,
            favoriteEmojiIds = emptySet(),
        )

        assertEquals("blob", set.emojiIndex.getValue("1").firstName)
        assertEquals("blob-1", set.emojiIndex.getValue("2").firstName)
    }

    @Test fun withoutExternalEmojiPermissionOtherGuildsAreSkippedEntirely() {
        val store = newEmojiStore()
        val set = store.buildUsableEmojiSet(
            allCustomEmojis = mapOf(
                CONTEXT_GUILD_ID to mapOf(1L to emoji(1, "local", guildId = CONTEXT_GUILD_ID)),
                GUILD_ID to mapOf(2L to emoji(2, "foreign", guildId = GUILD_ID)),
            ),
            emojiContext = StoreEmoji.EmojiContext.Chat(CONTEXT_GUILD_ID, CHANNEL_ID),
            sortedGuildIds = listOf(CONTEXT_GUILD_ID, GUILD_ID),
            isMePremium = true,
            // Even asking for unusable emoji does not surface them: they are skipped, not marked.
            includeUnusableEmojis = true,
            includeUnavailableEmojis = false,
            hasExternalEmojiPermission = false,
            favoriteEmojiIds = emptySet(),
        )

        assertEquals(setOf(CONTEXT_GUILD_ID), set.customEmojis.keys)
        assertNull(set.emojiIndex["2"])
    }

    @Test fun nonPremiumUsersGetAnimatedEmojiMarkedUnusableRatherThanHidden() {
        val store = newEmojiStore()
        val set = store.buildUsableEmojiSet(
            allCustomEmojis = mapOf(
                CONTEXT_GUILD_ID to mapOf(
                    1L to emoji(1, "still", guildId = CONTEXT_GUILD_ID),
                    2L to emoji(2, "moving", guildId = CONTEXT_GUILD_ID, animated = true),
                ),
            ),
            emojiContext = StoreEmoji.EmojiContext.Chat(CONTEXT_GUILD_ID, CHANNEL_ID),
            sortedGuildIds = listOf(CONTEXT_GUILD_ID),
            isMePremium = false,
            includeUnusableEmojis = true,
            includeUnavailableEmojis = false,
            hasExternalEmojiPermission = true,
            favoriteEmojiIds = emptySet(),
        )

        assertTrue(set.emojiIndex.getValue("1").isUsable())
        assertFalse(set.emojiIndex.getValue("2").isUsable())
    }

    @Test fun guildProfileContextExcludesUnicodeEmojiFromTheIndex() {
        val store = newEmojiStore()
        store.handleLoadedUnicodeEmojis(
            mapOf(EmojiCategory.PEOPLE to listOf(unicode(listOf("smile"), SMILE))),
        )

        val profileSet = store.buildEmptySet(StoreEmoji.EmojiContext.GuildProfile(GUILD_ID))
        val globalSet = store.buildEmptySet(StoreEmoji.EmojiContext.Global)

        assertNull(profileSet.emojiIndex[SMILE])
        assertNotNull(globalSet.emojiIndex[SMILE])
    }

    @Test fun frequentlyUsedFallsBackToDefaultsAndThenPrefersTrackedEmoji() {
        val store = newEmojiStore()
        val defaults = StoreEmoji.DEFAULT_FREQUENT_EMOJIS.map { unicode(listOf(it), "surrogate-$it") }
        store.handleLoadedUnicodeEmojis(mapOf(EmojiCategory.PEOPLE to defaults))

        val before = store.buildEmptySet(StoreEmoji.EmojiContext.Global)
        assertEquals(
            StoreEmoji.DEFAULT_FREQUENT_EMOJIS.toList(),
            before.recentEmojis.map { it.firstName },
        )

        store.onEmojiUsed("surrogate-100")

        val after = store.buildEmptySet(StoreEmoji.EmojiContext.Global)
        assertEquals("100", after.recentEmojis.first().firstName)
        // Still forty slots' worth of content, with the tracked emoji deduplicated out of the tail.
        assertEquals(StoreEmoji.DEFAULT_FREQUENT_EMOJIS.size, after.recentEmojis.size)
    }

    @Test fun favouritesResolveThroughTheEmojiIndexAndUnknownIdsAreDropped() {
        val store = newEmojiStore()
        val set = store.buildUsableEmojiSet(
            allCustomEmojis = mapOf(GUILD_ID to mapOf(7L to emoji(7, "star", guildId = GUILD_ID))),
            emojiContext = StoreEmoji.EmojiContext.Global,
            sortedGuildIds = listOf(GUILD_ID),
            isMePremium = true,
            includeUnusableEmojis = false,
            includeUnavailableEmojis = false,
            hasExternalEmojiPermission = true,
            favoriteEmojiIds = setOf("7", "does-not-exist"),
        )

        assertEquals(listOf("star"), set.favoriteEmoji.map { it.firstName })
    }

    // endregion

    // region StoreEmojiGuild

    @Test fun onlyTheActivatedGuildRefetchesOnAnEmojiUpdate() {
        var apiCalls = 0
        val store = StoreEmojiGuild(deck, dispatcher, api = { apiCalls++; null })
        register(store)

        store.activate(GUILD_ID)
        scheduler.triggerActions()

        dispatcher.schedule { store.handleEmojiUpdate(emojisUpdate(OTHER_GUILD_ID)) }
        scheduler.triggerActions()
        assertEquals(0, apiCalls)

        dispatcher.schedule { store.handleEmojiUpdate(emojisUpdate(GUILD_ID)) }
        scheduler.triggerActions()
        assertEquals(1, apiCalls)

        store.deactivate()
        scheduler.triggerActions()
        dispatcher.schedule { store.handleEmojiUpdate(emojisUpdate(GUILD_ID)) }
        scheduler.triggerActions()
        assertEquals(1, apiCalls)
    }

    @Test fun deleteEmojiIsOptimisticAndOnlyTouchesTheNamedGuild() {
        val store = StoreEmojiGuild(deck, dispatcher, api = { null })
        register(store)

        store.deleteEmoji(GUILD_ID, 5L)
        scheduler.triggerActions()

        // "Loaded and empty" for the guild that was edited, "never fetched" for everything else.
        assertEquals(emptyList<Any>(), store.getGuildEmoji(GUILD_ID))
        assertNull(store.getGuildEmoji(OTHER_GUILD_ID))
    }

    // endregion

    // region fixtures

    private fun StoreEmoji.buildEmptySet(context: StoreEmoji.EmojiContext) = buildUsableEmojiSet(
        allCustomEmojis = emptyMap(),
        emojiContext = context,
        sortedGuildIds = emptyList(),
        isMePremium = true,
        includeUnusableEmojis = false,
        includeUnavailableEmojis = false,
        hasExternalEmojiPermission = true,
        favoriteEmojiIds = emptySet(),
    )

    private fun newCustomStore(): StoreEmojiCustom {
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        return StoreEmojiCustom(StoreGuilds(users, dispatcher, deck))
    }

    private fun newEmojiStore(): StoreEmoji {
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val guilds = StoreGuilds(users, dispatcher, deck)
        val channels = StoreChannels(dispatcher, deck)
        return StoreEmoji(
            customEmojiStore = StoreEmojiCustom(guilds),
            userStore = users,
            permissionsStore = StorePermissions(users, channels, guilds, deck),
            sortedGuildsStore = StoreGuildsSorted(deck, dispatcher),
        )
    }

    private fun register(vararg stores: StoreV2) =
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(stores.toList(), deck))

    private fun allowedIdsFor(store: StoreEmojiCustom, guildId: Long): Set<Long> {
        var allowed: Map<Long, Map<Long, ModelEmojiCustom>> = emptyMap()
        store.observeAllowedGuildEmoji().take(1).subscribe { allowed = it }
        return allowed[guildId]?.keys.orEmpty()
    }

    private fun emojisUpdate(guildId: Long) =
        GuildEmojisUpdate(guildId, emptyList(), GuildHashes(null, null, null, 0))

    /** Emoji 1 is unrestricted, 2 needs a role we have, 3 needs one we do not. */
    private fun readyPayload(): ModelPayload = gson.fromJson(
        """
        {
          "user": {"id": $ME_ID, "username": "me", "discriminator": "0000"},
          "private_channels": [],
          "guilds": [{
            "id": $GUILD_ID, "name": "Guild", "owner_id": 9, "unavailable": false, "features": [],
            "members": [{
              "guild_id": $GUILD_ID,
              "user": {"id": $ME_ID, "username": "me", "discriminator": "0000"},
              "roles": [$ROLE_ID], "pending": false
            }],
            "emojis": [
              {"id": 1, "name": "unrestricted", "roles": [], "require_colons": true, "managed": false, "animated": false, "available": true},
              {"id": 2, "name": "gated", "roles": [$ROLE_ID], "require_colons": true, "managed": false, "animated": false, "available": true},
              {"id": 3, "name": "other_role_only", "roles": [$OTHER_ROLE_ID], "require_colons": true, "managed": false, "animated": false, "available": true}
            ]
          }]
        }
        """.trimIndent(),
        ModelPayload::class.java,
    )

    private fun memberWithNoRoles(): ApiGuildMember = gson.fromJson(
        """{"guild_id": $GUILD_ID, "user": {"id": $ME_ID, "username": "me", "discriminator": "0000"}, "roles": [], "pending": false}""",
        ApiGuildMember::class.java,
    )

    private fun emoji(
        id: Long,
        name: String,
        guildId: Long = GUILD_ID,
        animated: Boolean = false,
    ) = ModelEmojiCustom(apiEmoji(id, name, animated = animated), guildId)

    private fun apiEmoji(id: Long, name: String, animated: Boolean = false) =
        GuildEmoji(id, name, emptyList(), true, false, animated, true)

    private fun unicode(names: List<String>, surrogates: String) =
        ModelEmojiUnicode(names = names, surrogates = surrogates)

    // endregion

    companion object {
        private const val ME_ID = 100L
        private const val GUILD_ID = 10L
        private const val OTHER_GUILD_ID = 11L
        private const val CONTEXT_GUILD_ID = 12L
        private const val CHANNEL_ID = 20L
        private const val ROLE_ID = 30L
        private const val OTHER_ROLE_ID = 31L

        /** Built from code points so the invisible joiners and selectors stay reviewable. */
        private val HEART_WITHOUT_SELECTOR = String(Character.toChars(0x2764))
        private val HEART_WITH_SELECTOR = HEART_WITHOUT_SELECTOR + Char(0xFE0F)
        private val FAMILY_ZWJ_SEQUENCE = String(Character.toChars(0x1F469)) +
            Char(0x200D) + String(Character.toChars(0x1F467))
        private val THUMBS_UP = String(Character.toChars(0x1F44D))
        private val THUMBS_UP_TONED = THUMBS_UP + String(Character.toChars(0x1F3FB))
        private val SMILE = String(Character.toChars(0x1F604))

        private val gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .create()
    }
}

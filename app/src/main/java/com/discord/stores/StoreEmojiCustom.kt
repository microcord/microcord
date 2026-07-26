package com.discord.stores

import com.discord.api.emoji.GuildEmojisUpdate
import com.discord.api.guild.Guild
import com.discord.api.guildmember.GuildMember
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.utilities.persister.Persister
import rx.Observable

/**
 * Every guild emoji this account can see, plus the subset it is actually allowed to use.
 *
 * Two maps, because they answer different questions:
 *  - [allGuildEmoji] is `guildId -> emojiId -> emoji`, everything the guild has uploaded. The
 *    emoji sheet and message rendering read this, because an emoji you cannot *send* still has to
 *    render when someone else sends it.
 *  - [allowedGuildEmojis] is `guildId -> roleId -> emojiId -> emoji`, keyed by the role that grants
 *    access. Role-restricted emoji are filed under the granting role and unrestricted ones under
 *    [NO_ROLE], so a role change only has to re-file one bucket rather than re-evaluate the guild.
 *
 * The allowed map is flattened per guild and written to a [Persister] on every snapshot, which is
 * what makes the picker's custom section populate before `READY` arrives on a cold start.
 */
class StoreEmojiCustom(
    private val storeGuilds: StoreGuilds,
    private val allowedGuildEmojiPersister: Persister<Map<Long, Map<Long, ModelEmojiCustom>>> =
        Persister(ALLOWED_EMOJI_CACHE_KEY, HashMap()),
) : StoreV2() {

    private val allGuildEmoji = LinkedHashMap<Long, Map<Long, ModelEmojiCustom>>()
    private var allGuildEmojiSnapshot: Map<Long, Map<Long, ModelEmojiCustom>> = emptyMap()

    private val allowedGuildEmojis =
        LinkedHashMap<Long, MutableMap<Long, MutableMap<Long, ModelEmojiCustom>>>()

    private var me: Long = 0

    fun getAllGuildEmoji(): Map<Long, Map<Long, ModelEmojiCustom>> = allGuildEmojiSnapshot

    @StoreThread
    fun getAllGuildEmojiInternal(): Map<Long, Map<Long, ModelEmojiCustom>> = allGuildEmoji

    fun getEmojiForGuild(guildId: Long): Map<Long, ModelEmojiCustom> =
        getAllGuildEmoji()[guildId].orEmpty()

    @StoreThread
    fun getEmojiForGuildInternal(guildId: Long): Map<Long, ModelEmojiCustom>? = allGuildEmoji[guildId]

    /**
     * Emits the flattened allowed-emoji map.
     *
     * Deliberately the persister's observable rather than an [com.discord.stores.updates.ObservationDeck]
     * connection: the cached value is replayed immediately on subscribe, so the picker has emoji to
     * draw before the socket connects.
     */
    fun observeAllowedGuildEmoji(): Observable<Map<Long, Map<Long, ModelEmojiCustom>>> =
        allowedGuildEmojiPersister.getObservable()

    // region gateway input

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        me = payload.me?.id ?: 0
        payload.guilds.orEmpty().forEach(::handleGuildAdd)
    }

    @StoreThread
    fun handleGuildAdd(guild: Guild) {
        // No member object for us means the payload is a lazy/unavailable guild: leaving the
        // previous state alone is right, because we cannot tell which emoji our roles grant.
        val meMember = guild.members?.firstOrNull { it.user.id == me } ?: return
        val emojis = guild.emojis.orEmpty().map { ModelEmojiCustom(it, guild.id) }
        updateGlobalEmojis(guild.id, emojis)
        updateAllowedGuildEmojis(guild.id, meMember.roles, emojis)
    }

    @StoreThread
    fun handleGuildRemove(guild: Guild) = handleGuildRemove(guild.id)

    @StoreThread
    fun handleGuildRemove(guildId: Long) {
        if (allowedGuildEmojis.containsKey(guildId)) {
            allowedGuildEmojis.remove(guildId)
            markChanged()
        }
        if (allGuildEmoji.containsKey(guildId)) {
            allGuildEmoji.remove(guildId)
            markChanged()
        }
    }

    /** Our own roles changed, so re-file the guild's emoji; other members' roles are irrelevant. */
    @StoreThread
    fun handleGuildMemberAdd(member: GuildMember) {
        if (member.user.id != me) return
        val emojis = allGuildEmoji[member.guildId]?.values ?: return
        updateAllowedGuildEmojis(member.guildId, member.roles, emojis)
    }

    @StoreThread
    fun handleEmojiUpdate(emojiUpdate: GuildEmojisUpdate) {
        val guildId = emojiUpdate.guildId
        val emojis = emojiUpdate.emojis.map { ModelEmojiCustom(it, guildId) }
        // Our roles come from the member store, and without them the allowed set cannot be
        // recomputed — so the reference drops the whole update rather than publishing a global
        // list the user might not be able to use.
        val meMember = storeGuilds.getGuildMembersComputedInternal()[guildId]?.get(me) ?: return
        updateGlobalEmojis(guildId, emojis)
        updateAllowedGuildEmojis(guildId, meMember.getRoles(), emojis)
    }

    // endregion

    override fun snapshotData() {
        super.snapshotData()
        allGuildEmojiSnapshot = allGuildEmoji.entries.associate { (guildId, emojis) ->
            guildId to emojis.toMap()
        }
        allowedGuildEmojiPersister.set(
            allowedGuildEmojis.entries.associate { (guildId, byRole) ->
                val flattened = LinkedHashMap<Long, ModelEmojiCustom>()
                byRole.values.forEach(flattened::putAll)
                guildId to flattened
            },
        )
    }

    @StoreThread
    internal fun reset() {
        allGuildEmoji.clear()
        allowedGuildEmojis.clear()
        markChanged()
    }

    @StoreThread
    private fun updateGlobalEmojis(guildId: Long, emojis: Collection<ModelEmojiCustom>) {
        if (emojis.isEmpty()) {
            allGuildEmoji.remove(guildId)
        } else {
            allGuildEmoji[guildId] =
                emojis.associateByTo(LinkedHashMap<Long, ModelEmojiCustom>(), ModelEmojiCustom::id)
        }
        markChanged()
    }

    @StoreThread
    private fun updateAllowedGuildEmojis(
        guildId: Long,
        myRoles: List<Long>,
        guildEmojis: Collection<ModelEmojiCustom>,
    ) {
        val allowed = allowedGuildEmojis.getOrPut(guildId, ::LinkedHashMap)
        allowed.clear()
        for (emoji in guildEmojis) {
            val roles = emoji.roles
            val grantingRole = if (roles.isEmpty()) NO_ROLE else myRoles.firstOrNull(roles::contains)
            if (grantingRole == null) continue
            val bucket = allowed.getOrPut(grantingRole, ::LinkedHashMap)
            if (bucket[emoji.id] != emoji) {
                bucket[emoji.id] = emoji
                markChanged()
            }
        }
    }

    companion object {
        /** Filing key for emoji any member may use. Never a real role id. */
        private const val NO_ROLE = 0L

        const val ALLOWED_EMOJI_CACHE_KEY = "STORE_EMOJI_AVAILABLE_V5"
    }
}

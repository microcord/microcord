package com.discord.stores

import com.discord.api.guild.Guild
import com.discord.api.presence.ClientStatus
import com.discord.api.presence.Presence as ApiPresence
import com.discord.models.domain.ModelPayload
import com.discord.models.user.User
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

/**
 * Who is currently in each game party, derived entirely from presence activities.
 *
 * A party id is an opaque string a game supplies in its rich presence; this store inverts the
 * user → party mapping so the "N others in this party" affordance on an invite can be rendered
 * without scanning every presence.
 *
 * Membership is tracked *per guild* ([userParties]) because the same user's presence arrives once
 * per guild they share with us; without the guild key, a leave in one guild would evict the user
 * from a party they are still in according to another.
 */
class StoreGameParty(
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val storeUserPresence: StoreUserPresence,
    private val storeUser: StoreUser,
) : StoreV2() {
    private val parties = HashMap<String, HashSet<Long>>()
    private val userParties = HashMap<Long, HashMap<Long, String>>()
    private var partiesSnapshot: Map<String, Set<Long>> = emptyMap()

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        payload.presences?.let(::handlePresences)
        payload.guilds.orEmpty().forEach(::handleGuildCreateOrSync)
    }

    @StoreThread
    fun handleConnectionOpenSupplemental(presences: List<ApiPresence>) = handlePresences(presences)

    @StoreThread
    fun handleGuildCreateOrSync(guild: Guild) {
        guild.presences?.let(::handlePresences)
    }

    @StoreThread
    fun handlePresenceReplace(presences: List<ApiPresence>) = handlePresences(presences)

    @StoreThread
    @JvmOverloads
    fun handlePresenceUpdate(presence: ApiPresence, guildId: Long = presence.guildId ?: 0L) =
        updateParty(presence, guildId)

    @StoreThread
    fun handleThreadMemberListUpdate(guildId: Long, presences: List<ApiPresence>) =
        presences.forEach { updateParty(it, guildId) }

    @StoreThread
    fun handleThreadMembersUpdate(guildId: Long, presences: List<ApiPresence>) =
        handleThreadMemberListUpdate(guildId, presences)

    fun observeUsersForPartyId(partyId: String?): Observable<Map<Long, User>> =
        observationDeck.connectRx(this, StoreUser.UsersUpdate) {
            val members = partiesSnapshot[partyId].orEmpty()
            storeUser.getUsers().filterKeys(members::contains)
        }.distinctUntilChanged()

    override fun snapshotData() {
        partiesSnapshot = parties.mapValues { (_, members) -> HashSet(members) }
    }

    @StoreThread
    private fun handlePresences(presences: List<ApiPresence>) =
        presences.forEach { updateParty(it, it.guildId ?: 0L) }

    /**
     * The party is read from [StoreUserPresence]'s flattened presence rather than from the incoming
     * payload: a `PRESENCE_UPDATE` may omit activities the user still has from another guild, and
     * the flattened value is the one the rest of the app renders.
     */
    @StoreThread
    private fun updateParty(presence: ApiPresence, guildId: Long) {
        val userId = presence.user?.id ?: return
        val previousPartyId = userParties[userId]?.get(guildId)
        val partyId = storeUserPresence.getPresencesInternal()[userId]
            ?.activities
            ?.firstOrNull { it.party?.id != null }
            ?.party
            ?.id
        if (partyId == null || presence.status == ClientStatus.OFFLINE) {
            removeUserFromParty(userId, guildId)
            return
        }
        if (previousPartyId != partyId) removeUserFromParty(userId, guildId)
        addUserToParty(userId, guildId, partyId)
    }

    @StoreThread
    private fun addUserToParty(userId: Long, guildId: Long, partyId: String) {
        // guildId 0 means "no guild context" (a DM presence); it is still a party membership, it
        // just cannot be evicted by a guild-scoped leave.
        if (guildId > 0) userParties.getOrPut(userId, ::HashMap)[guildId] = partyId
        parties[partyId] = HashSet(parties[partyId].orEmpty()).apply { add(userId) }
        markChanged()
    }

    @StoreThread
    private fun removeUserFromParty(userId: Long, guildId: Long) {
        val partyId = userParties[userId]?.remove(guildId) ?: return
        parties[partyId]?.let { members ->
            if (members.size == 1) {
                parties.remove(partyId)
            } else {
                parties[partyId] = HashSet(members).apply { remove(userId) }
            }
        }
        markChanged()
    }
}

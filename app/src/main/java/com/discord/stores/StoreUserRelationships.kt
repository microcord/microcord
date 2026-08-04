package com.discord.stores

import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelUserRelationship
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.persister.Persister
import rx.Observable

class StoreUserRelationships(
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val relationshipsCache: Persister<Map<Long, Int>> = Persister(CACHE_KEY, UNLOADED_SENTINEL),
    private val nicknamesCache: Persister<Map<Long, String>> = Persister(NICKNAMES_CACHE_KEY, emptyMap()),
) : StoreV2() {
    sealed class UserRelationshipsState {
        data object Unloaded : UserRelationshipsState()
        data class Loaded(val relationships: Map<Long, Int>) : UserRelationshipsState()
    }

    private var relationshipsState: UserRelationshipsState = UserRelationshipsState.Unloaded
    private var relationshipsStateSnapshot: UserRelationshipsState = UserRelationshipsState.Unloaded

    private var nicknames: Map<Long, String> = emptyMap()
    private var nicknamesSnapshot: Map<Long, String> = emptyMap()

    @StoreThread
    fun init() {
        StoreThreadScheduler.assertCurrentThread()
        relationshipsCache.get().takeUnless { it == UNLOADED_SENTINEL }?.let {
            relationshipsState = UserRelationshipsState.Loaded(LinkedHashMap(it))
            nicknames = LinkedHashMap(nicknamesCache.get())
            markChanged()
        }
    }

    fun getRelationshipsState() = relationshipsStateSnapshot
    fun getRelationships() = (relationshipsStateSnapshot as? UserRelationshipsState.Loaded)?.relationships.orEmpty()

    /** The nickname the user gave this friend, or null. Blank nicknames are stored as null. */
    fun getNickname(userId: Long): String? = nicknamesSnapshot[userId]

    fun getNicknames(): Map<Long, String> = nicknamesSnapshot

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        StoreThreadScheduler.assertCurrentThread()
        val relationships = payload.relationships.orEmpty()
        relationshipsState = UserRelationshipsState.Loaded(relationships.associate { it.id to it.type })
        nicknames = relationships.mapNotNull { relationship ->
            relationship.nickname?.takeUnless(String::isBlank)?.let { relationship.id to it }
        }.toMap()
        markChanged()
    }

    @StoreThread fun handlePreLogout() {
        StoreThreadScheduler.assertCurrentThread()
        relationshipsState = UserRelationshipsState.Unloaded
        nicknames = emptyMap()
        markChanged()
    }

    @StoreThread fun handleRelationshipAdd(relationship: ModelUserRelationship) = update(
        types = { it[relationship.id] = relationship.type },
        nicks = { nicks ->
            val nickname = relationship.nickname?.takeUnless(String::isBlank)
            if (nickname == null) nicks.remove(relationship.id) else nicks[relationship.id] = nickname
        },
    )

    @StoreThread fun handleRelationshipRemove(relationship: ModelUserRelationship) = update(
        types = { it.remove(relationship.id) },
        nicks = { it.remove(relationship.id) },
    )

    fun observe(): Observable<Map<Long, Int>> = observationDeck.connectRx(this) { getRelationships() }.distinctUntilChanged()
    fun observe(userId: Long): Observable<Int?> = observe().map { it[userId] }.distinctUntilChanged()
    fun observe(userIds: Collection<Long>): Observable<Map<Long, Int>> {
        val ids = userIds.toSet()
        return observe().map { it.filterKeys(ids::contains) }.distinctUntilChanged()
    }
    fun observeForType(relationshipType: Int): Observable<Map<Long, Int>> =
        observe().map { it.filterValues(relationshipType::equals) }.distinctUntilChanged()
    fun observeUserRelationshipsState(): Observable<UserRelationshipsState> =
        observationDeck.connectRx(this) { relationshipsStateSnapshot }.distinctUntilChanged()

    fun observeNicknames(): Observable<Map<Long, String>> =
        observationDeck.connectRx(this) { getNicknames() }.distinctUntilChanged()

    fun observeNickname(userId: Long): Observable<String?> =
        observeNicknames().map { it[userId] }.distinctUntilChanged()

    override fun snapshotData() {
        relationshipsStateSnapshot = when (val state = relationshipsState) {
            is UserRelationshipsState.Loaded -> UserRelationshipsState.Loaded(LinkedHashMap(state.relationships).also(relationshipsCache::set))
            UserRelationshipsState.Unloaded -> UserRelationshipsState.Unloaded.also { relationshipsCache.clear() }
        }
        nicknamesSnapshot = LinkedHashMap(nicknames).also(nicknamesCache::set)
    }

    @StoreThread
    private fun update(
        types: (MutableMap<Long, Int>) -> Unit,
        nicks: (MutableMap<Long, String>) -> Unit,
    ) {
        StoreThreadScheduler.assertCurrentThread()
        val beforeTypes = (relationshipsState as? UserRelationshipsState.Loaded)?.relationships.orEmpty()
        val changedTypes = LinkedHashMap(beforeTypes).apply(types)
        val changedNicks = LinkedHashMap(nicknames).apply(nicks)
        if (changedTypes == beforeTypes && changedNicks == nicknames) return
        relationshipsState = UserRelationshipsState.Loaded(changedTypes)
        nicknames = changedNicks
        markChanged()
    }

    companion object {
        const val CACHE_KEY = "STORE_USER_RELATIONSHIPS_V9"
        const val NICKNAMES_CACHE_KEY = "STORE_USER_RELATIONSHIP_NICKNAMES_V1"
        private val UNLOADED_SENTINEL = mapOf(-1L to -1)
    }
}

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
) : StoreV2() {
    sealed class UserRelationshipsState {
        data object Unloaded : UserRelationshipsState()
        data class Loaded(val relationships: Map<Long, Int>) : UserRelationshipsState()
    }

    private var relationshipsState: UserRelationshipsState = UserRelationshipsState.Unloaded
    private var relationshipsStateSnapshot: UserRelationshipsState = UserRelationshipsState.Unloaded

    @StoreThread
    fun init() {
        StoreThreadScheduler.assertCurrentThread()
        relationshipsCache.get().takeUnless { it == UNLOADED_SENTINEL }?.let {
            relationshipsState = UserRelationshipsState.Loaded(LinkedHashMap(it))
            markChanged()
        }
    }

    fun getRelationshipsState() = relationshipsStateSnapshot
    fun getRelationships() = (relationshipsStateSnapshot as? UserRelationshipsState.Loaded)?.relationships.orEmpty()

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        StoreThreadScheduler.assertCurrentThread()
        relationshipsState = UserRelationshipsState.Loaded(
            payload.relationships.orEmpty().associate { it.id to it.type },
        )
        markChanged()
    }

    @StoreThread fun handlePreLogout() {
        StoreThreadScheduler.assertCurrentThread()
        relationshipsState = UserRelationshipsState.Unloaded
        markChanged()
    }

    @StoreThread fun handleRelationshipAdd(relationship: ModelUserRelationship) = update { it[relationship.id] = relationship.type }
    @StoreThread fun handleRelationshipRemove(relationship: ModelUserRelationship) = update { it.remove(relationship.id) }

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

    override fun snapshotData() {
        relationshipsStateSnapshot = when (val state = relationshipsState) {
            is UserRelationshipsState.Loaded -> UserRelationshipsState.Loaded(LinkedHashMap(state.relationships).also(relationshipsCache::set))
            UserRelationshipsState.Unloaded -> UserRelationshipsState.Unloaded.also { relationshipsCache.clear() }
        }
    }

    @StoreThread
    private fun update(block: (MutableMap<Long, Int>) -> Unit) {
        StoreThreadScheduler.assertCurrentThread()
        val before = (relationshipsState as? UserRelationshipsState.Loaded)?.relationships.orEmpty()
        val changed = LinkedHashMap(before).apply(block)
        if (changed == before) return
        relationshipsState = UserRelationshipsState.Loaded(changed)
        markChanged()
    }

    companion object {
        const val CACHE_KEY = "STORE_USER_RELATIONSHIPS_V9"
        private val UNLOADED_SENTINEL = mapOf(-1L to -1)
    }
}

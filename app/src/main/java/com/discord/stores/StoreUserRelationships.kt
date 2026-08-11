package com.discord.stores

import com.discord.models.domain.ModelPayload
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.domain.ModelUserRelationship
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.persister.Persister
import rx.Observable

class StoreUserRelationships(
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val relationshipsCache: Persister<Map<Long, Int>> = Persister(CACHE_KEY, UNLOADED_SENTINEL),
    private val nicknamesCache: Persister<Map<Long, String>> = Persister(NICKNAMES_CACHE_KEY, emptyMap()),
    private val ignoredCache: Persister<Set<Long>> = Persister(IGNORED_CACHE_KEY, emptySet()),
    private val sinceCache: Persister<Map<Long, Long>> = Persister(SINCE_CACHE_KEY, emptyMap()),
) : StoreV2() {
    sealed class UserRelationshipsState {
        data object Unloaded : UserRelationshipsState()
        data class Loaded(val relationships: Map<Long, Int>) : UserRelationshipsState()
    }

    private var relationshipsState: UserRelationshipsState = UserRelationshipsState.Unloaded
    private var relationshipsStateSnapshot: UserRelationshipsState = UserRelationshipsState.Unloaded

    private var nicknames: Map<Long, String> = emptyMap()
    private var nicknamesSnapshot: Map<Long, String> = emptyMap()

    private var ignored: Set<Long> = emptySet()
    private var ignoredSnapshot: Set<Long> = emptySet()

    private var since: Map<Long, Long> = emptyMap()
    private var sinceSnapshot: Map<Long, Long> = emptyMap()

    @StoreThread
    fun init() {
        StoreThreadScheduler.assertCurrentThread()
        relationshipsCache.get().takeUnless { it == UNLOADED_SENTINEL || it.isEmpty() }?.let {
            relationshipsState = UserRelationshipsState.Loaded(LinkedHashMap(it))
            nicknames = LinkedHashMap(nicknamesCache.get())
            ignored = LinkedHashSet(ignoredCache.get())
            since = LinkedHashMap(sinceCache.get())
            markChanged()
        }
    }

    fun getRelationshipsState() = relationshipsStateSnapshot
    fun getRelationships() = (relationshipsStateSnapshot as? UserRelationshipsState.Loaded)?.relationships.orEmpty()

    /** The nickname the user gave this friend, or null. Blank nicknames are stored as null. */
    fun getNickname(userId: Long): String? = nicknamesSnapshot[userId]

    fun getNicknames(): Map<Long, String> = nicknamesSnapshot

    /** When the friendship started, for friends whose relationship carried a `since`. */
    fun getSince(userId: Long): UtcDateTime? = sinceSnapshot[userId]?.let(::UtcDateTime)

    /** Ids the viewer has ignored, excluding blocked users. */
    fun getIgnoredUserIds(): Set<Long> = ignoredSnapshot

    /**
     * Blocking supersedes ignoring, so a blocked user never reports as ignored. Otherwise the
     * softer treatment would win wherever the two are tested in order.
     */
    fun isIgnored(userId: Long): Boolean = !isBlocked(userId) && userId in ignoredSnapshot

    fun isBlockedOrIgnored(userId: Long): Boolean = isBlocked(userId) || isIgnored(userId)

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        StoreThreadScheduler.assertCurrentThread()
        val relationships = payload.relationships.orEmpty()
        relationshipsState = UserRelationshipsState.Loaded(relationships.associate { it.id to it.type })
        nicknames = relationships.mapNotNull { relationship ->
            relationship.nickname?.takeUnless(String::isBlank)?.let { relationship.id to it }
        }.toMap()
        ignored = relationships.filter { it.userIgnored }.map { it.id }.toSet()
        since = relationships.mapNotNull { relationship ->
            relationship.since?.let { relationship.id to it.dateTimeMillis }
        }.toMap()
        markChanged()
    }

    @StoreThread fun handlePreLogout() {
        StoreThreadScheduler.assertCurrentThread()
        relationshipsState = UserRelationshipsState.Unloaded
        nicknames = emptyMap()
        ignored = emptySet()
        since = emptyMap()
        markChanged()
    }

    @StoreThread fun handleRelationshipAdd(relationship: ModelUserRelationship) = update(
        types = { it[relationship.id] = relationship.type },
        nicks = { nicks ->
            val nickname = relationship.nickname?.takeUnless(String::isBlank)
            if (nickname == null) nicks.remove(relationship.id) else nicks[relationship.id] = nickname
        },
        ignores = { if (relationship.userIgnored) it.add(relationship.id) else it.remove(relationship.id) },
        sinces = { sinces ->
            val started = relationship.since?.dateTimeMillis
            if (started == null) sinces.remove(relationship.id) else sinces[relationship.id] = started
        },
    )

    /**
     * Removing a relationship does not lift an ignore.
     *
     * The server sends a remove for users who are still ignored — un-friending someone you had
     * also ignored, for instance — so dropping the flag here would silently un-ignore them.
     */
    @StoreThread fun handleRelationshipRemove(relationship: ModelUserRelationship) = update(
        types = { it.remove(relationship.id) },
        nicks = { it.remove(relationship.id) },
        ignores = { if (!relationship.userIgnored) it.remove(relationship.id) },
        sinces = { it.remove(relationship.id) },
    )

    /**
     * Applies an ignore locally.
     *
     * The endpoint returns no body, and the gateway sends no relationship event for a user the
     * viewer has no relationship with, so without this the toggle would not stick.
     */
    @StoreThread fun handleIgnoreUpdate(userId: Long, isIgnored: Boolean) = update(
        types = {},
        nicks = {},
        ignores = { if (isIgnored) it.add(userId) else it.remove(userId) },
        sinces = {},
    )

    fun observe(): Observable<Map<Long, Int>> = observationDeck.connectRx(this) { getRelationships() }.distinctUntilChanged()
    fun observe(userId: Long): Observable<Int?> = observe().map { it[userId] }.distinctUntilChanged()
    fun observe(userIds: Collection<Long>): Observable<Map<Long, Int>> {
        val ids = userIds.toSet()
        return observe().map { it.filterKeys(ids::contains) }.distinctUntilChanged()
    }
    fun observeForType(relationshipType: Int): Observable<Map<Long, Int>> =
        observe().map { it.filterValues(relationshipType::equals) }.distinctUntilChanged()

    fun isBlocked(userId: Long): Boolean =
        getRelationships()[userId] == ModelUserRelationship.TYPE_BLOCKED

    fun observeBlocked(): Observable<Map<Long, Int>> =
        observeForType(ModelUserRelationship.TYPE_BLOCKED)
    fun observeUserRelationshipsState(): Observable<UserRelationshipsState> =
        observationDeck.connectRx(this) { relationshipsStateSnapshot }.distinctUntilChanged()

    fun observeIgnoredUserIds(): Observable<Set<Long>> =
        observationDeck.connectRx(this) { getIgnoredUserIds() }.distinctUntilChanged()

    /** Ids whose content is hidden from the viewer: blocked, plus ignored. */
    fun observeBlockedOrIgnored(): Observable<Set<Long>> = Observable.combineLatest(
        observeBlocked(),
        observeIgnoredUserIds(),
    ) { blocked, ignoredIds -> blocked.keys + ignoredIds }.distinctUntilChanged()

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
        ignoredSnapshot = LinkedHashSet(ignored).also(ignoredCache::set)
        sinceSnapshot = LinkedHashMap(since).also(sinceCache::set)
    }

    @StoreThread
    private fun update(
        types: (MutableMap<Long, Int>) -> Unit,
        nicks: (MutableMap<Long, String>) -> Unit,
        ignores: (MutableSet<Long>) -> Unit,
        sinces: (MutableMap<Long, Long>) -> Unit,
    ) {
        StoreThreadScheduler.assertCurrentThread()
        val beforeTypes = (relationshipsState as? UserRelationshipsState.Loaded)?.relationships.orEmpty()
        val changedTypes = LinkedHashMap(beforeTypes).apply(types)
        val changedNicks = LinkedHashMap(nicknames).apply(nicks)
        val changedIgnores = LinkedHashSet(ignored).apply(ignores)
        if (changedTypes == beforeTypes && changedNicks == nicknames && changedIgnores == ignored) return
        relationshipsState = UserRelationshipsState.Loaded(changedTypes)
        nicknames = changedNicks
        ignored = changedIgnores
        markChanged()
    }

    companion object {
        const val CACHE_KEY = "STORE_USER_RELATIONSHIPS_V9"
        const val NICKNAMES_CACHE_KEY = "STORE_USER_RELATIONSHIP_NICKNAMES_V1"
        const val IGNORED_CACHE_KEY = "STORE_USER_RELATIONSHIP_IGNORED_V1"
        const val SINCE_CACHE_KEY = "STORE_USER_RELATIONSHIP_SINCE_V1"
        private val UNLOADED_SENTINEL = mapOf(-1L to -1)
    }
}

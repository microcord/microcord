package com.discord.stores

import android.content.Context
import com.discord.api.channel.Channel
import com.discord.api.guild.Guild
import com.discord.api.guildmember.GuildMember
import com.discord.api.presence.Presence
import com.discord.api.user.NsfwAllowance
import com.discord.api.user.User as ApiUser
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.message.Message
import com.discord.models.user.CoreUser
import com.discord.models.user.MeUser
import com.discord.models.user.User
import com.discord.nullserializable.NullSerializable
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import rx.Observable

class StoreUser(
    private val notifyUserUpdated: (ApiUser) -> Unit = {},
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    private val meCache: Persister<MeUser> = Persister(ME_CACHE_KEY, EMPTY_ME_USER),
    private val requestUsers: (Set<Long>, (ApiUser) -> Unit) -> Unit = { _, _ -> },
) : StoreV2() {
    private var me = EMPTY_ME_USER
    private val users = LinkedHashMap<Long, User>()
    private val requestedUserIds = HashSet<Long>()
    private var meSnapshot = me
    private var usersSnapshot: Map<Long, User> = emptyMap()

    override fun init(context: Context) {
        restore()
    }

    @StoreThread
    internal fun restore() {
        StoreThreadScheduler.assertCurrentThread()
        me = meCache.get()
        markChanged(MeUpdate)
    }

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        StoreThreadScheduler.assertCurrentThread()
        val apiMe = requireNotNull(payload.me)
        users.clear()
        requestedUserIds.clear()
        me = MeUser(apiMe)
        users[me.id] = me
        payload.privateChannels.orEmpty().flatMap { it.recipients.orEmpty() }.forEach(::putUser)
        payload.relationships.orEmpty().mapNotNull(ModelUserRelationship::user).forEach(::putUser)
        payload.guilds.orEmpty().flatMap { it.members.orEmpty() }.map(GuildMember::user).forEach(::putUser)
        markChanged(MeUpdate, UsersUpdate)
    }

    @StoreThread
    fun handleAuthToken(token: String?) {
        StoreThreadScheduler.assertCurrentThread()
        if (token != null) return
        me = EMPTY_ME_USER
        markChanged(MeUpdate)
    }

    @StoreThread fun handleChannelCreated(channel: Channel) = channel.recipients.orEmpty().forEach(::updateUser)
    @StoreThread fun handleGuildAddOrSync(guild: Guild) = guild.members.orEmpty().forEach { updateUser(it.user) }
    @StoreThread fun handleGuildMemberAdd(member: GuildMember) = updateUser(member.user)
    @StoreThread fun handleGuildMembersChunk(members: List<GuildMember>) = members.forEach { updateUser(it.user) }
    @StoreThread fun handleGuildScheduledEventUsersFetch(users: List<ApiUser>) = users.forEach(::updateUser)
    @StoreThread fun handleFriendSuggestionCreate(user: ApiUser) = updateUser(user)
    @StoreThread fun handleFriendSuggestionsLoaded(users: List<ApiUser>) = users.forEach(::updateUser)
    @StoreThread fun handlePresenceUpdate(presence: Presence) = presence.user?.takeIf { it.username.isNotEmpty() }?.let(::updateUser)
    @StoreThread fun handleUserRelationshipAdd(relationship: ModelUserRelationship) = relationship.user?.let(::updateUser)
    @StoreThread fun handleMessageCreateOrUpdate(message: Message) {
        message.author?.let(::updateUser)
        message.mentions.orEmpty().forEach(::updateUser)
        if (message.isUrgent() && me.flags and URGENT_MESSAGE_USER_FLAG == 0) {
            me = me.copy(flags = me.flags or URGENT_MESSAGE_USER_FLAG)
            users[me.id] = me
            markChanged(MeUpdate, UsersUpdate)
        }
    }
    @StoreThread fun handleMessagesLoaded(messages: List<Message>) = messages.forEach(::handleMessageCreateOrUpdate)
    @StoreThread fun handleThreadMemberListUpdate(members: List<GuildMember>, presences: List<Presence>) {
        members.forEach { updateUser(it.user) }
        presences.mapNotNull(Presence::user).forEach(::updateUser)
    }
    @StoreThread fun handleThreadMembersUpdate(members: List<GuildMember>, presences: List<Presence>) =
        handleThreadMemberListUpdate(members, presences)

    @StoreThread
    fun handleUserUpdated(user: ApiUser) {
        updateUser(user)
        if (me.id == user.id) {
            me = mergeMe(me, user)
            users[me.id] = me
            markChanged(MeUpdate, UsersUpdate)
        }
    }

    fun fetchUsers(userIds: List<Long>) = dispatcher.schedule {
        fetchMissing(userIds, users.keys)
    }

    fun getMe(): MeUser = meSnapshot
    @StoreThread fun getMeInternal(): MeUser = me
    fun getUsers(): Map<Long, User> = usersSnapshot
    @StoreThread fun getUsersInternal(): Map<Long, User> = users
    fun getUsers(userIds: Collection<Long>, fetchMissing: Boolean = false): Map<Long, User> {
        val filtered = usersSnapshot.filterKeys(userIds.toSet()::contains)
        if (fetchMissing) dispatcher.schedule { fetchMissing(userIds, filtered.keys) }
        return filtered
    }

    fun observeAllUsers(): Observable<Map<Long, User>> = observationDeck.connectRx(this) { usersSnapshot }
    @JvmOverloads fun observeMe(emitEmpty: Boolean = false): Observable<MeUser> =
        observationDeck.connectRx(MeUpdate) { meSnapshot }.filter { emitEmpty || it != EMPTY_ME_USER }.distinctUntilChanged()
    fun observeMeId(): Observable<Long> = observationDeck.connectRx(MeUpdate) { meSnapshot.id }.distinctUntilChanged()
    fun observeUser(userId: Long): Observable<User?> = observeAllUsers().map { it[userId] }.distinctUntilChanged()
    @JvmOverloads fun observeUsers(userIds: Collection<Long>, fetchMissing: Boolean = false): Observable<Map<Long, User>> {
        val ids = userIds.toSet()
        return observeAllUsers().map { it.filterKeys(ids::contains) }.doOnNext { filtered ->
            if (fetchMissing) dispatcher.schedule { fetchMissing(userIds, filtered.keys) }
        }.distinctUntilChanged()
    }
    fun observeUsernames(userIds: Collection<Long>): Observable<Map<Long, String>> =
        observeUsers(userIds).map { users -> users.mapValues { it.value.username } }.distinctUntilChanged()

    @StoreThread
    internal fun reset() {
        StoreThreadScheduler.assertCurrentThread()
        me = EMPTY_ME_USER
        users.clear()
        requestedUserIds.clear()
        meCache.clear()
        markChanged(MeUpdate, UsersUpdate)
    }

    override fun snapshotData() {
        // Only the specific update sources gate these copies. `this` is always present once
        // markChanged() ran, so testing for it would copy the whole user map every dispatch.
        if (UsersUpdate in updateSources) usersSnapshot = LinkedHashMap(users)
        if (MeUpdate in updateSources) {
            meSnapshot = me
            meCache.set(me)
        }
    }

    @StoreThread
    private fun updateUser(user: ApiUser) {
        StoreThreadScheduler.assertCurrentThread()
        requestedUserIds.remove(user.id)
        val updated = when (val old = users[user.id]) {
            is MeUser -> mergeMe(old, user)
            is CoreUser -> mergeCore(old, user)
            else -> CoreUser(user)
        }
        if (users[user.id] == updated) return
        users[user.id] = updated
        markChanged(UsersUpdate)
    }

    private fun putUser(user: ApiUser) {
        users[user.id] = CoreUser(user)
    }

    @StoreThread
    private fun fetchMissing(userIds: Collection<Long>, loadedIds: Set<Long>) {
        StoreThreadScheduler.assertCurrentThread()
        val missing = userIds.filterTo(LinkedHashSet()) { it !in loadedIds && requestedUserIds.add(it) }
        if (missing.isEmpty()) return
        requestUsers(missing) { user ->
            dispatcher.schedule {
                requestedUserIds.remove(user.id)
                notifyUserUpdated(user)
            }
        }
    }

    private fun mergeCore(old: CoreUser, new: ApiUser) = old.copy(
        username = new.username.ifEmpty { old.username },
        avatar = merge(old.avatar, new.avatar),
        banner = merge(old.banner, new.banner),
        isBot = new.bot ?: old.isBot,
        isSystemUser = new.system ?: old.isSystemUser,
        discriminator = new.discriminator.toIntOrNull()?.takeUnless { it == 0 } ?: old.discriminator,
        premiumTier = new.premiumType ?: old.premiumTier,
        flags = new.flags ?: old.flags,
        publicFlags = new.publicFlags ?: old.publicFlags,
        bio = merge(old.bio, new.bio),
        bannerColor = merge(old.bannerColor, new.bannerColor),
    )

    private fun mergeMe(old: MeUser, new: ApiUser) = old.copy(
        username = new.username.ifEmpty { old.username },
        avatar = merge(old.avatar, new.avatar),
        banner = merge(old.banner, new.banner),
        isBot = new.bot ?: old.isBot,
        isSystemUser = new.system ?: old.isSystemUser,
        discriminator = new.discriminator.toIntOrNull()?.takeUnless { it == 0 } ?: old.discriminator,
        premiumTier = new.premiumType ?: old.premiumTier,
        email = new.email ?: old.email,
        mfaEnabled = new.mfaEnabled ?: old.mfaEnabled,
        isVerified = new.verified ?: old.isVerified,
        token = new.token ?: old.token,
        flags = new.flags ?: old.flags,
        publicFlags = new.publicFlags ?: old.publicFlags,
        phoneNumber = (new.phone as? com.discord.api.user.Phone.PhoneNumber)?.number ?: old.phoneNumber,
        nsfwAllowance = new.nsfwAllowed ?: old.nsfwAllowance,
        bio = merge(old.bio, new.bio),
        bannerColor = merge(old.bannerColor, new.bannerColor),
    )

    private fun <T> merge(old: T?, new: NullSerializable<T>?): T? = when (new) {
        null -> old
        is NullSerializable.Null -> null
        is NullSerializable.Value -> new.value
    }

    companion object {
        const val ME_CACHE_KEY = "STORE_USERS_ME_V13"
        private const val URGENT_MESSAGE_USER_FLAG = 8192
        @JvmField val MeUpdate = object : ObservationDeck.UpdateSource {}
        @JvmField val UsersUpdate = object : ObservationDeck.UpdateSource {}
        @JvmField val EMPTY_ME_USER = MeUser(
            0, "EMPTY_USERNAME", null, null, false, false, 0,
            com.discord.api.premium.PremiumTier.NONE, null, false, false, null,
            0, 0, null, NsfwAllowance.UNKNOWN, null, null,
        )
    }
}

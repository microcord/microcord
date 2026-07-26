package com.discord.stores

import com.discord.api.guildmember.GuildMember
import com.discord.api.user.User
import com.discord.api.user.UserProfile
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

class StoreUserProfile(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val fetchProfile: (
        userId: Long,
        guildId: Long?,
        withMutualGuilds: Boolean,
        onSuccess: (UserProfile) -> Unit,
        onFailure: () -> Unit,
    ) -> Unit,
    private val propagateMember: ((GuildMember, Long?) -> Unit)? = null,
) : StoreV2() {
    private val profiles = HashMap<Long, UserProfile>()
    private val profilesLoading = HashSet<Long>()
    private val profileErrors = HashSet<Long>()
    private var profilesSnapshot: Map<Long, UserProfile> = emptyMap()
    private var profilesLoadingSnapshot: Set<Long> = emptySet()
    private var profileErrorsSnapshot: Set<Long> = emptySet()

    fun getUserProfile(userId: Long): UserProfile = profilesSnapshot[userId] ?: EMPTY_PROFILE
    fun getUserProfiles(): Map<Long, UserProfile> = profilesSnapshot
    fun getProfilesLoading(): Set<Long> = profilesLoadingSnapshot
    fun observeProfileError(userId: Long): Observable<Boolean> =
        observationDeck.connectRx(this) { userId in profileErrorsSnapshot }.distinctUntilChanged()

    fun observeUserProfile(userId: Long): Observable<UserProfile> =
        observationDeck.connectRx(this) { getUserProfile(userId) }.distinctUntilChanged()

    fun observeUserProfiles(): Observable<Map<Long, UserProfile>> =
        observationDeck.connectRx(this) { profilesSnapshot }.distinctUntilChanged()

    fun fetchProfile(
        userId: Long,
        guildId: Long? = null,
        withMutualGuilds: Boolean = false,
        onFetchSuccess: ((UserProfile) -> Unit)? = null,
    ) = dispatcher.schedule {
        if (!profilesLoading.add(userId)) return@schedule
        profileErrors.remove(userId)
        markChanged()
        fetchProfile(
            userId,
            guildId,
            withMutualGuilds,
            { profile ->
                dispatcher.schedule {
                    handleUserProfile(profile)
                    // The reference's `StoreStream.handleUserProfile(profile, guildId)` fan-out is
                    // exactly this: the member is only propagated when the fetch was scoped to a
                    // guild, because the profile's member has no guild id of its own.
                    if (guildId != null) {
                        profile.guildMember?.let { propagateMember?.invoke(it, guildId) }
                    }
                    onFetchSuccess?.invoke(profile)
                }
            },
            { dispatcher.schedule { handleFailure(userId) } },
        )
    }

    fun updateUser(user: User) = dispatcher.schedule { handleUser(user) }

    @StoreThread
    fun handleUserProfile(userProfile: UserProfile) {
        StoreThreadScheduler.assertCurrentThread()
        profilesLoading.remove(userProfile.user.id)
        profileErrors.remove(userProfile.user.id)
        profiles[userProfile.user.id] = userProfile
        markChanged()
    }

    @StoreThread
    fun handlePreLogout() {
        StoreThreadScheduler.assertCurrentThread()
        profiles.clear()
        profilesLoading.clear()
        profileErrors.clear()
        markChanged()
    }

    override fun snapshotData() {
        profilesSnapshot = HashMap(profiles)
        profilesLoadingSnapshot = HashSet(profilesLoading)
        profileErrorsSnapshot = HashSet(profileErrors)
    }

    @StoreThread
    private fun handleFailure(userId: Long) {
        StoreThreadScheduler.assertCurrentThread()
        profilesLoading.remove(userId)
        profileErrors.add(userId)
        markChanged()
    }

    @StoreThread
    private fun handleUser(user: User) {
        StoreThreadScheduler.assertCurrentThread()
        val profile = profiles[user.id] ?: return
        profiles[user.id] = profile.copy(user = user, guildMember = null)
        markChanged()
    }

    companion object {
        val EMPTY_PROFILE = UserProfile()
    }
}

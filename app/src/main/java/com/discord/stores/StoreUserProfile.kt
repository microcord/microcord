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
    private val fetchProfileEffect: (
        skuId: Long,
        onSuccess: (UserProfile.ProfileEffectProduct) -> Unit,
        onFailure: () -> Unit,
    ) -> Unit = { _, _, failure -> failure() },
) : StoreV2() {
    private val profiles = HashMap<Long, UserProfile>()
    private val profilesLoading = HashSet<Long>()
    private val profileErrors = HashSet<Long>()
    // ponytail: session cache; add the shop's ten-minute refresh only if effect metadata proves mutable.
    private val profileEffectProducts = HashMap<Long, UserProfile.ProfileEffectProduct>()
    private val profileEffectsLoading = HashSet<Long>()
    private var profileEffectGeneration = 0
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
    ) = dispatcher.schedule { startFetch(userId, guildId, withMutualGuilds, onFetchSuccess) }

    /**
     * Fetches only when nothing is cached. A screen that merely reads a profile calls this, so
     * re-opening it does not re-request; [invalidateProfile] is what forces the next read to refetch.
     */
    fun fetchProfileIfMissing(
        userId: Long,
        guildId: Long? = null,
        withMutualGuilds: Boolean = false,
    ) = dispatcher.schedule {
        if (profiles.containsKey(userId) || userId in profilesLoading) return@schedule
        startFetch(userId, guildId, withMutualGuilds, null)
    }

    fun invalidateProfile(userId: Long) = dispatcher.schedule {
        if (profiles.remove(userId) == null) return@schedule
        markChanged()
    }

    @StoreThread
    private fun startFetch(
        userId: Long,
        guildId: Long?,
        withMutualGuilds: Boolean,
        onFetchSuccess: ((UserProfile) -> Unit)?,
    ) {
        StoreThreadScheduler.assertCurrentThread()
        if (!profilesLoading.add(userId)) return
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

    /** Folds the metadata returned by a profile PATCH back into [userId]'s cached profile. */
    fun updateProfileMetadata(userId: Long, metadata: UserProfile.ProfileMetadata) =
        dispatcher.schedule { handleProfileMetadata(userId, metadata) }

    @StoreThread
    fun handleUserProfile(userProfile: UserProfile) {
        StoreThreadScheduler.assertCurrentThread()
        profilesLoading.remove(userProfile.user.id)
        profileErrors.remove(userProfile.user.id)
        val resolved = resolveProfileEffects(userProfile)
        profiles[userProfile.user.id] = resolved
        loadProfileEffects(resolved)
        markChanged()
    }

    @StoreThread
    fun handlePreLogout() {
        StoreThreadScheduler.assertCurrentThread()
        profiles.clear()
        profilesLoading.clear()
        profileErrors.clear()
        profileEffectProducts.clear()
        profileEffectsLoading.clear()
        profileEffectGeneration++
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
        profiles[user.id] = profile.copy(user = user)
        markChanged()
    }

    /** A guild-scoped response carries its `guild_id`; a global one does not. */
    @StoreThread
    private fun handleProfileMetadata(userId: Long, metadata: UserProfile.ProfileMetadata) {
        StoreThreadScheduler.assertCurrentThread()
        val profile = profiles[userId] ?: return
        val updated = if (metadata.guildId != null) {
            profile.copy(guildMemberProfile = metadata)
        } else {
            profile.copy(userProfile = metadata)
        }
        val resolved = resolveProfileEffects(updated)
        profiles[userId] = resolved
        loadProfileEffects(resolved)
        markChanged()
    }

    @StoreThread
    private fun loadProfileEffects(profile: UserProfile) {
        listOfNotNull(
            profile.userProfile?.profileEffect,
            profile.guildMemberProfile?.profileEffect,
        ).forEach { effect ->
            effect.product?.let {
                profileEffectProducts[effect.skuId] = it
                return@forEach
            }
            if (effect.skuId in profileEffectProducts || !profileEffectsLoading.add(effect.skuId)) {
                return@forEach
            }
            val generation = profileEffectGeneration
            fetchProfileEffect(
                effect.skuId,
                { product ->
                    dispatcher.schedule {
                        if (generation == profileEffectGeneration) {
                            handleProfileEffectProduct(effect.skuId, product)
                        }
                    }
                },
                {
                    dispatcher.schedule {
                        if (generation == profileEffectGeneration) {
                            profileEffectsLoading.remove(effect.skuId)
                        }
                    }
                },
            )
        }
    }

    @StoreThread
    private fun handleProfileEffectProduct(
        skuId: Long,
        product: UserProfile.ProfileEffectProduct,
    ) {
        StoreThreadScheduler.assertCurrentThread()
        profileEffectsLoading.remove(skuId)
        profileEffectProducts[skuId] = product
        var changed = false
        profiles.entries.forEach { entry ->
            val resolved = resolveProfileEffects(entry.value)
            if (resolved != entry.value) {
                entry.setValue(resolved)
                changed = true
            }
        }
        if (changed) markChanged()
    }

    @StoreThread
    private fun resolveProfileEffects(profile: UserProfile): UserProfile {
        fun UserProfile.ProfileMetadata?.resolve(): UserProfile.ProfileMetadata? {
            val metadata = this ?: return null
            val effect = metadata.profileEffect ?: return metadata
            val product = effect.product ?: profileEffectProducts[effect.skuId] ?: return metadata
            return metadata.copy(profileEffect = effect.copy(product = product))
        }

        val user = profile.userProfile.resolve()
        val guild = profile.guildMemberProfile.resolve()
        return if (user == profile.userProfile && guild == profile.guildMemberProfile) profile else
            profile.copy(userProfile = user, guildMemberProfile = guild)
    }

    companion object {
        val EMPTY_PROFILE = UserProfile()
    }
}

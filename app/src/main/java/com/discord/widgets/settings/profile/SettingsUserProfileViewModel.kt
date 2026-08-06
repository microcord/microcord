package com.discord.widgets.settings.profile

import android.content.Context
import androidx.annotation.MainThread
import com.discord.api.collectibles.CollectibleProduct
import com.discord.api.guildmember.PatchCollectibles
import com.discord.api.guildmember.PatchGuildMemberBody
import com.discord.api.guildmember.PatchNameplate
import com.discord.api.user.AvatarDecorationData
import com.discord.api.user.Nameplate
import com.discord.api.user.PatchUserBody
import com.discord.api.user.PatchUserProfileBody
import com.discord.api.user.UserProfile
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.MeUser
import com.discord.nullserializable.NullSerializable
import com.discord.restapi.RestAPIInterface
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.parser.Parser
import com.discord.stores.StoreCollectibles
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserProfile
import com.discord.utilities.channel.GuildChannelsInfo
import com.discord.utilities.premium.grantsAccessToCustomTagAndAnimatedAvatarFeatures
import com.discord.utilities.premium.grantsAccessToProfileCustomization
import com.discord.utilities.user.UserUtils.isPremiumTier2
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessageParseState
import com.discord.utilities.textprocessing.MessagePreprocessor
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.textprocessing.node.SpoilerNode
import com.discord.widgets.user.profile.UserProfileHeaderViewModel
import rx.Observable
import rx.subjects.PublishSubject
import java.io.Serializable

/** What one completed save request changed, applied to the state the save started from. */
private typealias SavedEdit =
    (SettingsUserProfileViewModel.ViewState.Loaded) -> SettingsUserProfileViewModel.ViewState.Loaded

class SettingsUserProfileViewModel(
    private val guildId: Long?,
    private val storeUserProfile: StoreUserProfile = StoreStream.getUserProfiles(),
    private val storeGuilds: StoreGuilds = StoreStream.getGuilds(),
    private val storeCollectibles: StoreCollectibles = StoreStream.getCollectibles(),
    private val api: RestAPIInterface = RestAPI.api,
    private val bioParser: Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> =
        DiscordParser.createParser(false, false),
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId),
    initialDraft: Draft? = null,
) : AppViewModel<SettingsUserProfileViewModel.ViewState>() {

    data class Draft(
        val avatar: NullSerializable<String>?,
        val banner: NullSerializable<String>?,
        val bannerColor: NullSerializable<String>?,
        val nickname: String?,
        val displayName: String?,
        val pronouns: String?,
        val bio: String?,
        val isEditingBio: Boolean,
        val avatarDecoration: NullSerializable<AvatarDecorationData>? = null,
        val nameplate: NullSerializable<Nameplate>? = null,
        val themeColors: ArrayList<Int>? = null,
        val themeColorsCleared: Boolean = false,
        val profileEffect: Long? = null,
        val profileEffectCleared: Boolean = false,
        val profileFrame: Long? = null,
        val profileFrameCleared: Boolean = false,
    ) : Serializable

    data class StoreState(
        val user: MeUser,
        val guild: Guild?,
        val userProfile: UserProfile,
        val meMember: GuildMember?,
        val guildChannelsInfo: GuildChannelsInfo,
        val collectibles: StoreCollectibles.State,
        val effectProducts: Map<Long, UserProfile.ProfileEffectProduct>,
    )

    sealed class ViewState {
        data class Loaded(
            val user: MeUser,
            val guild: Guild?,
            val meMember: GuildMember?,
            val userProfile: UserProfile,
            val currentAvatar: NullSerializable<String>? = null,
            val currentBannerImage: NullSerializable<String>? = null,
            val currentBannerColorHex: NullSerializable<String>? = null,
            val currentNickname: String? = null,
            val currentDisplayName: String? = null,
            val currentPronouns: String? = null,
            val currentBio: String?,
            val bioAst: List<Node<MessageRenderContext>>?,
            val isEditingBio: Boolean,
            val canEditNickname: Boolean,
            val collectibles: StoreCollectibles.State = StoreCollectibles.State(),
            val effectProducts: Map<Long, UserProfile.ProfileEffectProduct> = emptyMap(),
            val currentAvatarDecoration: NullSerializable<AvatarDecorationData>? = null,
            val currentNameplate: NullSerializable<Nameplate>? = null,
            val currentThemeColors: NullSerializable<List<Int>>? = null,
            val currentProfileEffect: NullSerializable<Long>? = null,
            val currentProfileFrame: NullSerializable<Long>? = null,
        ) : ViewState() {
            /** The metadata this screen edits: the guild profile in a guild, the global one otherwise. */
            val savedProfile = if (meMember != null) userProfile.guildMemberProfile else userProfile.userProfile
            val isDirty = currentAvatar != null || currentBannerImage != null ||
                currentBannerColorHex != null || isBioChanged || isPronounsChanged ||
                currentAvatarDecoration != null || currentNameplate != null ||
                currentThemeColors != null || currentProfileEffect != null || currentProfileFrame != null ||
                (meMember != null && currentNickname.orEmpty() != meMember.nick.orEmpty()) ||
                (meMember == null && isDisplayNameChanged)

            /**
             * What is equipped in this screen's scope. Read from the domain models rather than
             * [userProfile], which is empty until the profile fetch lands.
             */
            val savedAvatarDecoration: AvatarDecorationData? = if (meMember != null) {
                meMember.avatarDecoration
            } else {
                user.avatarDecoration ?: userProfile.user.avatarDecorationData?.value
            }
            val savedNameplate: Nameplate? = if (meMember != null) {
                meMember.collectibles?.nameplate
            } else {
                user.collectibles?.nameplate
            }

            /** The item the picker should highlight, and what the header previews. */
            val previewAvatarDecoration: AvatarDecorationData? =
                currentAvatarDecoration.resolve(savedAvatarDecoration, user.avatarDecoration)
            val previewNameplate: Nameplate? =
                currentNameplate.resolve(savedNameplate, user.collectibles?.nameplate)

            val savedProfileEffect: Long? = savedProfile?.profileEffect?.skuId

            val previewProfileEffect: Long? = when (currentProfileEffect) {
                is NullSerializable.Value -> currentProfileEffect.value
                is NullSerializable.Null -> null
                null -> savedProfileEffect
            }

            val profileEffectName: String? get() = collectibleName(previewProfileEffect)

            val savedProfileFrame: Long? = savedProfile?.collectibles
                ?.firstOrNull { it.type == CollectibleProduct.TYPE_PROFILE_FRAME }?.skuId

            val previewProfileFrame: Long? = when (currentProfileFrame) {
                is NullSerializable.Value -> currentProfileFrame.value
                is NullSerializable.Null -> null
                null -> savedProfileFrame
            }

            val profileFrameName: String? get() = collectibleName(previewProfileFrame)

            /** Carries the artwork so an unsaved pick renders, not just the SKU. */
            val previewEffect: UserProfile.ProfileEffect? = previewProfileEffect?.let { skuId ->
                UserProfile.ProfileEffect(skuId, product = effectProducts[skuId])
            }

            /** Two colours, primary then accent; the pair is written and cleared together. */
            val savedThemeColors: List<Int>? = savedProfile?.themeColors?.takeIf { it.size == 2 }

            val previewThemeColors: List<Int>? = when (currentThemeColors) {
                is NullSerializable.Value -> currentThemeColors.value
                is NullSerializable.Null -> null
                null -> savedThemeColors
            }

            /**
             * The equipped item's shop name, or null while the inventory is still loading — the row
             * must not call an unnamed item "None", which is a different state entirely.
             */
            val avatarDecorationName: String? get() = collectibleName(previewAvatarDecoration?.skuId)
            val nameplateName: String? get() = collectibleName(previewNameplate?.skuId)

            private fun collectibleName(skuId: Long?) =
                collectibles.product(skuId)?.name?.takeIf(String::isNotBlank)

            /** In a guild, clearing an override falls back to the account item rather than nothing. */
            private fun <T> NullSerializable<T>?.resolve(saved: T?, global: T?): T? = when (this) {
                is NullSerializable.Value -> value
                is NullSerializable.Null -> if (meMember != null) global else null
                null -> saved ?: if (meMember != null) global else null
            }
            val showSaveFab = isDirty && !isEditingBio
            val isBioChanged: Boolean
                get() = currentBio.orEmpty() != (if (meMember != null) meMember.bio else user.bio).orEmpty()
            val isPronounsChanged: Boolean
                get() = currentPronouns.orEmpty() != savedProfile?.pronouns.orEmpty()

            val defaultPronouns = userProfile.userProfile?.pronouns.orEmpty()
            val defaultBio = user.bio.orEmpty()

            /** A per-guild bio, avatar and banner need Nitro; pronouns and the nickname do not. */
            val canEditGuildProfile = guild == null || user.isPremiumTier2

            /** Theme colours are premium on every scope, unlike the guild-only fields above. */
            val canEditThemeColors = user.premiumTier.grantsAccessToProfileCustomization()

            /** Only meaningful outside a guild scope; a guild profile has no display name. */
            val isDisplayNameChanged: Boolean
                get() = currentDisplayName.orEmpty() != user.globalName.orEmpty()
            val showBioEditor = isEditingBio || currentBio.isNullOrBlank()
            val nonDefaultColorPreviewHex = when (currentBannerColorHex) {
                is NullSerializable.Value -> currentBannerColorHex.value
                is NullSerializable.Null -> null
                null -> user.bannerColor
            }
            private val hasUserAvatarForDisplay = meMember == null && user.avatar != null
            private val hasMemberAvatarForDisplay = meMember?.hasAvatar() == true
            val hasAvatarForDisplay = when (currentAvatar) {
                is NullSerializable.Value -> true
                is NullSerializable.Null -> false
                null -> hasUserAvatarForDisplay || hasMemberAvatarForDisplay
            }
            private val hasUserBannerForDisplay = meMember == null && user.banner != null
            private val hasMemberBannerForDisplay = meMember?.hasBanner() == true
            val hasBannerImageForDisplay = when (currentBannerImage) {
                is NullSerializable.Value -> true
                is NullSerializable.Null -> false
                null -> hasUserBannerForDisplay || hasMemberBannerForDisplay
            }
            val canHaveAnimatedAvatars = user.premiumTier.grantsAccessToCustomTagAndAnimatedAvatarFeatures()
        }
    }

    sealed class Event {
        object UserUpdateRequestCompleted : Event()
    }

    private val eventSubject = PublishSubject.create<Event>()
    private val revealedBioIndices = linkedSetOf<Int>()
    private var restoredDraft = initialDraft

    init {
        StoreStream.getUsers().observeMe().take(1).computationLatest().ui(this)
            .appSubscribe(SettingsUserProfileViewModel::class.java) { user ->
                storeUserProfile.fetchProfileIfMissing(user.id, guildId)
            }
        // The rows name the equipped items, so the inventory is warmed with the screen rather than
        // when a picker opens; the reference does the same from its edit form.
        storeCollectibles.fetchPurchases()
        storeStateObservable.distinctUntilChanged().computationLatest().ui(this)
            .appSubscribe(SettingsUserProfileViewModel::class.java, onNext = ::handleStoreState)
    }

    fun observeEvents(): Observable<Event> = eventSubject

    @MainThread
    private fun handleStoreState(storeState: StoreState) {
        var state = nextViewState(viewState as? ViewState.Loaded, storeState, ::parseBio)
        restoredDraft?.let { draft ->
            state = restoreDraft(state, draft, ::parseBio)
            restoredDraft = null
        }
        updateViewState(state)
    }

    @MainThread fun updateAvatar(value: String?) = update { copy(currentAvatar = value.serializable()) }
    @MainThread fun updateBannerImage(value: String?) = update { copy(currentBannerImage = value.serializable()) }
    @MainThread fun updateBannerColor(value: String?) = update { copy(currentBannerColorHex = value.serializable()) }
    @MainThread fun updateNickname(value: String) = update { copy(currentNickname = value) }
    @MainThread fun updateDisplayName(value: String) = update { copy(currentDisplayName = value) }
    @MainThread fun updatePronouns(value: String) = update { copy(currentPronouns = value) }
    @MainThread fun updateIsEditingBio(value: Boolean) = update { copy(isEditingBio = value) }

    /**
     * Selecting what is already equipped is not an edit, so it collapses back to "untouched" rather
     * than arming the save button and sending a redundant field.
     */
    /** Resolves the picker's SKU against the owned inventory; null is None, or Use Default in a guild. */
    @MainThread
    fun selectAvatarDecoration(skuId: Long?) =
        updateAvatarDecoration(skuId?.let { ownedProduct(it)?.avatarDecoration() })

    @MainThread
    fun selectNameplate(skuId: Long?) = updateNameplate(skuId?.let { ownedProduct(it)?.nameplate() })

    @MainThread
    fun selectProfileEffect(skuId: Long?) = update {
        skuId?.let(storeUserProfile::fetchProfileEffectIfMissing)
        copy(
            currentProfileEffect = when {
                skuId == savedProfileEffect -> null
                skuId == null -> NullSerializable.Null()
                else -> NullSerializable.Value(skuId)
            },
        )
    }

    @MainThread
    fun selectProfileFrame(skuId: Long?) = update {
        skuId?.let(storeUserProfile::fetchProfileFrameIfMissing)
        copy(
            currentProfileFrame = when {
                skuId == savedProfileFrame -> null
                skuId == null -> NullSerializable.Null()
                else -> NullSerializable.Value(skuId)
            },
        )
    }

    /** The pair is written whole; callers pass the effective colours, including any fallbacks. */
    @MainThread
    fun updateThemeColors(colors: List<Int>) = update {
        copy(currentThemeColors = if (colors == savedThemeColors) null else NullSerializable.Value(colors))
    }

    @MainThread
    fun resetThemeColors() = update {
        copy(currentThemeColors = if (savedThemeColors == null) null else NullSerializable.Null())
    }

    private fun ownedProduct(skuId: Long) = storeCollectibles.getState().product(skuId)

    @MainThread
    private fun updateAvatarDecoration(value: AvatarDecorationData?) = update {
        copy(currentAvatarDecoration = value.pendingOrNull(savedAvatarDecoration?.skuId, AvatarDecorationData::skuId))
    }

    @MainThread
    private fun updateNameplate(value: Nameplate?) = update {
        copy(currentNameplate = value.pendingOrNull(savedNameplate?.skuId, Nameplate::skuId))
    }

    @MainThread
    fun updateBio(value: String) = update {
        if (value == currentBio) this else copy(currentBio = value, bioAst = parseBio(value))
    }

    @MainThread
    fun handleBioIndexClicked(spoilerNode: SpoilerNode<*>) = update {
        val bio = currentBio ?: return@update this
        revealedBioIndices += spoilerNode.id
        copy(bioAst = parseBio(bio))
    }

    fun observeHeaderViewState(): Observable<UserProfileHeaderViewModel.ViewState> = observeViewState()
        .map { state ->
            val loaded = state as ViewState.Loaded
            // Unsaved edits are previewed, not just the persisted values: a blank draft name reads
            // as "cleared" and the header falls back to the username, exactly as it will after save.
            val editedAvatar = if (loaded.meMember == null && loaded.currentAvatar != null) {
                loaded.currentAvatar.value
            } else loaded.user.avatar
            val user = loaded.user.copy(
                avatar = editedAvatar,
                globalName = loaded.currentDisplayName ?: loaded.user.globalName,
            )
            val banner = when (val current = loaded.currentBannerImage) {
                is NullSerializable.Value -> current.value
                is NullSerializable.Null -> if (loaded.meMember != null) loaded.user.banner else null
                null -> loaded.meMember?.bannerHash ?: loaded.user.banner
            }
            val member = loaded.meMember?.copy(
                nick = loaded.currentNickname ?: loaded.meMember.nick,
                avatarHash = when (val current = loaded.currentAvatar) {
                    is NullSerializable.Value -> current.value
                    is NullSerializable.Null -> null
                    null -> loaded.meMember.avatarHash
                },
                bannerHash = when (val current = loaded.currentBannerImage) {
                    is NullSerializable.Value -> current.value
                    is NullSerializable.Null -> null
                    null -> loaded.meMember.bannerHash
                },
            )
            UserProfileHeaderViewModel.ViewState.Loaded(
                user = user,
                banner = banner,
                bannerColorHex = when (val current = loaded.currentBannerColorHex) {
                    is NullSerializable.Value -> current.value
                    is NullSerializable.Null -> null
                    null -> loaded.user.bannerColor
                },
                guildMember = member,
                guildMembersForAka = emptyList(),
                presence = null,
                streamContext = null,
                // The header resolves its decoration from the profile object, so an unsaved pick
                // has to be written there rather than passed alongside.
                userProfile = loaded.userProfile.copy(
                    user = loaded.userProfile.user.copy(
                        avatarDecorationData = if (loaded.meMember == null) {
                            loaded.previewAvatarDecoration?.let { NullSerializable.Value(it) }
                        } else {
                            loaded.userProfile.user.avatarDecorationData
                        },
                    ),
                    guildMember = loaded.userProfile.guildMember?.copy(
                        avatarDecorationData = loaded.previewAvatarDecoration,
                    ),
                    userProfile = loaded.userProfile.userProfile
                        ?.copy(profileEffect = loaded.previewEffect.takeIf { loaded.meMember == null }),
                    guildMemberProfile = loaded.userProfile.guildMemberProfile
                        ?.copy(profileEffect = loaded.previewEffect),
                ),
                themeColors = loaded.previewThemeColors,
                isMeUserPremium = loaded.user.premiumTier != com.discord.api.premium.PremiumTier.NONE,
                isMeUserVerified = loaded.user.isVerified,
                allowAnimatedEmojis = false,
                showPresence = false,
                editable = true,
                reducedMotionEnabled = false,
                allowAnimationInReducedMotion = false,
                isMe = true,
            ) as UserProfileHeaderViewModel.ViewState
        }
        .distinctUntilChanged()

    fun draft(): Draft? = (viewState as? ViewState.Loaded)?.let {
        Draft(
            it.currentAvatar,
            it.currentBannerImage,
            it.currentBannerColorHex,
            it.currentNickname,
            it.currentDisplayName,
            it.currentPronouns,
            it.currentBio,
            it.isEditingBio,
            it.currentAvatarDecoration,
            it.currentNameplate,
            (it.currentThemeColors as? NullSerializable.Value)?.value?.let(::ArrayList),
            it.currentThemeColors is NullSerializable.Null,
            (it.currentProfileEffect as? NullSerializable.Value)?.value,
            it.currentProfileEffect is NullSerializable.Null,
            (it.currentProfileFrame as? NullSerializable.Value)?.value,
            it.currentProfileFrame is NullSerializable.Null,
        )
    }

    @MainThread
    fun restoreDraft(draft: Draft) {
        val state = viewState as? ViewState.Loaded
        if (state == null) {
            restoredDraft = draft
            return
        }
        updateViewState(restoreDraft(state, draft, ::parseBio))
    }

    /** Identity and profile metadata live on separate endpoints, so a save is up to two requests. */
    @MainThread
    fun saveChanges(context: Context) {
        val state = viewState as? ViewState.Loaded ?: return
        val requests = listOfNotNull(identityRequest(state), profileRequest(state))
        if (requests.isEmpty()) {
            eventSubject.onNext(Event.UserUpdateRequestCompleted)
            return
        }
        val cleared = state.copy(
            currentAvatar = null,
            currentBannerImage = null,
            currentBannerColorHex = null,
        )
        Observable.concat(Observable.from(requests))
            .toList()
            .map { edits -> edits.fold(cleared) { saved, edit -> edit(saved) } }
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                SettingsUserProfileViewModel::class.java,
                context = context,
                errorHandler = { eventSubject.onNext(Event.UserUpdateRequestCompleted) },
            ) { updated ->
                updateViewState(updated)
                eventSubject.onNext(Event.UserUpdateRequestCompleted)
            }
    }

    private fun identityRequest(state: ViewState.Loaded): Observable<SavedEdit>? {
        val id = guildId
        if (id != null) {
            val body = guildMemberPatch(state) ?: return null
            return api.updateMeGuildMember(id, body).map { member ->
                storeUserProfile.fetchProfile(state.user.id, id)
                val edit: SavedEdit = {
                    it.copy(meMember = GuildMember.from(member, id), currentAvatarDecoration = null, currentNameplate = null)
                }
                edit
            }
        }
        val body = userPatch(state) ?: return null
        return api.patchUser(body).map { user ->
            storeUserProfile.updateUser(user)
            // `updateUser` overwrites the cached profile's user outright, so a response that
            // omits a field would drop it; re-read rather than invalidate, because this screen
            // renders from that same cache and would lose its badges.
            storeUserProfile.fetchProfile(state.user.id, guildId)
            val edit: SavedEdit = {
                it.copy(user = merge(it.user, user), currentAvatarDecoration = null, currentNameplate = null)
            }
            edit
        }
    }

    private fun profileRequest(state: ViewState.Loaded): Observable<SavedEdit>? {
        val body = profilePatch(state) ?: return null
        val id = guildId
        val request = if (id != null) api.patchGuildProfile(id, body) else api.patchUserProfile(body)
        return request.map { metadata ->
            storeUserProfile.updateProfileMetadata(state.user.id, metadata)
            storeUserProfile.fetchProfile(state.user.id, id)
            val edit: SavedEdit = {
                it.withSavedProfile(metadata).copy(currentThemeColors = null, currentProfileEffect = null, currentProfileFrame = null)
            }
            edit
        }
    }

    private fun parseBio(value: String): List<Node<MessageRenderContext>> =
        ArrayList(bioParser.parse(value, MessageParseState.initialState)).also {
            MessagePreprocessor(-1L, revealedBioIndices).process(it)
        }

    private inline fun update(transform: ViewState.Loaded.() -> ViewState.Loaded) {
        (viewState as? ViewState.Loaded)?.let { updateViewState(it.transform()) }
    }

    companion object {
        internal fun nextViewState(
            current: ViewState.Loaded?,
            storeState: StoreState,
            parseBio: (String) -> List<Node<MessageRenderContext>> = { emptyList() },
        ): ViewState.Loaded {
            val bio = current?.currentBio ?: if (storeState.meMember != null) {
                storeState.meMember.bio.orEmpty()
            } else {
                storeState.user.bio
            }
            val user = storeState.userProfile.user
                .takeIf { it.id == storeState.user.id }
                ?.let { merge(storeState.user, it) }
                ?: storeState.user
            return ViewState.Loaded(
                user = user,
                guild = storeState.guild,
                meMember = storeState.meMember,
                userProfile = storeState.userProfile,
                currentAvatar = current?.currentAvatar,
                currentBannerImage = current?.currentBannerImage,
                currentBannerColorHex = current?.currentBannerColorHex,
                currentNickname = current?.currentNickname ?: storeState.meMember?.nick,
                currentDisplayName = current?.currentDisplayName ?: storeState.user.globalName,
                currentPronouns = current?.currentPronouns ?: savedPronouns(storeState),
                currentBio = bio,
                bioAst = bio?.let(parseBio),
                isEditingBio = current?.isEditingBio ?: false,
                canEditNickname = storeState.guildChannelsInfo.canChangeNickname,
                collectibles = storeState.collectibles,
                effectProducts = storeState.effectProducts,
                currentThemeColors = current?.currentThemeColors,
                currentProfileEffect = current?.currentProfileEffect,
                currentProfileFrame = current?.currentProfileFrame,
                currentAvatarDecoration = current?.currentAvatarDecoration,
                currentNameplate = current?.currentNameplate,
            )
        }

        internal fun userPatch(state: ViewState.Loaded): PatchUserBody? {
            val globalName = if (state.isDisplayNameChanged) state.currentDisplayName.serializable() else null
            val decoration = state.currentAvatarDecoration.skuIdPatch(AvatarDecorationData::skuId)
            val nameplate = state.currentNameplate.skuIdPatch(Nameplate::skuId)
            if (state.currentAvatar == null && globalName == null && decoration == null && nameplate == null) {
                return null
            }
            return PatchUserBody(state.currentAvatar, globalName, decoration, nameplate)
        }

        internal fun guildMemberPatch(state: ViewState.Loaded): PatchGuildMemberBody? {
            val nick = state.currentNickname.orEmpty()
                .takeIf { it != state.meMember?.nick.orEmpty() }
            val decoration = state.currentAvatarDecoration.skuIdPatch(AvatarDecorationData::skuId)
            // A guild nameplate is nested; `{"nameplate": null}` drops the override, which is not
            // the same as clearing every guild collectible.
            val nameplate = when (val pending = state.currentNameplate) {
                is NullSerializable.Value ->
                    PatchCollectibles(NullSerializable.Value(PatchNameplate(pending.value.skuId.toString())))
                is NullSerializable.Null -> PatchCollectibles(NullSerializable.Null())
                null -> null
            }
            if (nick == null && state.currentAvatar == null && decoration == null && nameplate == null) {
                return null
            }
            return PatchGuildMemberBody(nick, state.currentAvatar, decoration, nameplate?.let { NullSerializable.Value(it) })
        }

        private fun <T> NullSerializable<T>?.skuIdPatch(skuId: (T) -> Long): NullSerializable<String>? =
            when (this) {
                is NullSerializable.Value -> NullSerializable.Value(skuId(value).toString())
                is NullSerializable.Null -> NullSerializable.Null()
                null -> null
            }

        private fun <T> T?.pendingOrNull(savedSkuId: Long?, skuId: (T) -> Long): NullSerializable<T>? = when {
            this == null -> if (savedSkuId == null) null else NullSerializable.Null()
            skuId(this) == savedSkuId -> null
            else -> NullSerializable.Value(this)
        }

        internal fun profilePatch(state: ViewState.Loaded): PatchUserProfileBody? {
            val bio = if (state.isBioChanged) state.currentBio.orEmpty() else null
            val pronouns = if (state.isPronounsChanged) state.currentPronouns.orEmpty() else null
            val accentColor = if (state.meMember != null) null else state.currentBannerColorHex?.toAccentColor()
            val themeColors = state.currentThemeColors
            val collectibles = state.collectiblesPatch()
            if (bio == null && pronouns == null && state.currentBannerImage == null &&
                accentColor == null && themeColors == null && collectibles == null
            ) {
                return null
            }
            return PatchUserProfileBody(
                state.currentBannerImage, bio, pronouns, accentColor, themeColors, collectibles,
            )
        }

        /**
         * The whole equipped set, with the edited types swapped in or dropped. Types this screen
         * does not edit ride along untouched: the server replaces the set wholesale, so omitting
         * one would unequip it.
         */
        private fun ViewState.Loaded.collectiblesPatch(): List<String>? {
            if (currentProfileEffect == null && currentProfileFrame == null) return null
            val edited = setOfNotNull(
                CollectibleProduct.TYPE_PROFILE_EFFECT.takeIf { currentProfileEffect != null },
                CollectibleProduct.TYPE_PROFILE_FRAME.takeIf { currentProfileFrame != null },
            )
            val kept = savedProfile?.collectibles.orEmpty()
                .filter { it.type !in edited }
                .map { it.skuId }
            val picked = listOfNotNull(
                previewProfileEffect.takeIf { currentProfileEffect != null },
                previewProfileFrame.takeIf { currentProfileFrame != null },
            )
            return (kept + picked).map(Long::toString)
        }

        private fun NullSerializable<String>.toAccentColor(): NullSerializable<Int> =
            (this as? NullSerializable.Value)?.value?.removePrefix("#")?.toIntOrNull(16)
                ?.let { NullSerializable.Value(it) }
                ?: NullSerializable.Null()

        private fun ViewState.Loaded.withSavedProfile(
            metadata: UserProfile.ProfileMetadata,
        ): ViewState.Loaded = if (metadata.guildId != null) {
            copy(
                userProfile = userProfile.copy(guildMemberProfile = metadata),
                meMember = meMember?.copy(bio = metadata.bio, bannerHash = metadata.banner),
            )
        } else {
            copy(
                userProfile = userProfile.copy(userProfile = metadata),
                user = user.copy(
                    bio = metadata.bio,
                    banner = metadata.banner,
                    bannerColor = metadata.accentColor?.let { "#%06X".format(it) },
                ),
            )
        }

        internal fun restoreDraft(
            state: ViewState.Loaded,
            draft: Draft,
            parseBio: (String) -> List<Node<MessageRenderContext>> = { emptyList() },
        ) = state.copy(
            currentAvatar = draft.avatar,
            currentBannerImage = draft.banner,
            currentBannerColorHex = draft.bannerColor,
            currentNickname = draft.nickname,
            currentDisplayName = draft.displayName,
            currentPronouns = draft.pronouns,
            currentBio = draft.bio,
            bioAst = draft.bio?.let(parseBio),
            isEditingBio = draft.isEditingBio,
            currentAvatarDecoration = draft.avatarDecoration,
            currentNameplate = draft.nameplate,
            currentThemeColors = draft.themeColors?.let { NullSerializable.Value(it.toList()) }
                ?: NullSerializable.Null<List<Int>>().takeIf { draft.themeColorsCleared },
            currentProfileEffect = draft.profileEffect?.let { NullSerializable.Value(it) }
                ?: NullSerializable.Null<Long>().takeIf { draft.profileEffectCleared },
            currentProfileFrame = draft.profileFrame?.let { NullSerializable.Value(it) }
                ?: NullSerializable.Null<Long>().takeIf { draft.profileFrameCleared },
        )

        private fun observeStoreState(guildId: Long?): Observable<StoreState> =
            StoreStream.getUsers().observeMe().flatMap { user ->
                Observable.combineLatest(
                    StoreStream.getGuilds().observeGuild(guildId ?: -1L),
                    StoreStream.getUserProfiles().observeUserProfile(user.id),
                    StoreStream.getGuilds().observeComputedMember(guildId ?: -1L, user.id),
                    GuildChannelsInfo.get(guildId ?: -1L),
                    StoreStream.getCollectibles().observeState(),
                    StoreStream.getUserProfiles().observeProfileEffectProducts(),
                ) { guild, profile, member, info, collectibles, effects ->
                    StoreState(user, guild, profile, member, info, collectibles, effects)
                }
            }

        private fun savedPronouns(storeState: StoreState): String {
            val profile = storeState.userProfile
            val metadata = if (storeState.meMember != null) profile.guildMemberProfile else profile.userProfile
            return metadata?.pronouns.orEmpty()
        }

        private fun String?.serializable(): NullSerializable<String> =
            this?.let { NullSerializable.Value(it) } ?: NullSerializable.Null()

        private fun merge(old: MeUser, new: com.discord.api.user.User) = old.copy(
            username = new.username,
            globalName = new.globalName,
            avatar = if (new.avatar == null) old.avatar else new.avatar.value,
            banner = if (new.banner == null) old.banner else new.banner.value,
            discriminator = new.discriminator.toIntOrNull() ?: old.discriminator,
            premiumTier = new.premiumType ?: old.premiumTier,
            bio = if (new.bio == null) old.bio else new.bio.value,
            bannerColor = if (new.bannerColor == null) old.bannerColor else new.bannerColor.value,
        )
    }
}

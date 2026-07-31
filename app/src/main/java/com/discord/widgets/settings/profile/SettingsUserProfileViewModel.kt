package com.discord.widgets.settings.profile

import android.content.Context
import androidx.annotation.MainThread
import com.discord.api.guildmember.PatchGuildMemberBody
import com.discord.api.user.PatchUserBody
import com.discord.api.user.UserProfile
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.MeUser
import com.discord.nullserializable.NullSerializable
import com.discord.restapi.RestAPIInterface
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.parser.Parser
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserProfile
import com.discord.utilities.channel.GuildChannelsInfo
import com.discord.utilities.premium.grantsAccessToCustomTagAndAnimatedAvatarFeatures
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

class SettingsUserProfileViewModel(
    private val guildId: Long?,
    private val storeUserProfile: StoreUserProfile = StoreStream.getUserProfiles(),
    private val storeGuilds: StoreGuilds = StoreStream.getGuilds(),
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
        val bio: String?,
        val isEditingBio: Boolean,
    ) : Serializable

    data class StoreState(
        val user: MeUser,
        val guild: Guild?,
        val userProfile: UserProfile,
        val meMember: GuildMember?,
        val guildChannelsInfo: GuildChannelsInfo,
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
            val currentBio: String?,
            val bioAst: List<Node<MessageRenderContext>>?,
            val isEditingBio: Boolean,
            val canEditNickname: Boolean,
        ) : ViewState() {
            val isDirty = currentAvatar != null || currentBannerImage != null ||
                currentBannerColorHex != null ||
                currentBio.orEmpty() != (if (meMember != null) meMember.bio else user.bio).orEmpty() ||
                (meMember != null && currentNickname.orEmpty() != meMember.nick.orEmpty())
            val showSaveFab = isDirty && !isEditingBio
            val isBioChanged = currentBio != user.bio
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
                storeUserProfile.fetchProfile(user.id, guildId)
            }
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
    @MainThread fun updateIsEditingBio(value: Boolean) = update { copy(isEditingBio = value) }

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
            val user = if (loaded.meMember == null && loaded.currentAvatar != null) {
                loaded.user.copy(avatar = loaded.currentAvatar.value)
            } else loaded.user
            val banner = when (val current = loaded.currentBannerImage) {
                is NullSerializable.Value -> current.value
                is NullSerializable.Null -> if (loaded.meMember != null) loaded.user.banner else null
                null -> loaded.meMember?.bannerHash ?: loaded.user.banner
            }
            val member = loaded.meMember?.copy(
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
                userProfile = loaded.userProfile,
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
            it.currentBio,
            it.isEditingBio,
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

    @MainThread
    fun saveChanges(context: Context) {
        val state = viewState as? ViewState.Loaded ?: return
        val request = guildId?.let { id ->
            api.updateMeGuildMember(id, guildMemberPatch(state)).map<Any> { member ->
                state.copy(
                    meMember = GuildMember.from(member, id),
                    currentAvatar = null,
                    currentBannerImage = null,
                    currentBannerColorHex = null,
                )
            }
        } ?: api.patchUser(userPatch(state)).map<Any> { user ->
            storeUserProfile.updateUser(user)
            state.copy(
                user = merge(state.user, user),
                currentAvatar = null,
                currentBannerImage = null,
                currentBannerColorHex = null,
            )
        }
        request.restSubscribeOn().ui(this).appSubscribe(
            SettingsUserProfileViewModel::class.java,
            context = context,
            errorHandler = { eventSubject.onNext(Event.UserUpdateRequestCompleted) },
        ) { updated ->
            @Suppress("UNCHECKED_CAST")
            updateViewState(updated as ViewState.Loaded)
            eventSubject.onNext(Event.UserUpdateRequestCompleted)
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
                currentBio = bio,
                bioAst = bio?.let(parseBio),
                isEditingBio = current?.isEditingBio ?: false,
                canEditNickname = storeState.guildChannelsInfo.canChangeNickname,
            )
        }

        internal fun userPatch(state: ViewState.Loaded) = PatchUserBody(
            state.currentAvatar,
            state.currentBannerImage,
            state.currentBannerColorHex,
            if (state.isBioChanged) state.currentBio.serializable() else null,
        )

        internal fun guildMemberPatch(state: ViewState.Loaded) = PatchGuildMemberBody(
            state.currentNickname,
            state.currentAvatar,
            state.currentBannerImage,
            state.currentBio,
        )

        internal fun restoreDraft(
            state: ViewState.Loaded,
            draft: Draft,
            parseBio: (String) -> List<Node<MessageRenderContext>> = { emptyList() },
        ) = state.copy(
            currentAvatar = draft.avatar,
            currentBannerImage = draft.banner,
            currentBannerColorHex = draft.bannerColor,
            currentNickname = draft.nickname,
            currentBio = draft.bio,
            bioAst = draft.bio?.let(parseBio),
            isEditingBio = draft.isEditingBio,
        )

        private fun observeStoreState(guildId: Long?): Observable<StoreState> =
            StoreStream.getUsers().observeMe().flatMap { user ->
                Observable.combineLatest(
                    StoreStream.getGuilds().observeGuild(guildId ?: -1L),
                    StoreStream.getUserProfiles().observeUserProfile(user.id),
                    StoreStream.getGuilds().observeComputedMember(guildId ?: -1L, user.id),
                    GuildChannelsInfo.get(guildId ?: -1L),
                ) { guild, profile, member, info -> StoreState(user, guild, profile, member, info) }
            }

        private fun String?.serializable(): NullSerializable<String> =
            this?.let { NullSerializable.Value(it) } ?: NullSerializable.Null()

        private fun merge(old: MeUser, new: com.discord.api.user.User) = old.copy(
            username = new.username,
            avatar = if (new.avatar == null) old.avatar else new.avatar.value,
            banner = if (new.banner == null) old.banner else new.banner.value,
            discriminator = new.discriminator.toIntOrNull() ?: old.discriminator,
            premiumTier = new.premiumType ?: old.premiumTier,
            bio = if (new.bio == null) old.bio else new.bio.value,
            bannerColor = if (new.bannerColor == null) old.bannerColor else new.bannerColor.value,
        )
    }
}

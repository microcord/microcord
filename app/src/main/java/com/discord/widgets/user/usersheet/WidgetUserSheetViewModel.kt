package com.discord.widgets.user.usersheet

import android.content.Context
import android.util.Base64
import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.isDM
import com.discord.api.channel.isMultiUserDM
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.role.GuildRole
import com.discord.api.user.UserProfile
import com.discord.api.voice.state.StageRequestToSpeakState
import com.discord.api.voice.state.VoiceState
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelApplicationStream
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.MeUser
import com.discord.models.user.User
import com.discord.restapi.RestAPIParams
import com.discord.simpleast.core.node.Node
import com.discord.stores.StoreApplicationStreamPreviews
import com.discord.stores.StoreMediaSettings
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserNotes
import com.discord.stores.StoreUserProfile
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.permissions.ManageUserContext
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.streams.StreamContext
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessagePreprocessor
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.textprocessing.node.SpoilerNode
import com.discord.widgets.user.presence.ModelRichPresence
import com.discord.widgets.user.profile.UserProfileAdminView
import com.discord.widgets.user.profile.UserProfileConnectionsView
import rx.Observable
import rx.subjects.PublishSubject

/**
 * State and behaviour for the user sheet — the bottom sheet that opens on any avatar tap.
 *
 * The sheet is the app's single "who is this person, and what can I do about them" surface, so it
 * has to combine an unusually wide slice of the store graph: the user, their profile, their
 * presence, their membership and roles in the guild you tapped them from, their voice state in the
 * channel you are both in, your relationship with them, your note about them, and what your
 * permissions let you do to them. [WidgetUserSheetViewModelStoreState] does that combining;
 * everything here turns the result into one [ViewState] and performs the actions.
 *
 * @param channelId the channel the sheet was opened from — `0` when there is none. Guild-scoped
 *   sections key off this or [guildId].
 * @param friendToken carried through into an outgoing friend request when the sheet was opened
 *   from a friend-invite link.
 * @param isVoiceContext the sheet was opened from a voice surface, which suppresses the
 *   "Message"/"Call" actions in favour of the voice controls.
 */
class WidgetUserSheetViewModel(
    private val userId: Long,
    private val channelId: Long,
    private val guildId: Long?,
    private val friendToken: String?,
    private val isVoiceContext: Boolean,
    storeStateObservable: Observable<StoreState?> = WidgetUserSheetViewModelStoreState.observeStoreState(
        userId = userId,
        channelId = channelId.takeIf { it != 0L },
        guildId = guildId,
    ),
    private val streamPreviewClickBehavior: WidgetUserSheet.StreamPreviewClickBehavior =
        WidgetUserSheet.StreamPreviewClickBehavior.TARGET_AND_LAUNCH_SPECTATE,
    private val storeMediaSettings: StoreMediaSettings? = null,
    private val storeUserNotes: StoreUserNotes = StoreStream.getUserNotes(),
    private val storeUserProfile: StoreUserProfile = StoreStream.getUserProfiles(),
    private val restAPI: RestAPI = RestAPI,
    private val storeApplicationStreamPreviews: StoreApplicationStreamPreviews? = null,
) : AppViewModel<WidgetUserSheetViewModel.ViewState>(ViewState.Uninitialized) {

    /**
     * The store slice the sheet is built from.
     *
     * Field-for-field the reference's `StoreState`, with two exceptions noted at the bottom: this
     * project has no `StoreStageChannels`, so the caller's and the target's stage roles are absent.
     */
    data class StoreState(
        val user: User,
        val me: MeUser,
        val channel: Channel?,
        val computedMembers: Map<Long, GuildMember>,
        val guildRoles: Map<Long, GuildRole>,
        /** Voice states in the channel *I* am connected to. */
        val mySelectedVoiceChannelVoiceStates: Map<Long, VoiceState>,
        /** Voice states in the channel the sheet was opened from. */
        val currentChannelVoiceStates: Map<Long, VoiceState>,
        /** I have locally muted this user. */
        val muted: Boolean,
        val selfMuted: Boolean,
        val selfDeafened: Boolean,
        /** My playback volume for this user, 0..200. */
        val outputVolume: Float,
        val richPresence: ModelRichPresence?,
        val guild: Guild?,
        /** My permissions in [channel]. */
        val permissions: Long?,
        val streamContext: StreamContext?,
        val userProfile: UserProfile,
        val userRelationshipType: Int?,
        val userNoteFetchState: StoreUserNotes.UserNoteState,
        /** The stage I am connected to, if my selected voice channel happens to be one. */
        val stageChannel: Channel?,
        val userRequestToSpeakState: StageRequestToSpeakState,
        /** This guild may use timeouts. Reference: `GuildCommunicationDisabledGuildsFeatureFlag`. */
        val canDisableCommunication: Boolean,
        // Absent versus the reference: `userStageRoles` and `myStageRoles`, both
        // `com.discord.widgets.stage.StageRoles`. `StoreStageChannels` and `widgets/stage` are not
        // reconstructed, so speaker/audience role state cannot be resolved.
    )

    sealed class ViewState {
        object Uninitialized : ViewState()

        data class Loaded(
            val user: User,
            val isMe: Boolean,
            val showVoiceSettings: Boolean,
            val voiceSettingsViewState: UserProfileVoiceSettingsView.ViewState,
            val channelVoiceState: VoiceState?,
            val richPresence: ModelRichPresence?,
            /** "Roles in <guild>", or `null` when there is no guild section. */
            val guildSectionHeaderText: String?,
            val roleItems: List<GuildRole>,
            val adminViewState: UserProfileAdminView.ViewState?,
            val channel: Channel?,
            val streamContext: StreamContext?,
            val guildName: String?,
            val guildIcon: String?,
            val userRelationshipType: Int,
            val connectionsViewState: UserProfileConnectionsView.ViewState,
            val userNoteFetchState: StoreUserNotes.UserNoteState,
            val userNote: String,
            val userInSameVoiceChannel: Boolean,
            /** Rendered "About Me" markdown; `null` when the user has no bio. */
            val bioAst: List<Node<MessageRenderContext>>?,
            val profileLoaded: Boolean,
            val guildId: Long?,
            val guildMember: GuildMember?,
            val userProfile: UserProfile,
            // Absent versus the reference: `stageViewState`
            // (`com.discord.widgets.stage.usersheet.UserProfileStageActionsView.ViewState`), which
            // drives invite-to-speak / move-to-audience. `widgets/stage` is not reconstructed.
        ) : ViewState() {

            /** The user has a per-guild banner or an animated avatar — Nitro-only decorations. */
            val hasPremiumCustomization: Boolean =
                user.banner != null || user.avatar?.let(IconUtils::isImageHashAnimated) == true

            /** The guild the sheet is scoped to: the channel's, falling back to the explicit one. */
            val currentGuildId: Long? = channel?.guildId?.takeIf { it != 0L } ?: guildId

            /** The bio shown is the guild-specific one rather than the account-wide one. */
            val hasGuildMemberBio: Boolean = !guildMember?.bio.isNullOrEmpty()

            val guildIconURL: String? = guildIcon?.let {
                IconUtils.getForGuild(guildId, it, size = GUILD_SECTION_ICON_SIZE_DP)
            }

            val presence get() = richPresence?.presence

            /** Roles only make sense inside a guild, and only when the member actually has some. */
            fun shouldShowRoles(): Boolean =
                roleItems.isNotEmpty() && currentGuildId != null && currentGuildId > 0L
        }
    }

    /** One-shot outcomes the sheet cannot express as state. */
    sealed class Event {
        data class ShowToast(@StringRes val stringRes: Int) : Event()

        /** A friend request the server refused; [abortCode] selects the explanatory copy. */
        data class ShowFriendRequestErrorToast(val abortCode: Int, val username: String) : Event()

        data class LaunchVoiceCall(val channelId: Long) : Event()
        data class LaunchVideoCall(val channelId: Long) : Event()
        data class LaunchSpectate(val stream: ModelApplicationStream) : Event()
        data class RequestPermissionsForSpectateStream(val stream: ModelApplicationStream) : Event()
        data class LaunchEditMember(val guildId: Long, val userId: Long) : Event()
        data class LaunchKickUser(val username: String, val guildId: Long, val userId: Long) : Event()
        data class LaunchBanUser(val username: String, val guildId: Long, val userId: Long) : Event()
        data class LaunchDisableCommunication(val guildId: Long, val userId: Long) : Event()
        data class LaunchEnableCommunication(val guildId: Long, val userId: Long) : Event()
        data class LaunchMoveUser(val guildId: Long) : Event()

        /** The user id the sheet was opened with resolves to nobody. */
        object UserNotFound : Event()
        object DismissSheet : Event()
    }

    private val eventSubject = PublishSubject.create<Event>()

    /** Spoilers in the bio the reader has tapped open; survives store-driven re-renders. */
    private val revealedBioIndices = mutableSetOf<Int>()

    private var storeState: StoreState? = null

    /** Guards the once-per-sheet "user sheet opened" analytics event. */
    var openPopoutLogged: Boolean = false

    init {
        storeStateObservable
            .ui(this)
            .appSubscribe(WidgetUserSheetViewModel::class.java) { handleStoreState(it) }
    }

    fun observeEvents(): Observable<Event> = eventSubject

    // ---------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------

    /**
     * Reference `handleStoreState` is undecompilable in both JADX (782 instructions, "Method dump
     * skipped") and Vineflower (whole class fails), so this is reconstructed from the `ViewState`
     * field semantics and from the `createAdminViewState`/`createConnectionsViewState` helpers,
     * which *are* readable. It is the least corroborated part of this file.
     */
    private fun handleStoreState(storeState: StoreState?) {
        if (storeState == null) {
            // The user id resolves to nobody — the store emits null after a 5 s grace period.
            eventSubject.onNext(Event.UserNotFound)
            return
        }
        this.storeState = storeState

        val user = storeState.user
        val isMe = user.id == storeState.me.id
        val guildMember = storeState.computedMembers[user.id]
        val myGuildMember = storeState.computedMembers[storeState.me.id]

        val channelVoiceState = storeState.currentChannelVoiceStates[user.id]
        val myVoiceState = storeState.mySelectedVoiceChannelVoiceStates[storeState.me.id]
        val theirVoiceStateInMyChannel = storeState.mySelectedVoiceChannelVoiceStates[user.id]
        val userInSameVoiceChannel = isInSameVoiceChannel(myVoiceState, theirVoiceStateInMyChannel)

        val manageUserContext = storeState.guild?.let { guild ->
            ManageUserContext.from(
                guild = guild,
                actingUser = storeState.me,
                targetUser = user,
                actingUserRoles = myGuildMember?.getRoles().orEmpty(),
                userRoles = guildMember?.getRoles().orEmpty(),
                myPermissions = storeState.permissions,
                guildRoles = storeState.guildRoles,
            )
        }

        val roleItems = guildMember?.getRoles().orEmpty()
            .mapNotNull { roleId -> storeState.guildRoles[roleId] }
            .sortedByDescending { it.position }

        val bio = guildMember?.bio?.takeUnless { it.isBlank() } ?: user.bio

        val note = storeState.userNoteFetchState
        val userNote = (note as? StoreUserNotes.UserNoteState.Loaded)?.note?.note.orEmpty()

        updateViewState(
            ViewState.Loaded(
                user = user,
                isMe = isMe,
                // Volume and per-user mute only mean anything while you are both connected.
                showVoiceSettings = !isMe && userInSameVoiceChannel,
                voiceSettingsViewState = UserProfileVoiceSettingsView.ViewState(
                    isMuted = storeState.muted,
                    isDeafened = storeState.selfDeafened.takeIf { isMe },
                    outputVolume = storeState.outputVolume,
                ),
                channelVoiceState = channelVoiceState,
                richPresence = storeState.richPresence,
                guildSectionHeaderText = storeState.guild?.name,
                roleItems = roleItems,
                adminViewState = createAdminViewState(
                    channel = storeState.channel,
                    isGroupOwner = storeState.channel?.ownerId == storeState.me.id,
                    isMe = isMe,
                    manageUserContext = manageUserContext,
                    voiceState = channelVoiceState,
                    canDisableCommunication = storeState.canDisableCommunication,
                    isCommunicationDisabled = guildMember?.isCommunicationDisabled() == true,
                ),
                channel = storeState.channel,
                streamContext = storeState.streamContext,
                guildName = storeState.guild?.name,
                guildIcon = storeState.guild?.icon,
                userRelationshipType =
                    storeState.userRelationshipType ?: ModelUserRelationship.TYPE_NONE,
                connectionsViewState = createConnectionsViewState(
                    userProfile = storeState.userProfile,
                    isMe = isMe,
                    isSystemUser = user.isSystemUser,
                ),
                userNoteFetchState = note,
                userNote = userNote,
                userInSameVoiceChannel = userInSameVoiceChannel,
                bioAst = createAndProcessBioAstFromText(bio),
                profileLoaded = storeState.userProfile.connectedAccounts.isNotEmpty() ||
                    note !is StoreUserNotes.UserNoteState.Loading,
                guildId = storeState.guild?.id,
                guildMember = guildMember,
                userProfile = storeState.userProfile,
            ),
        )
    }

    /**
     * Two people are "in the same call" only when both report the same channel. A null on either
     * side means at least one of them is not connected at all.
     */
    private fun isInSameVoiceChannel(mine: VoiceState?, theirs: VoiceState?): Boolean {
        val myChannel = mine?.channelId ?: return false
        val theirChannel = theirs?.channelId ?: return false
        return myChannel == theirChannel
    }

    /**
     * The moderation section.
     *
     * `null` when the sheet has no channel context at all: every action here is scoped to a guild
     * or a group DM. Note the whole section is hidden unless at least one action inside it is
     * available, so an ordinary member never sees an empty card.
     *
     * The reference's own body survives decompilation only as a chain of unnamed booleans, and
     * JADX drops the outer negation on the kick branch. The kick rule below is the reading that
     * makes the branch consistent with every other flag — **verify on device**.
     */
    private fun createAdminViewState(
        channel: Channel?,
        isGroupOwner: Boolean,
        isMe: Boolean,
        manageUserContext: ManageUserContext?,
        voiceState: VoiceState?,
        canDisableCommunication: Boolean,
        isCommunicationDisabled: Boolean,
    ): UserProfileAdminView.ViewState? {
        if (channel == null) return null
        val isMultiUserDM = channel.isMultiUserDM()

        val showEditMember = manageUserContext != null &&
            (manageUserContext.canChangeNickname || manageUserContext.canManageRoles)
        val showKick = if (isMultiUserDM) {
            !channel.isDM() && isGroupOwner && !isMe
        } else {
            manageUserContext?.canKick == true
        }
        val showDisableCommunication = canDisableCommunication &&
            manageUserContext?.canDisableCommunication == true && !isMe
        val showBan = manageUserContext?.canBan == true
        // Server-muting a stage speaker is done through the stage's own controls instead.
        val showServerMute = voiceState != null &&
            manageUserContext?.canMute == true &&
            !channel.isStageVoiceChannel()
        val showServerDeafen = voiceState != null && manageUserContext?.canDeafen == true
        val showServerMoveAndDisconnect = voiceState != null && manageUserContext?.canMove == true

        val anyAction = showEditMember || showKick || showBan || showDisableCommunication ||
            showServerMute || showServerDeafen || showServerMoveAndDisconnect

        return UserProfileAdminView.ViewState(
            showEditMemberButton = showEditMember,
            showKickButton = showKick,
            showDisableCommunicationButton = showDisableCommunication,
            isMultiUserDM = isMultiUserDM,
            showBanButton = showBan,
            showServerMuteButton = showServerMute,
            isServerMuted = voiceState?.mute == true,
            showServerDeafenButton = showServerDeafen,
            isServerDeafened = voiceState?.deaf == true,
            showServerMoveAndDisconnectButtons = showServerMoveAndDisconnect,
            isAdminSectionEnabled = anyAction,
            isMe = isMe,
            isCommunicationDisabled = isCommunicationDisabled,
        )
    }

    /**
     * Linked accounts, plus the mutual-guilds/friends rows.
     *
     * The mutual rows are meaningless for yourself and unavailable for system accounts, which is
     * the only thing that can hide them.
     */
    private fun createConnectionsViewState(
        userProfile: UserProfile,
        isMe: Boolean,
        isSystemUser: Boolean,
    ): UserProfileConnectionsView.ViewState {
        val items = userProfile.connectedAccounts
            .map(UserProfileConnectionsView::ConnectedAccountItem)
        val showMutuals = !isMe && !isSystemUser
        return UserProfileConnectionsView.ViewState(
            showConnectionsSection = showMutuals || items.isNotEmpty(),
            showMutualGuildsAndFriends = showMutuals,
            connectedAccountItems = items,
        )
    }

    private fun createAndProcessBioAstFromText(text: String?): List<Node<MessageRenderContext>>? {
        if (text.isNullOrBlank()) return null
        val ast = DiscordParser.parse(text)
        MessagePreprocessor(
            myUserId = -1L,
            visibleSpoilerNodeIndices = revealedBioIndices,
            shouldJumboify = false,
        ).process(ast)
        return ast
    }

    /** Reveals the tapped spoiler and re-renders the bio around it. */
    fun handleBioIndexClicked(node: SpoilerNode<*>) {
        revealedBioIndices.add(node.id)
        storeState?.let(::handleStoreState)
    }

    // ---------------------------------------------------------------------------------------
    // Relationship actions
    // ---------------------------------------------------------------------------------------

    /**
     * Sends, accepts or ignores a friend request.
     *
     * @param type `null` accepts an incoming request; otherwise a [ModelUserRelationship] type.
     * @param successToast shown once the server confirms.
     */
    @JvmOverloads
    fun addRelationship(
        type: Int?,
        username: String,
        @StringRes successToast: Int,
        captchaKey: String? = null,
    ) {
        restAPI.api.addRelationship(
            userId,
            RestAPIParams.UserRelationship(type, friendToken, captchaKey),
            RELATIONSHIP_CONTEXT,
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetUserSheetViewModel::class.java,
                errorHandler = { error ->
                    eventSubject.onNext(Event.ShowFriendRequestErrorToast(error.code, username))
                },
            ) { eventSubject.onNext(Event.ShowToast(successToast)) }
    }

    fun removeRelationship(@StringRes successToast: Int) {
        restAPI.api.removeRelationship(userId, RELATIONSHIP_CONTEXT)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetUserSheetViewModel::class.java) {
                eventSubject.onNext(Event.ShowToast(successToast))
            }
    }

    // ---------------------------------------------------------------------------------------
    // Calls and streams
    // ---------------------------------------------------------------------------------------

    fun launchVoiceCall() = withPrivateChannel { eventSubject.onNext(Event.LaunchVoiceCall(it)) }

    fun launchVideoCall() = withPrivateChannel { eventSubject.onNext(Event.LaunchVideoCall(it)) }

    /**
     * Resolves the DM with this user, opening one if it does not exist yet, then runs [onChannel].
     * The sheet may have been opened from a guild, where no DM channel is in hand.
     */
    private fun withPrivateChannel(onChannel: (Long) -> Unit) {
        val existing = storeState?.channel?.takeIf { it.isDM() }
        if (existing != null) {
            onChannel(existing.id)
            return
        }
        restAPI.api.userCreateChannel(RestAPIParams.CreateChannel(userId))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetUserSheetViewModel::class.java) { channel -> onChannel(channel.id) }
    }

    /**
     * Watch this user's stream, or ask for the permissions that watching needs first.
     *
     * When the sheet was opened from a voice surface the preview is a navigation target rather than
     * a spectate action, which is what [WidgetUserSheet.StreamPreviewClickBehavior] selects.
     */
    fun onStreamPreviewClicked(streamContext: StreamContext) {
        val stream = streamContext.stream as? ModelApplicationStream ?: return
        when (streamPreviewClickBehavior) {
            WidgetUserSheet.StreamPreviewClickBehavior.TARGET_AND_DISMISS ->
                eventSubject.onNext(Event.DismissSheet)
            WidgetUserSheet.StreamPreviewClickBehavior.TARGET_AND_LAUNCH_SPECTATE ->
                eventSubject.onNext(Event.RequestPermissionsForSpectateStream(stream))
        }
    }

    fun onSpectatePermissionsGranted(stream: ModelApplicationStream) {
        eventSubject.onNext(Event.LaunchSpectate(stream))
    }

    /**
     * A button the *game* declared on its rich presence. The action is dispatched to the specific
     * session running the game, not to the user.
     */
    fun onActivityCustomButtonClicked(
        context: Context,
        userId: Long,
        sessionId: String,
        applicationId: Long,
        buttonIndex: Int,
    ) {
        restAPI.api.getActivityMetadata(userId, sessionId, applicationId)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetUserSheetViewModel::class.java,
                context = context,
            ) { metadata ->
                val url = metadata.buttonUrls?.getOrNull(buttonIndex) ?: return@appSubscribe
                com.discord.utilities.uri.UriHandler.handle(context, url)
            }
    }

    // ---------------------------------------------------------------------------------------
    // Moderation
    // ---------------------------------------------------------------------------------------

    fun editMember() = withGuild { eventSubject.onNext(Event.LaunchEditMember(it, userId)) }

    fun kickUser() = withGuild {
        eventSubject.onNext(Event.LaunchKickUser(username(), it, userId))
    }

    fun banUser() = withGuild {
        eventSubject.onNext(Event.LaunchBanUser(username(), it, userId))
    }

    fun disableCommunication() = withGuild {
        eventSubject.onNext(Event.LaunchDisableCommunication(it, userId))
    }

    fun enableCommunication() = withGuild {
        eventSubject.onNext(Event.LaunchEnableCommunication(it, userId))
    }

    fun guildMoveForUser() = withGuild { eventSubject.onNext(Event.LaunchMoveUser(it)) }

    fun moveUserToChannel(channelId: Long) = patchGuildMember { guildId ->
        restAPI.api.changeGuildMember(
            guildId,
            userId,
            RestAPIParams.GuildMember(channelId = channelId),
        )
    }

    fun guildMuteUser() = patchGuildMember { guildId ->
        val muted = storeState?.currentChannelVoiceStates?.get(userId)?.mute == true
        restAPI.api.changeGuildMember(guildId, userId, RestAPIParams.GuildMember(mute = !muted))
    }

    fun guildDeafenUser() = patchGuildMember { guildId ->
        val deafened = storeState?.currentChannelVoiceStates?.get(userId)?.deaf == true
        restAPI.api.changeGuildMember(guildId, userId, RestAPIParams.GuildMember(deaf = !deafened))
    }

    /** Kicks the user out of the voice channel by clearing their channel assignment. */
    @JvmOverloads
    fun disconnectUser(channel: Channel? = null) {
        val guildId = (channel ?: storeState?.channel)?.guildId?.takeIf { it != 0L } ?: return
        restAPI.api.disconnectGuildMember(guildId, userId, RestAPIParams.GuildMemberDisconnect())
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetUserSheetViewModel::class.java) { }
    }

    private fun patchGuildMember(request: (Long) -> Observable<Void>) = withGuild { guildId ->
        request(guildId)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetUserSheetViewModel::class.java) { }
    }

    private inline fun withGuild(block: (Long) -> Unit) {
        val id = storeState?.guild?.id ?: guildId ?: return
        block(id)
    }

    private fun username(): String = storeState?.user?.username.orEmpty()

    // ---------------------------------------------------------------------------------------
    // Voice settings
    // ---------------------------------------------------------------------------------------

    fun toggleMute(isMuted: Boolean) {
        val settings = storeMediaSettings ?: return
        if (settings.getMutedUsers()[userId] == isMuted) return
        settings.toggleUserMuted(userId)
    }

    fun toggleDeafen(isDeafened: Boolean) {
        storeMediaSettings?.setSelfDeafen(isDeafened)
    }

    fun setUserOutputVolume(volume: Float) {
        storeMediaSettings?.setUserOutputVolume(userId, volume)
    }

    /**
     * Stage moderation. `StoreStageChannels` and the REST voice-state endpoints it drives are not
     * reconstructed, so this is recorded here to keep the sheet's API complete and does nothing.
     */
    fun setUserSuppressedInChannel(suppressed: Boolean) = Unit

    /** See [setUserSuppressedInChannel]. */
    fun inviteUserToSpeak() = Unit

    // ---------------------------------------------------------------------------------------
    // Note
    // ---------------------------------------------------------------------------------------

    fun saveUserNote(context: Context, note: String) {
        val current = (storeState?.userNoteFetchState as? StoreUserNotes.UserNoteState.Loaded)
            ?.note?.note
        if (current == note) return
        storeUserNotes.saveNote(userId, note)
    }

    /**
     * Re-requests everything the sheet shows optimistically from cache. Mutual guilds are only
     * returned when asked for, and the note is a separate endpoint entirely.
     */
    fun fetchProfile() {
        storeUserProfile.fetchProfile(userId, guildId, withMutualGuilds = true)
        storeUserNotes.loadNote(userId)
    }

    companion object {
        /** Analytics location reported for relationship changes made from this sheet. */
        const val LOCATION = "User Profile"

        /**
         * `X-Context-Properties` is a base64-encoded JSON blob, not raw JSON — sending the plain
         * text makes the server drop the analytics context silently.
         */
        private val RELATIONSHIP_CONTEXT: String = Base64.encodeToString(
            """{"location":"$LOCATION"}""".toByteArray(),
            Base64.NO_WRAP,
        )

        /** Guild icon in the "Roles in <guild>" header. */
        private const val GUILD_SECTION_ICON_SIZE_DP = 16
    }
}

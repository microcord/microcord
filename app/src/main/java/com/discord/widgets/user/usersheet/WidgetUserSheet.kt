package com.discord.widgets.user.usersheet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.channel.isDM
import com.discord.app.AppActivity
import com.discord.app.AppBottomSheet
import com.discord.dialogs.useractions.UserActionsDialog
import com.discord.app.AppLog
import com.discord.app.appViewModels
import com.discord.i18n.i18nFormat
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserNotes
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.textprocessing.AstRenderer
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.widgets.guildcommunicationdisabled.start.WidgetDisableGuildCommunication
import com.discord.widgets.guildcommunicationdisabled.start.WidgetEnableGuildCommunication
import com.discord.widgets.roles.RolesListView
import com.discord.widgets.servers.WidgetServerSettingsEditMember
import com.discord.widgets.stage.usersheet.UserProfileStageActionsView
import com.discord.widgets.tabs.WidgetTabsHost
import com.discord.widgets.user.Badge
import com.discord.widgets.user.WidgetBanUser
import com.discord.widgets.user.WidgetKickUser
import com.discord.widgets.user.WidgetUserMutualFriends
import com.discord.widgets.user.WidgetUserMutualGuilds
import com.discord.widgets.user.presence.ViewHolderUserRichPresence
import com.discord.widgets.user.profile.UserProfileAdminView
import com.discord.widgets.user.profile.UserProfileConnectionsView
import com.discord.widgets.user.profile.UserProfileHeaderView
import com.discord.widgets.user.profile.UserProfileHeaderViewModel
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * The user sheet: the bottom sheet that opens whenever an avatar or a name is tapped.
 *
 * Which sections appear depends entirely on *where* it was opened from, which is why the arguments
 * matter so much. Opened from a guild channel it shows roles, the guild nickname and moderation
 * actions; opened from a DM it shows none of those; opened from a voice channel it adds per-user
 * volume and mute. All of that selection happens in [WidgetUserSheetViewModel]; this class only
 * binds the result.
 */
class WidgetUserSheet : AppBottomSheet() {

    /** What tapping the stream preview inside the rich-presence card should do. */
    enum class StreamPreviewClickBehavior {
        /** Focus the stream and start watching it. */
        TARGET_AND_LAUNCH_SPECTATE,

        /** Focus the stream and close the sheet — used when the caller is already watching. */
        TARGET_AND_DISMISS,
    }

    private val viewModel: WidgetUserSheetViewModel by appViewModels {
        val args = argumentsOrDefault
        WidgetUserSheetViewModel(
            userId = args.getLong(ARG_USER_ID),
            channelId = args.getLong(ARG_CHANNEL_ID),
            guildId = args.getLong(ARG_GUILD_ID).takeIf { it != 0L },
            friendToken = args.getString(ARG_FRIEND_TOKEN),
            isVoiceContext = args.getBoolean(ARG_IS_VOICE_CONTEXT),
            streamPreviewClickBehavior = streamPreviewClickBehavior,
        )
    }

    private val streamPreviewClickBehavior: StreamPreviewClickBehavior
        get() = argumentsOrDefault.getSerializable(ARG_STREAM_PREVIEW_CLICK_BEHAVIOR)
            as? StreamPreviewClickBehavior
            ?: StreamPreviewClickBehavior.TARGET_AND_LAUNCH_SPECTATE

    /** Reused across re-renders so the elapsed timer and image loads survive a presence tick. */
    private var richPresenceViewHolder: ViewHolderUserRichPresence? = null

    /** Set while the note field is being populated from state, so the watcher ignores it. */
    private var settingNote = false
    private var noteDirty = false

    private val messageNavigator by lazy(LazyThreadSafetyMode.NONE) {
        UserSheetMessageNavigator(
            findExistingDm = { userId ->
                StoreStream.getChannels().getAllChannels().values.firstOrNull { channel ->
                    channel.isDM() && channel.recipients?.singleOrNull()?.id == userId
                }?.id
            },
            createDm = { userId, completed ->
                StoreStream.createPrivateChannel(listOf(userId)) { result ->
                    completed(result.map { it.id })
                }
            },
            selectDm = { channelId ->
                ChannelSelector.getInstance().findAndSet(requireContext(), channelId)
            },
            closeSheet = ::dismiss,
            selectHome = {
                StoreStream.getTabsNavigation().selectHomeTab(StoreNavigation.PanelAction.CLOSE)
            },
            ensureHomeActivity = ::ensureHomeActivity,
        )
    }

    override fun getContentViewResId() = R.layout.widget_user_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindNoteField(view)
        bindActionButtons(view)
        view.findViewById<UserProfileHeaderView>(R.id.user_sheet_profile_header_view)
            .setOnBadgeClick(Badge.onBadgeClick(parentFragmentManager, requireContext()))
        bindAdminView(view.findViewById(R.id.user_sheet_admin_view))
        bindStageActions(view)
        bindVoiceSettings(view)
        markAccessibilityHeadings(view)

        viewModel.observeViewState()
            .ui(this)
            .appSubscribe(WidgetUserSheet::class.java) { render(it) }

        viewModel.observeEvents()
            .ui(this)
            .appSubscribe(WidgetUserSheet::class.java) { handleEvent(it) }
    }

    override fun onResume() {
        super.onResume()
        // Member and presence for a user we may never have subscribed to are fetched lazily.
        StoreStream.getGuildSubscriptions()
            .subscribeUser(argumentsOrDefault.getLong(ARG_GUILD_ID), argumentsOrDefault.getLong(ARG_USER_ID))
        viewModel.fetchProfile()
    }

    override fun onPause() {
        saveNote()
        StoreStream.getGuildSubscriptions()
            .unsubscribeUser(argumentsOrDefault.getLong(ARG_GUILD_ID), argumentsOrDefault.getLong(ARG_USER_ID))
        super.onPause()
    }

    // ---------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------

    private fun render(state: WidgetUserSheetViewModel.ViewState) {
        val root = view ?: return
        val loaded = state as? WidgetUserSheetViewModel.ViewState.Loaded
        root.findViewById<View>(R.id.user_sheet_loading_container).visibility =
            if (loaded == null) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.user_sheet_content).visibility =
            if (loaded == null) View.INVISIBLE else View.VISIBLE
        if (loaded == null) return

        configureHeader(root, loaded)
        configureRichPresence(root, loaded)
        configureProfileActionButtons(root, loaded)
        configureStageActions(root, loaded)
        configureAboutMe(root, loaded)
        configureVoiceSection(root, loaded)
        configureGuildSection(root, loaded)
        configureConnectionsSection(root, loaded)
        configureNote(root, loaded)
        configureDeveloperSection(root, loaded)

        if (!viewModel.openPopoutLogged && loaded.profileLoaded) {
            val activity = loaded.richPresence?.primaryActivity
            AnalyticsTracker.openUserSheet(
                loaded.hasPremiumCustomization,
                activity?.name,
                activity?.platform,
                loaded.guildMember,
            )
            viewModel.openPopoutLogged = true
        }
    }

    private fun configureHeader(root: View, state: WidgetUserSheetViewModel.ViewState.Loaded) {
        root.findViewById<UserProfileHeaderView>(R.id.user_sheet_profile_header_view).updateViewState(
            UserProfileHeaderViewModel.ViewState.Loaded(
                state.user,
                state.guildMember?.bannerHash ?: state.user.banner,
                state.user.bannerColor,
                state.guildMember,
                listOfNotNull(state.guildMember),
                state.presence,
                state.streamContext,
                state.userProfile,
                isMeUserPremium = false,
                isMeUserVerified = false,
                allowAnimatedEmojis = true,
                showPresence = true,
                editable = state.isMe,
                reducedMotionEnabled = false,
                allowAnimationInReducedMotion = false,
                isMe = state.isMe,
            ),
        )
    }

    /**
     * The activity card. The holder is rebuilt only when the *shape* of the activity changes;
     * see [ViewHolderUserRichPresence.setRichPresence].
     */
    private fun configureRichPresence(root: View, state: WidgetUserSheetViewModel.ViewState.Loaded) {
        val container = root.findViewById<FrameLayout>(R.id.rich_presence_container)
        val activity = state.richPresence?.primaryActivity
        if (activity == null && state.streamContext == null) {
            container.visibility = View.GONE
            richPresenceViewHolder?.disposeSubscriptions()
            return
        }
        container.visibility = View.VISIBLE
        val holder = ViewHolderUserRichPresence.setRichPresence(
            parent = container,
            activity = activity,
            streamContext = state.streamContext,
            oldViewHolder = richPresenceViewHolder,
        )
        richPresenceViewHolder = holder
        holder.setOnActivityCustomButtonClicked(viewModel::onActivityCustomButtonClicked)
        holder.configureUi(
            fragmentManager = parentFragmentManager,
            streamContext = state.streamContext,
            isMe = state.isMe,
            user = state.user,
            applicationContext = requireContext().applicationContext,
            richPresence = state.richPresence,
            userInSameVoiceChannel = state.userInSameVoiceChannel,
        )
        holder.configureUiTimestamp(activity, this)
    }

    /**
     * Message / call / video / friend-request row.
     *
     * The reference's `configureProfileActionButtons` is 437 instructions and decompiles in neither
     * JADX nor Vineflower, so the visibility matrix below is derived from the buttons the layout
     * declares and from the relationship types they correspond to. **Verify on device.**
     */
    private fun configureProfileActionButtons(
        root: View,
        state: WidgetUserSheetViewModel.ViewState.Loaded,
    ) {
        val visibility = state.actionVisibility
        root.setVisible(R.id.user_sheet_profile_actions_container, visibility.showActions)
        root.setVisible(R.id.user_sheet_profile_actions_divider, visibility.showActions)
        root.setVisible(R.id.user_sheet_message_action_button, visibility.showMessage)
        root.setVisible(R.id.user_sheet_call_action_button, visibility.showCall)
        root.setVisible(R.id.user_sheet_video_action_button, visibility.showVideo)
        root.setVisible(R.id.user_sheet_add_friend_action_button, visibility.showAddFriend)
        root.setVisible(R.id.user_sheet_pending_friend_request_action_button, visibility.showPendingRequest)
        root.setVisible(R.id.user_sheet_incoming_friend_request_header, visibility.showIncomingRequest)
        root.setVisible(R.id.user_sheet_friend_request_incoming_container, visibility.showIncomingRequest)
        root.setVisible(R.id.user_sheet_more_button, visibility.showMore)
        root.setVisible(R.id.user_sheet_profile_edit_container, visibility.showEditProfile)
        // Reference also offers "Add to Server" for bots; `WidgetOAuthAuthorize` is not
        // reconstructed, so the row stays hidden rather than leading nowhere.
        root.setVisible(R.id.user_sheet_profile_add_to_server_container, false)
    }

    /**
     * About Me, rendered as markdown rather than plain text, with a guild chip when the bio is the
     * guild-specific one.
     */
    private fun configureAboutMe(root: View, state: WidgetUserSheetViewModel.ViewState.Loaded) {
        val bioAst = state.bioAst
        val hasBio = bioAst != null
        root.setVisible(R.id.about_me_header, hasBio)
        root.setVisible(R.id.about_me_card, hasBio)
        if (bioAst == null) {
            root.setVisible(R.id.about_me_guild_icon, false)
            root.setVisible(R.id.about_me_guild_icon_name, false)
            return
        }

        val bioText = root.findViewById<LinkifiedTextView>(R.id.about_me_text)
        bioText.text = AstRenderer.render(
            bioAst,
            MessageRenderContext(
                context = requireContext(),
                myId = 0L,
                isAnimationEnabled = true,
                spoilerOnClick = viewModel::handleBioIndexClicked,
            ),
        )

        // A guild-scoped bio is badged with the guild it belongs to, and says so when tapped.
        val showGuildChip = state.hasGuildMemberBio && state.guildName != null
        val guildIcon = root.findViewById<SimpleDraweeView>(R.id.about_me_guild_icon)
        val guildIconName = root.findViewById<TextView>(R.id.about_me_guild_icon_name)
        guildIcon.visibility = if (showGuildChip && state.guildIconURL != null) View.VISIBLE else View.GONE
        guildIconName.visibility = if (showGuildChip && state.guildIconURL == null) View.VISIBLE else View.GONE
        if (showGuildChip) {
            MGImages.setImage(guildIcon, state.guildIconURL)
            guildIconName.text = state.guildName?.take(1)
            val toast: (View) -> Unit = { view ->
                view.context.showBioToast(state.guildName.orEmpty())
            }
            guildIcon.setOnClickListener(toast)
            guildIconName.setOnClickListener(toast)
        }
    }

    private fun configureVoiceSection(root: View, state: WidgetUserSheetViewModel.ViewState.Loaded) {
        root.setVisible(R.id.user_sheet_profile_voice_settings_header, state.showVoiceSettings)
        root.setVisible(R.id.voice_settings_view_card, state.showVoiceSettings)
        if (!state.showVoiceSettings) return
        root.findViewById<UserProfileVoiceSettingsView>(R.id.user_sheet_profile_voice_settings_view)
            .updateView(state.voiceSettingsViewState)
    }

    /** Roles in the guild the sheet is scoped to, plus the moderation card. */
    private fun configureGuildSection(root: View, state: WidgetUserSheetViewModel.ViewState.Loaded) {
        val showRoles = state.shouldShowRoles()
        val adminState = state.adminViewState
        val showAdmin = adminState?.isAdminSectionEnabled == true
        root.setVisible(R.id.user_sheet_guild_container, showRoles || showAdmin)
        root.setVisible(R.id.user_sheet_guild_header, showRoles)
        root.setVisible(R.id.user_sheet_roles_list, showRoles)
        if (showRoles) {
            // The reference labels this section with the guild; no "Roles in {guild}" format string
            // survives in the recovered resources, so the plain "Roles" heading is the fallback.
            root.findViewById<TextView>(R.id.user_sheet_guild_header).text =
                state.guildSectionHeaderText ?: getString(R.string.roles)
            root.findViewById<RolesListView>(R.id.user_sheet_roles_list).updateView(
                state.roleItems,
                ColorCompat.getThemedColor(requireContext(), R.attr.primary_300),
                state.currentGuildId ?: 0L,
            )
        }
        root.setVisible(R.id.user_sheet_admin_card, showAdmin)
        root.findViewById<UserProfileAdminView>(R.id.user_sheet_admin_view).apply {
            visibility = if (showAdmin) View.VISIBLE else View.GONE
            adminState?.let(::updateView)
        }
    }

    private fun configureConnectionsSection(
        root: View,
        state: WidgetUserSheetViewModel.ViewState.Loaded,
    ) {
        val visible = state.connectionsViewState.showConnectionsSection
        root.setVisible(R.id.user_sheet_connections_header, visible)
        root.findViewById<UserProfileConnectionsView>(R.id.user_sheet_connections_view).apply {
            visibility = if (visible) View.VISIBLE else View.GONE
            updateViewState(
                state.connectionsViewState,
                { _, _, url -> UriHandler.handle(context, url) },
                { WidgetUserMutualGuilds.show(requireContext(), state.user.id) },
                { WidgetUserMutualFriends.show(requireContext(), state.user) },
            )
        }
    }

    private fun configureNote(root: View, state: WidgetUserSheetViewModel.ViewState.Loaded) {
        val wrap = root.findViewById<TextInputLayout>(R.id.user_sheet_note_text_field_wrap)
        val input = root.findViewById<TextInputEditText>(R.id.user_sheet_note_text_field)
        val loading = state.userNoteFetchState is StoreUserNotes.UserNoteState.Loading
        wrap.isEnabled = !loading
        wrap.hint = getString(if (loading) R.string.loading_note else R.string.note_placeholder_mobile)
        if (noteDirty || loading) return
        if (input.text?.toString() == state.userNote) return
        settingNote = true
        input.setText(state.userNote)
        settingNote = false
    }

    /** Copy-ID row, shown only with developer mode on. */
    private fun configureDeveloperSection(
        root: View,
        state: WidgetUserSheetViewModel.ViewState.Loaded,
    ) {
        val developerMode = StoreStream.getUserSettings().getIsDeveloperMode()
        root.setVisible(R.id.user_sheet_developer_header, developerMode)
        root.setVisible(R.id.user_sheet_copy_id, developerMode)
        if (!developerMode) return
        root.findViewById<View>(R.id.user_sheet_copy_id).setOnClickListener {
            requireContext().getSystemService<ClipboardManager>()
                ?.setPrimaryClip(ClipData.newPlainText("User ID", state.user.id.toString()))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Wiring
    // ---------------------------------------------------------------------------------------

    private fun bindActionButtons(view: View) {
        view.findViewById<View>(R.id.user_sheet_message_action_button).setOnClickListener {
            viewModel.launchMessage()
        }
        view.findViewById<View>(R.id.user_sheet_call_action_button)
            .setOnClickListener { viewModel.launchVoiceCall() }
        view.findViewById<View>(R.id.user_sheet_video_action_button)
            .setOnClickListener { viewModel.launchVideoCall() }
        view.findViewById<View>(R.id.user_sheet_add_friend_action_button).setOnClickListener {
            viewModel.addRelationship(null, username(), R.string.friend_request_sent)
        }
        view.findViewById<View>(R.id.user_sheet_pending_friend_request_action_button)
            .setOnClickListener { viewModel.removeRelationship(R.string.friend_request_cancelled) }
        view.findViewById<View>(R.id.user_sheet_friend_request_accept_button).setOnClickListener {
            viewModel.addRelationship(null, username(), R.string.friend_request_sent)
        }
        view.findViewById<View>(R.id.user_sheet_friend_request_ignore_button)
            .setOnClickListener { viewModel.removeRelationship(R.string.friend_request_ignored) }
        view.findViewById<View>(R.id.user_sheet_more_button).setOnClickListener {
            UserActionsDialog.show(
                parentFragmentManager,
                argumentsOrDefault.getLong(ARG_USER_ID),
                argumentsOrDefault.getLong(ARG_CHANNEL_ID),
            )
        }
    }

    private fun configureStageActions(root: View, state: WidgetUserSheetViewModel.ViewState.Loaded) {
        val actions = root.findViewById<UserProfileStageActionsView>(
            R.id.user_sheet_profile_stage_actions_view,
        )
        actions.updateView(state.stageViewState)
        root.findViewById<View>(R.id.user_sheet_profile_stage_actions_card).visibility = actions.visibility
    }

    private fun bindAdminView(view: UserProfileAdminView) {
        view.setOnEditMember(viewModel::editMember)
        view.setOnKick(viewModel::kickUser)
        view.setOnBan(viewModel::banUser)
        view.setOnDisableCommunication {
            val state = viewModel.viewState as? WidgetUserSheetViewModel.ViewState.Loaded
            if (state?.adminViewState?.isCommunicationDisabled == true) {
                viewModel.enableCommunication()
            } else {
                viewModel.disableCommunication()
            }
        }
        view.setOnServerMute(viewModel::guildMuteUser)
        view.setOnServerDeafen(viewModel::guildDeafenUser)
        view.setOnServerMove(viewModel::guildMoveForUser)
        view.setOnDisconnect { viewModel.disconnectUser() }
    }

    private fun bindVoiceSettings(view: View) {
        view.findViewById<UserProfileVoiceSettingsView>(R.id.user_sheet_profile_voice_settings_view)
            .apply {
                setOnMuteChecked(viewModel::toggleMute)
                setOnDeafenChecked(viewModel::toggleDeafen)
                setOnVolumeChange { volume, _ -> viewModel.setUserOutputVolume(volume) }
            }
    }

    private fun bindNoteField(view: View) {
        val note = view.findViewById<TextInputEditText>(R.id.user_sheet_note_text_field)
        // The reference forces a plain text input so the note never gets autocorrected.
        note.setRawInputType(EditorInfo.TYPE_CLASS_TEXT)
        note.setOnFocusChangeListener { _, focused -> if (!focused) saveNote() }
        note.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            saveNote()
            note.clearFocus()
            true
        }
        note.doOnTextChanged { _, _, _, _ -> if (!settingNote) noteDirty = true }
    }

    private fun markAccessibilityHeadings(view: View) {
        listOf(
            R.id.about_me_header,
            R.id.user_sheet_profile_voice_settings_header,
            R.id.user_sheet_guild_header,
            R.id.user_sheet_connections_header,
            R.id.user_sheet_note_header,
            R.id.user_sheet_developer_header,
            R.id.user_sheet_incoming_friend_request_header,
        ).forEach { ViewCompat.setAccessibilityHeading(view.findViewById(it), true) }
    }

    private fun saveNote() {
        if (!noteDirty) return
        val text = view?.findViewById<TextInputEditText>(R.id.user_sheet_note_text_field)
            ?.text?.toString().orEmpty()
        viewModel.saveUserNote(requireContext(), text)
        noteDirty = false
    }

    private fun username(): String =
        (viewModel.viewState as? WidgetUserSheetViewModel.ViewState.Loaded)?.user?.username.orEmpty()

    // ---------------------------------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------------------------------

    private fun handleEvent(event: WidgetUserSheetViewModel.Event) {
        when (event) {
            is WidgetUserSheetViewModel.Event.ShowToast -> toast(getString(event.stringRes))

            is WidgetUserSheetViewModel.Event.ShowFriendRequestErrorToast ->
                toast(relationshipErrorMessage(event.abortCode, event.username))

            is WidgetUserSheetViewModel.Event.LaunchMessage -> messageNavigator.open(event.userId)

            is WidgetUserSheetViewModel.Event.LaunchVoiceCall -> {
                dismiss()
                onCall?.invoke(event.channelId, false)
            }

            is WidgetUserSheetViewModel.Event.LaunchVideoCall -> {
                dismiss()
                onCall?.invoke(event.channelId, true)
            }

            is WidgetUserSheetViewModel.Event.LaunchEditMember ->
                WidgetServerSettingsEditMember.launch(event.guildId, event.userId, requireView().context)

            is WidgetUserSheetViewModel.Event.LaunchKickUser ->
                WidgetKickUser.show(parentFragmentManager, event.guildId, event.userId, event.username)

            is WidgetUserSheetViewModel.Event.LaunchBanUser ->
                WidgetBanUser.show(parentFragmentManager, event.guildId, event.userId, event.username)

            is WidgetUserSheetViewModel.Event.LaunchDisableCommunication ->
                WidgetDisableGuildCommunication.launch(event.userId, event.guildId, requireView().context)

            is WidgetUserSheetViewModel.Event.LaunchEnableCommunication ->
                WidgetEnableGuildCommunication.launch(event.userId, event.guildId, parentFragmentManager)

            is WidgetUserSheetViewModel.Event.UserNotFound -> {
                toast(getString(R.string.user_profile_failure_to_open_message))
                dismiss()
            }

            is WidgetUserSheetViewModel.Event.DismissSheet -> dismiss()

            // WidgetChannelSelector and the spectate surface are not reconstructed yet.
            else -> AppLog.i("WidgetUserSheet: no destination for $event")
        }
    }

    /**
     * Copy for a refused friend request.
     *
     * The reference maps the abort code through `RestAPIAbortMessages.ResponseResolver
     * .getRelationshipResponse`, which is not reconstructed; until it is, every code falls back to
     * the generic failure message rather than inventing per-code text.
     */
    private fun relationshipErrorMessage(abortCode: Int, username: String): String =
        getString(R.string.default_failure_to_perform_action_message)

    private fun toast(message: CharSequence) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun Context.showBioToast(guildName: String) {
        android.widget.Toast.makeText(
            this,
            i18nFormat(R.string.guild_identity_bio_toast, "guildName" to guildName),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    private fun View.setVisible(id: Int, visible: Boolean) {
        findViewById<View>(id).visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun bindStageActions(view: View) {
        view.findViewById<UserProfileStageActionsView>(R.id.user_sheet_profile_stage_actions_view)
            .apply {
                setOnInviteToSpeak {
                    val isMe = (viewModel.viewState as? WidgetUserSheetViewModel.ViewState.Loaded)?.isMe == true
                    if (isMe) {
                        requestMicrophone(
                            onFailure = { toast(getString(R.string.stage_channel_permission_microphone_denied)) },
                            onSuccess = { viewModel.setUserSuppressedInChannel(false) },
                        )
                    } else {
                        viewModel.inviteUserToSpeak()
                    }
                }
                setOnMoveToAudience { viewModel.setUserSuppressedInChannel(true) }
            }
    }

    private fun ensureHomeActivity() {
        if (parentFragmentManager.hasVisibleTabsHost()) return
        startActivity(
            Intent(requireContext(), AppActivity.Main::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    private fun FragmentManager.hasVisibleTabsHost(): Boolean = fragments.any { fragment ->
        fragment.isVisible &&
            (fragment is WidgetTabsHost || fragment.childFragmentManager.hasVisibleTabsHost())
    }

    companion object {
        private const val ARG_USER_ID = "ARG_USER_ID"
        private const val ARG_CHANNEL_ID = "ARG_CHANNEL_ID"
        private const val ARG_GUILD_ID = "ARG_GUILD_ID"
        private const val ARG_IS_VOICE_CONTEXT = "ARG_IS_VOICE_CONTEXT"
        private const val ARG_STREAM_PREVIEW_CLICK_BEHAVIOR = "ARG_STREAM_PREVIEW_CLICK_BEHAVIOR"
        private const val ARG_FRIEND_TOKEN = "ARG_FRIEND_TOKEN"

        /**
         * Navigation the sheet cannot perform itself because the destination lives outside
         * `widgets/user`. Each is a seam, not a state channel — the sheet's own state is entirely
         * inside its view model.
         */
        @Deprecated("Message navigation is owned by WidgetUserSheet")
        @JvmField var onMessage: ((Long) -> Unit)? = null

        /** `(channelId, isVideo)`; installed by whoever owns [com.discord.widgets.user.calls.PrivateCallLauncher]. */
        @JvmField var onCall: ((Long, Boolean) -> Unit)? = null

        /**
         * Opens the sheet.
         *
         * @param channelId the channel it was opened from; drives DM calls and channel-scoped
         *   permissions.
         * @param guildId the guild it was opened from; drives nickname, roles and moderation.
         */
        @JvmStatic
        @JvmOverloads
        fun show(
            fragmentManager: FragmentManager,
            userId: Long,
            channelId: Long? = null,
            guildId: Long? = null,
            isVoiceContext: Boolean = false,
            streamPreviewClickBehavior: StreamPreviewClickBehavior =
                StreamPreviewClickBehavior.TARGET_AND_LAUNCH_SPECTATE,
            friendToken: String? = null,
        ) {
            WidgetUserSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_USER_ID, userId)
                    putLong(ARG_CHANNEL_ID, channelId ?: 0L)
                    putLong(ARG_GUILD_ID, guildId ?: 0L)
                    putBoolean(ARG_IS_VOICE_CONTEXT, isVoiceContext)
                    putSerializable(ARG_STREAM_PREVIEW_CLICK_BEHAVIOR, streamPreviewClickBehavior)
                    putString(ARG_FRIEND_TOKEN, friendToken)
                }
            }.show(fragmentManager, WidgetUserSheet::class.java.name)
        }

        /** Legacy two-argument entry point kept for existing call sites. */
        @JvmStatic
        fun show(userId: Long, fragmentManager: FragmentManager) = show(fragmentManager, userId)
    }
}

internal class UserSheetMessageNavigator(
    private val findExistingDm: (Long) -> Long?,
    private val createDm: (Long, (Result<Long>) -> Unit) -> Unit,
    private val selectDm: (Long) -> Unit,
    private val closeSheet: () -> Unit,
    private val selectHome: () -> Unit,
    private val ensureHomeActivity: () -> Unit,
) {
    fun open(userId: Long) {
        findExistingDm(userId)?.let(selectDm) ?: createDm(userId) { result ->
            result.onSuccess(selectDm)
        }
        closeSheet()
        selectHome()
        ensureHomeActivity()
    }
}

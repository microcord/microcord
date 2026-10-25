package com.discord.widgets.user.usersheet

import android.content.Context
import android.util.Log
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.updateLayoutParams
import androidx.core.view.ViewCompat
import androidx.core.view.children
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.channel.isDM
import com.discord.models.domain.ModelUserRelationship
import com.discord.app.AppActivity
import com.discord.app.AppBottomSheet
import com.discord.databinding.WidgetUserSheetBinding
import com.discord.plugins.OwnedProfileContribution
import com.discord.plugins.OwnedProfileTabContribution
import com.discord.plugins.PluginUiRegistry
import com.discord.dialogs.useractions.UserActionsDialog
import com.discord.app.AppLog
import com.discord.app.appViewModels
import com.discord.i18n.i18nFormat
import com.discord.i18n.i18nPlural
import com.discord.i18n.i18nRender
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.stores.StoreNavigation
import android.view.ViewTreeObserver
import com.discord.api.collectibles.ProfileFrameItem
import com.google.android.material.R as MaterialR
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserNotes
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.feature.PluginSurfaceCustomization
import com.discord.utilities.feature.PluginSurfaceCustomization.ProfileElement
import com.discord.utilities.feature.PluginSurfaceCustomization.Surface
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.guilds.computeShortName
import com.discord.utilities.icon.IconUtils
import com.discord.views.PileView
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.textprocessing.AstRenderer
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.time.TimeUtils
import com.discord.utilities.user.UserUtils.getDisplayName
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.guildcommunicationdisabled.start.WidgetDisableGuildCommunication
import com.discord.widgets.guildcommunicationdisabled.start.WidgetEnableGuildCommunication
import com.discord.widgets.servers.WidgetServerSettingsEditMember
import com.discord.widgets.settings.profile.WidgetEditUserOrGuildMemberProfile
import com.discord.widgets.stage.usersheet.UserProfileStageActionsView
import com.discord.widgets.tabs.WidgetTabsHost
import com.discord.widgets.user.Badge
import com.discord.widgets.user.WidgetBanUser
import com.discord.widgets.user.WidgetKickUser
import com.discord.widgets.user.WidgetUserMutualFriends
import com.discord.widgets.user.WidgetUserMutualGuilds
import com.discord.widgets.user.presence.ViewHolderUserRichPresence
import com.discord.widgets.user.profile.ProfileFrameView
import com.discord.widgets.user.profile.UserProfileHeaderViewModel
import com.discord.widgets.user.profile.ProfileThemeColors
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.tabs.TabLayout
import rx.subscriptions.CompositeSubscription
import dev.mantikafasi.manticord.plugins.api.PluginProfileContext
import dev.mantikafasi.manticord.plugins.api.PluginRegistration
import dev.mantikafasi.manticord.plugins.api.pluginProfileContext
import com.discord.widgets.friends.StrangerFriendRequest

/**
 * The user sheet: the bottom sheet that opens whenever an avatar or a name is tapped.
 *
 * Which sections appear depends entirely on *where* it was opened from, which is why the arguments
 * matter so much. Opened from a guild channel it shows roles, the guild nickname and moderation
 * actions; opened from a DM it shows none of those; opened from a voice channel it adds per-user
 * volume and mute. All of that selection happens in [WidgetUserSheetViewModel]; this class only
 * binds the result.
 */
private const val PROFILE_TINT_ICON = "profile_tint_icon"

class WidgetUserSheet : AppBottomSheet() {

    private var _binding: WidgetUserSheetBinding? = null
    private val binding get() = requireNotNull(_binding)

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
    private var renderedBioAst: List<com.discord.simpleast.core.node.Node<MessageRenderContext>>? = null

    /** Set while the note field is being populated from state, so the watcher ignores it. */
    private var defaultButtonBackgroundTints = emptyMap<MaterialButton, ColorStateList?>()
    private var defaultButtonTextColors = emptyMap<MaterialButton, ColorStateList>()
    private var defaultTextColors = emptyMap<TextView, ColorStateList>()
    private var defaultNoteTheme: UserSheetNoteTheme? = null
    private var profileThemeColors: ProfileThemeColors? = null
    private var profileFrames: Map<Long, ProfileFrameItem> = emptyMap()
    private var equippedFrameSkuId: Long? = null
    private var frameViews: FrameViews? = null
    private var selectingTabFromSwipe = false
    private var settlingProfilePage = false
    private var pluginProfileRegistration: PluginRegistration? = null
    private var pluginProfileTabRegistration: PluginRegistration? = null
    private val pluginProfileViews = mutableMapOf<String, View>()
    private val pluginProfileTabViews = mutableMapOf<String, View>()
    private var loadedState: WidgetUserSheetViewModel.ViewState.Loaded? = null
    private var profileTabKeys = emptyList<String>()
    private var selectedProfileTabKey = MAIN_TAB_KEY
    private var hasBoard = false
    private val frameAnchorListener = ViewTreeObserver.OnPreDrawListener { anchorProfileFrame() }

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
                ChannelSelector.getInstance().selectChannel(0L, channelId)
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
        _binding = WidgetUserSheetBinding.bind(view)
        binding.userSheetProfileHeaderView.paintsProfileSurface = false
        profileButtons().also { buttons ->
            defaultButtonBackgroundTints = buttons.associateWith(MaterialButton::getBackgroundTintList)
            defaultButtonTextColors = buttons.associateWith(MaterialButton::getTextColors)
        }
        defaultTextColors = profileTextViews().associateWith(TextView::getTextColors)
        defaultNoteTheme = UserSheetNoteTheme.from(binding)
        parentFragmentManager.setFragmentResultListener(
            WidgetKickUser.REQUEST_KEY_KICKED,
            viewLifecycleOwner,
        ) { _, _ -> dismiss() }
        WidgetEditUserNote.bindResult(this) { note ->
            viewModel.saveUserNote(requireContext(), note)
        }
        bindActionButtons()
        binding.userSheetProfileHeaderView
            .setOnBadgeClick(Badge.onBadgeClick(parentFragmentManager, requireContext()))
        bindAdminView()
        bindStageActions()
        bindVoiceSettings()
        bindProfileTabs()
        pluginProfileRegistration = PluginUiRegistry.addProfileListener {
            view.post { loadedState?.let(::render) }
        }
        pluginProfileTabRegistration = PluginUiRegistry.addProfileTabListener {
            view.post { loadedState?.let(::render) }
        }
        markAccessibilityHeadings()
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        super.bindSubscriptions(compositeSubscription)
        viewModel.observeViewState()
            .ui(this)
            .appSubscribe(WidgetUserSheet::class.java) { render(it) }

        viewModel.observeEvents()
            .ui(this)
            .appSubscribe(WidgetUserSheet::class.java) { handleEvent(it) }

        // Frame artwork arrives after the profile it belongs to, so it is watched separately
        // rather than holding up the rest of the sheet.
        StoreStream.getUserProfiles().observeProfileFrames()
            .ui(this)
            .appSubscribe(WidgetUserSheet::class.java) { frames ->
                profileFrames = frames
                configureProfileFrame()
            }
    }

    override fun onDestroyView() {
        // The frame lives in the dialog, not in this fragment's view, so it outlives the binding.
        frameViews?.let { views ->
            (views.front.parent as? ViewGroup)?.let { decor ->
                decor.viewTreeObserver.removeOnPreDrawListener(frameAnchorListener)
                decor.removeView(views.front)
                decor.removeView(views.back)
            }
        }
        frameViews = null
        _binding?.userSheetProfileEffect?.clear()
        defaultButtonBackgroundTints = emptyMap()
        defaultButtonTextColors = emptyMap()
        defaultTextColors = emptyMap()
        defaultNoteTheme = null
        profileThemeColors = null
        pluginProfileRegistration?.unregister()
        pluginProfileRegistration = null
        pluginProfileTabRegistration?.unregister()
        pluginProfileTabRegistration = null
        pluginProfileViews.clear()
        pluginProfileTabViews.clear()
        loadedState = null
        profileTabKeys = emptyList()
        selectedProfileTabKey = MAIN_TAB_KEY
        _binding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        // Member and presence for a user we may never have subscribed to are fetched lazily.
        StoreStream.getGuildSubscriptions()
            .subscribeUser(argumentsOrDefault.getLong(ARG_GUILD_ID), argumentsOrDefault.getLong(ARG_USER_ID))
        viewModel.fetchProfile()
    }

    override fun onPause() {
        StoreStream.getGuildSubscriptions()
            .unsubscribeUser(argumentsOrDefault.getLong(ARG_GUILD_ID), argumentsOrDefault.getLong(ARG_USER_ID))
        super.onPause()
    }

    // ---------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------

    private fun render(state: WidgetUserSheetViewModel.ViewState) {
        if (_binding == null) return
        val loaded = state as? WidgetUserSheetViewModel.ViewState.Loaded
        binding.userSheetLoadingContainer.visibility =
            if (loaded == null) View.VISIBLE else View.GONE
        binding.userSheetContent.visibility =
            if (loaded == null) View.INVISIBLE else View.VISIBLE
        if (loaded == null) {
            loadedState = null
            requireNotNull(binding.userSheetProfileEffect).let {
                it.clear()
            }
            return
        }
        loadedState = loaded

        configureHeader(loaded)
        configureRichPresence(loaded)
        configureProfileActionButtons(loaded)
        configureMutuals(loaded)
        configureStageActions(loaded)
        configurePrivateProfile(loaded)
        configureAboutMe(loaded)
        configureSinceDates(loaded)
        configureVoiceSection(loaded)
        configureGuildSection(loaded)
        configureConnectionsSection(loaded)
        configureNote(loaded)
        configureWidgets(loaded)
        configurePluginProfile(loaded)
        configurePluginProfileTabs(loaded)

        if (!viewModel.openPopoutLogged && loaded.profileLoaded) {
            val activity = loaded.richPresence?.primaryActivity
            AnalyticsTracker.openUserSheet(
                loaded.hasPremiumCustomization,
                activity?.name,
                activity?.platform,
                loaded.contextualGuildMember,
                loaded.isProfilePrivate,
            )
            viewModel.openPopoutLogged = true
        }
    }

    private fun configurePluginProfile(state: WidgetUserSheetViewModel.ViewState.Loaded) {
        val context = pluginContext(state)
        val contributions = PluginUiRegistry.profiles()
        pluginProfileViews.keys.retainAll(contributions.mapTo(HashSet(), OwnedProfileContribution::key))
        val builtins = profileBuiltinViews()
        val pluginByKey = contributions.associateBy {
            PluginSurfaceCustomization.pluginKey(it.owner, it.contribution.id)
        }
        val defaultOrder = PluginSurfaceCustomization.defaultOrder(
            ProfileElement.entries.map { PluginSurfaceCustomization.builtinProfileKey(it) to it.priority } +
                contributions.map {
                    PluginSurfaceCustomization.pluginKey(it.owner, it.contribution.id) to it.contribution.priority
                },
        )
        val order = PluginSurfaceCustomization.get(Surface.PROFILE, defaultOrder)
        val desired = buildList {
            order.forEach { entry ->
                builtins[entry.key]?.let { views ->
                    if (!entry.visible) views.forEach { it.visibility = View.GONE }
                    addAll(views)
                    return@forEach
                }
                if (!entry.visible) return@forEach
                val owned = pluginByKey[entry.key] ?: return@forEach
                if (!runCatching { owned.contribution.isVisible(context) }.getOrDefault(false)) return@forEach
                pluginProfileView(owned, context)?.let(::add)
            }
        }
        val parent = binding.userSheetMainTabContent
        val customizable = (builtins.values.flatten() + pluginProfileViews.values).toSet()
        val current = parent.children.filter(customizable::contains).toList()
        if (current != desired) {
            customizable.forEach { view -> (view.parent as? ViewGroup)?.removeView(view) }
            desired.forEach(parent::addView)
        }
    }

    private fun pluginContext(state: WidgetUserSheetViewModel.ViewState.Loaded): PluginProfileContext {
        val colors = profileThemeColors
        return pluginProfileContext(
            userId = state.user.id,
            guildId = state.currentGuildId,
            isCurrentUser = state.isMe,
            isBot = state.user.isBot,
            surfaceColor = colors?.sectionOverlay
                ?: ColorCompat.getThemedColor(requireContext(), R.attr.colorBackgroundSecondary),
            borderColor = colors?.borderOverlay
                ?: ColorCompat.getThemedColor(requireContext(), R.attr.colorBackgroundModifierAccent),
            primaryTextColor = colors?.textDefault
                ?: ColorCompat.getThemedColor(requireContext(), R.attr.colorHeaderPrimary),
            secondaryTextColor = colors?.textMuted
                ?: ColorCompat.getThemedColor(requireContext(), R.attr.colorTextMuted),
            interactiveColor = colors?.button
                ?: ColorCompat.getThemedColor(requireContext(), R.attr.colorInteractiveNormal),
        )
    }

    private fun pluginProfileView(
        owned: OwnedProfileContribution,
        context: PluginProfileContext,
    ): View? {
        val contribution = owned.contribution
        val view = pluginProfileViews[owned.key]
            ?: runCatching { contribution.createView(requireContext()) }.getOrNull()
                ?.also { pluginProfileViews[owned.key] = it }
            ?: return null
        runCatching { contribution.bind(view, context) }
        if (view.contentDescription.isNullOrBlank()) view.contentDescription = contribution.title
        return view
    }

    private fun profileBuiltinViews(): Map<String, List<View>> = mapOf(
        PluginSurfaceCustomization.builtinProfileKey(ProfileElement.ABOUT_ME) to
            listOf(binding.aboutMeHeaderContainer, binding.aboutMeCard),
        PluginSurfaceCustomization.builtinProfileKey(ProfileElement.MEMBER_SINCE) to
            listOf(binding.userSheetMemberSinceHeader, binding.userSheetMemberSinceCard),
        PluginSurfaceCustomization.builtinProfileKey(ProfileElement.FRIENDS_SINCE) to
            listOf(binding.userSheetFriendsSinceHeader, binding.userSheetFriendsSinceCard),
        PluginSurfaceCustomization.builtinProfileKey(ProfileElement.VOICE_SETTINGS) to
            listOf(binding.userSheetProfileVoiceSettingsHeader, binding.voiceSettingsViewCard),
        PluginSurfaceCustomization.builtinProfileKey(ProfileElement.RICH_PRESENCE) to
            listOf(binding.richPresenceContainer),
        PluginSurfaceCustomization.builtinProfileKey(ProfileElement.GUILD) to
            listOf(binding.userSheetGuildContainer),
        PluginSurfaceCustomization.builtinProfileKey(ProfileElement.CONNECTIONS) to
            listOf(binding.userSheetConnectionsHeader, binding.userSheetConnectionsCard),
        PluginSurfaceCustomization.builtinProfileKey(ProfileElement.NOTE) to
            listOf(binding.userSheetNoteHeader, binding.userSheetNoteText),
    )

    private fun configureHeader(state: WidgetUserSheetViewModel.ViewState.Loaded) {
        val accessibility = StoreStream.getAccessibility()
        val reducedMotion = accessibility.isReducedMotionEnabled()
        val headerState = UserProfileHeaderViewModel.ViewState.Loaded(
            state.user,
            state.guildMember?.bannerHash
                ?: state.userProfile.user.banner?.value
                ?: state.user.banner,
            state.userProfile.user.bannerColor?.value ?: state.user.bannerColor,
            state.guildMember,
            emptyList(),
            state.presence,
            state.streamContext,
            state.userProfile,
            isMeUserPremium = false,
            isMeUserVerified = false,
            allowAnimatedEmojis = true,
            showPresence = true,
            editable = false,
            reducedMotionEnabled = reducedMotion,
            allowAnimationInReducedMotion = false,
            isMe = state.isMe,
            channelId = state.channel?.id,
            syncProfileThemeWithUserTheme = accessibility.isSyncProfileThemeWithUserThemeEnabled(),
        )
        val header = binding.userSheetProfileHeaderView
        header.updateViewState(headerState)
        applyProfileTheme(headerState)
        configureProfileEffect(headerState)
        equippedFrameSkuId = state.userProfile.equippedProfileFrameSkuId()
        configureProfileFrame()
    }

    private fun applyProfileTheme(state: UserProfileHeaderViewModel.ViewState.Loaded) {
        val colors = state.profileThemeColors(isDarkTheme())
        profileThemeColors = colors
        val body = colors?.bodySurface
            ?: ColorCompat.getThemedColor(requireContext(), R.attr.colorBackgroundSecondary)
        // Reference paints one continuous fill behind the whole sheet; the name block sits on it
        // rather than on a surface of its own.
        val header = colors?.headerSurface ?: body
        // Sections lift off whatever is behind them rather than using an opaque token, which
        // collapses into the background on the pure black themes.
        val card = surfaceColor()

        // The reference runs a primary-to-secondary gradient behind the whole profile, so the blocks
        // that used to carry their own fill go transparent and let it through.
        if (colors != null) {
            binding.root.background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(colors.gradientTop, colors.gradientBottom),
            )
        } else {
            binding.root.setBackgroundColor(body)
        }
        val block = if (colors != null) Color.TRANSPARENT else header
        binding.userSheetContent.setBackgroundColor(if (colors != null) Color.TRANSPARENT else body)
        binding.userSheetHeaderContainer.setBackgroundColor(block)
        binding.userSheetProfileAddToServerContainer.setBackgroundColor(block)
        binding.userSheetProfileEditContainer.setBackgroundColor(block)
        binding.userSheetProfileActionsContainer.setBackgroundColor(block)
        binding.userSheetMutuals.setBackgroundColor(block)
        binding.userSheetAdminHeader.setBackgroundColor(block)
        binding.userSheetProfileTabsContainer.setBackgroundColor(block)
        val tabText = colors?.textDefault
            ?: ColorCompat.getThemedColor(requireContext(), R.attr.colorHeaderPrimary)
        val tabMuted = colors?.textMuted
            ?: ColorCompat.getThemedColor(requireContext(), R.attr.colorTextMuted)
        val tabSelected = colors?.textDefault
            ?: ColorCompat.getThemedColor(requireContext(), R.attr.colorTextBrand)
        val border = colors?.borderOverlay
            ?: ColorCompat.getThemedColor(requireContext(), R.attr.colorBackgroundModifierAccent)
        binding.userSheetWidgetsView.setProfileColors(
            card = card,
            text = tabText,
            mutedText = tabMuted,
            border = colors?.borderOverlay
                ?: ColorCompat.getThemedColor(requireContext(), R.attr.colorBackgroundAccent),
        )
        binding.userSheetProfileTabs.setTabTextColors(tabMuted, tabSelected)
        binding.userSheetProfileTabs.setSelectedTabIndicatorColor(tabSelected)
        binding.userSheetProfileTabsRule.setBackgroundColor(border)
        profileCards().forEach { it.setCardBackgroundColor(card) }
        binding.userSheetNoteText.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = binding.aboutMeCard.radius
            setColor(card)
        }
        binding.userSheetNoteText.setHintTextColor(
            colors?.textMuted ?: ColorCompat.getThemedColor(requireContext(), R.attr.colorTextMuted),
        )
        profileTextViews().forEach { text ->
            text.setTextColor(
                if (colors != null && text !is Button) ColorStateList.valueOf(colors.textDefault)
                else defaultTextColors[text] ?: text.textColors,
            )
        }
        sectionHeaders().forEach { header ->
            header.setTextColor(
                colors?.textMuted?.let(ColorStateList::valueOf)
                    ?: defaultTextColors[header]
                    ?: header.textColors,
            )
        }
        val iconColor = colors?.textDefault
            ?: ColorCompat.getThemedColor(requireContext(), R.attr.colorInteractiveNormal)
        tintTaggedIcons(iconColor)
        styleActionButtons(colors)
        profileButtons().forEach { button ->
            button.backgroundTintList = colors?.button?.let(ColorStateList::valueOf)
                ?: defaultButtonBackgroundTints[button]
            button.setTextColor(
                if (colors == null) defaultButtonTextColors.getValue(button)
                else ColorStateList.valueOf(Color.WHITE),
            )
        }
        applyUserSheetNoteTheme(
            binding,
            colors,
            requireNotNull(defaultNoteTheme),
        )
    }

    /**
     * Icons the profile theme owns, marked `profile_tint_icon` in XML. Destructive icons are left
     * alone: they are red in both themes.
     */
    private fun tintTaggedIcons(color: Int) {
        val tint = ColorStateList.valueOf(color)
        fun walk(view: View) {
            if (view.tag == PROFILE_TINT_ICON) {
                when (view) {
                    is ImageView -> ImageViewCompat.setImageTintList(view, tint)
                    is TextView -> TextViewCompat.setCompoundDrawableTintList(view, tint)
                }
            }
            if (view is ViewGroup) repeat(view.childCount) { walk(view.getChildAt(it)) }
        }
        walk(binding.userSheetContent)
    }

    private fun profileCards(): List<CardView> = listOf(
        binding.userSheetProfileStageActionsCard,
        binding.aboutMeCard,
        binding.voiceSettingsViewCard,
        binding.userSheetAdminCard,
        binding.userSheetConnectionsCard,
        binding.userSheetMemberSinceCard,
        binding.userSheetFriendsSinceCard,
    )

    private fun profileButtons(): List<MaterialButton> = listOf(binding.userSheetProfileAddToServerButton)

    private fun profileActionButtons(): List<MaterialButton> = listOf(
        binding.userSheetMessageActionButton,
        binding.userSheetCallActionButton,
        binding.userSheetAddFriendActionButton,
        binding.userSheetPendingFriendRequestActionButton,
    )

    /**
     * Discord's current mobile profile buttons (`UserProfileContactButtons`): add friend is the
     * primary button and pending the secondary one. Message and call are secondary on a plain
     * profile; on a themed one they take the profile colour, translucent so the theme shows through.
     */
    private fun styleActionButtons(colors: ProfileThemeColors?) {
        val context = requireContext()
        val brand = colors?.button ?: ContextCompat.getColor(context, R.color.brand_500)
        val secondaryFill = when {
            colors == null -> ColorCompat.getThemedColorStateListDefault(context, R.attr.colorButtonSecondary)
            colors.isDark -> ColorUtils.setAlphaComponent(Color.WHITE, 0x3D)
            else -> ColorUtils.setAlphaComponent(Color.WHITE, 0xB8)
        }
        val secondaryText = colors?.textDefault ?: ColorCompat.getThemedColor(context, R.attr.colorInteractiveActive)
        profileActionButtons().forEach { button ->
            val (fill, content) = when (button.id) {
                R.id.user_sheet_add_friend_action_button -> brand to Color.WHITE
                R.id.user_sheet_pending_friend_request_action_button -> secondaryFill to secondaryText
                else -> if (colors != null) ColorUtils.setAlphaComponent(brand, 0x99) to Color.WHITE
                else secondaryFill to secondaryText
            }
            button.cornerRadius = dpToPixels(8)
            button.backgroundTintList = ColorStateList.valueOf(fill)
            button.setTextColor(content)
            button.iconTint = ColorStateList.valueOf(content)
            button.strokeWidth = 0
        }
    }

    /** Beside a full width add friend or pending button, message and call are icons only. */
    private fun compactActionButtons(compact: Boolean) {
        listOf(
            binding.userSheetMessageActionButton to R.string.user_profile_message,
            binding.userSheetCallActionButton to R.string.user_profile_audio,
        ).forEach { (button, label) ->
            button.text = if (compact) "" else getString(label)
            button.iconPadding = if (compact) 0 else dpToPixels(6)
            button.updateLayoutParams<LinearLayout.LayoutParams> {
                width = if (compact) dpToPixels(40) else 0
                weight = if (compact) 0f else 1f
            }
        }
    }

    private fun bindProfileTabs() {
        binding.root.onHorizontalDrag = ::dragProfilePages
        binding.root.onHorizontalRelease = ::releaseProfilePages
        binding.userSheetProfileTabs.apply {
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    if (selectingTabFromSwipe) return
                    settlingProfilePage = false
                    selectedProfileTabKey = profileTabKeys.getOrNull(tab.position) ?: MAIN_TAB_KEY
                    showSelectedProfileTab()
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
    }

    private fun configureWidgets(state: WidgetUserSheetViewModel.ViewState.Loaded) {
        val widgets = state.userProfile.widgets.orEmpty().filter { widget ->
            widget.data.applicationId != null || widget.data.games.orEmpty().isNotEmpty()
        }
        binding.userSheetWidgetsView.bind(widgets, state.applications, state.applicationIdentities, state.games)
        hasBoard = widgets.isNotEmpty()
        if (!hasBoard && selectedProfileTabKey == BOARD_TAB_KEY) {
            settlingProfilePage = false
            selectedProfileTabKey = MAIN_TAB_KEY
        }
    }

    private fun configurePluginProfileTabs(state: WidgetUserSheetViewModel.ViewState.Loaded) {
        val context = pluginContext(state)
        val allContributions = PluginUiRegistry.profileTabs()
        val byKey = allContributions.associateBy {
            PluginSurfaceCustomization.pluginKey(it.owner, it.contribution.id)
        }
        val defaultOrder = PluginSurfaceCustomization.defaultOrder(
            listOf(
                PluginSurfaceCustomization.builtinProfileTabKey("MAIN") to 200,
                PluginSurfaceCustomization.builtinProfileTabKey("BOARD") to 100,
            ) + allContributions.map {
                PluginSurfaceCustomization.pluginKey(it.owner, it.contribution.id) to it.contribution.priority
            },
        )
        val customization = PluginSurfaceCustomization.get(Surface.PROFILE_TABS, defaultOrder)
            .sortedBy {
                when (it.key) {
                    PluginSurfaceCustomization.builtinProfileTabKey("MAIN") -> 0
                    PluginSurfaceCustomization.builtinProfileTabKey("BOARD") -> 1
                    else -> 2
                }
            }
        val contributions = customization.mapNotNull { entry ->
            if (!entry.visible) return@mapNotNull null
            byKey[entry.key]?.takeIf {
                runCatching { it.contribution.isVisible(context) }.getOrDefault(false)
            }
        }
        pluginProfileTabViews.keys.retainAll(contributions.mapTo(HashSet(), OwnedProfileTabContribution::key))
        val nextKeys = customization.mapNotNull { entry ->
            when (entry.key) {
                PluginSurfaceCustomization.builtinProfileTabKey("MAIN") -> MAIN_TAB_KEY
                PluginSurfaceCustomization.builtinProfileTabKey("BOARD") -> BOARD_TAB_KEY.takeIf { hasBoard && entry.visible }
                else -> entry.key.takeIf { key -> contributions.any {
                    PluginSurfaceCustomization.pluginKey(it.owner, it.contribution.id) == key
                } }
            }
        }
        if (selectedProfileTabKey !in nextKeys) selectedProfileTabKey = MAIN_TAB_KEY
        if (profileTabKeys != nextKeys) {
            profileTabKeys = nextKeys
            selectingTabFromSwipe = true
            binding.userSheetProfileTabs.removeAllTabs()
            val titles = contributions.associate {
                PluginSurfaceCustomization.pluginKey(it.owner, it.contribution.id) to it.contribution.title
            }
            profileTabKeys.forEach { key ->
                val title = when (key) {
                    MAIN_TAB_KEY -> getString(R.string.profile_tab_main)
                    BOARD_TAB_KEY -> getString(R.string.profile_tab_board)
                    else -> titles[key].orEmpty()
                }
                binding.userSheetProfileTabs.addTab(
                    binding.userSheetProfileTabs.newTab().setText(title),
                    key == selectedProfileTabKey,
                )
            }
            selectingTabFromSwipe = false
        }
        contributions.forEach { owned ->
            val view = pluginProfileTabViews[owned.key]
                ?: runCatching { owned.contribution.createView(requireContext()) }
                    .onFailure { error -> Log.e("PluginProfileTab", "Failed to create ${owned.key}", error) }
                    .getOrNull()
                    ?.also { pluginProfileTabViews[owned.key] = it }
                ?: return@forEach
            runCatching { owned.contribution.bind(view, context) }
                .onFailure { error -> Log.e("PluginProfileTab", "Failed to bind ${owned.key}", error) }
            if (view.contentDescription.isNullOrBlank()) view.contentDescription = owned.contribution.title
        }
        binding.userSheetProfileTabsContainer.visibility = if (profileTabKeys.size > 1) View.VISIBLE else View.GONE
        if (!settlingProfilePage) showSelectedProfileTab()
    }

    private fun showSelectedProfileTab() {
        binding.userSheetMainTabContent.animate().cancel()
        binding.userSheetWidgetsView.animate().cancel()
        binding.userSheetPluginTabContent.animate().cancel()
        binding.userSheetMainTabContent.translationX = 0f
        binding.userSheetWidgetsView.translationX = 0f
        binding.userSheetPluginTabContent.translationX = 0f
        val pluginKey = selectedProfileTabKey.takeIf { it != MAIN_TAB_KEY && it != BOARD_TAB_KEY }
        binding.userSheetMainTabContent.visibility = if (selectedProfileTabKey == MAIN_TAB_KEY) View.VISIBLE else View.GONE
        binding.userSheetWidgetsView.visibility = if (selectedProfileTabKey == BOARD_TAB_KEY) View.VISIBLE else View.GONE
        val pluginView = pluginKey?.let { key ->
            PluginUiRegistry.profileTabs().firstOrNull {
                PluginSurfaceCustomization.pluginKey(it.owner, it.contribution.id) == key
            }?.let { pluginProfileTabViews[it.key] }
        }
        binding.userSheetPluginTabContent.let { container ->
            if (container.children.toList() != listOfNotNull(pluginView)) {
                container.removeAllViews()
                pluginView?.let(container::addView)
            }
        }
        binding.userSheetPluginTabContent.visibility = if (pluginView == null) View.GONE else View.VISIBLE
    }

    private fun dragProfilePages(distance: Float) {
        if (binding.userSheetProfileTabsContainer.visibility != View.VISIBLE) return
        val currentIndex = profileTabKeys.indexOf(selectedProfileTabKey)
        val targetIndex = currentIndex + if (distance < 0f) 1 else -1
        val targetKey = profileTabKeys.getOrNull(targetIndex) ?: return
        val width = binding.userSheetProfilePages.width.toFloat().takeIf { it > 0f } ?: return
        val allowed = if (targetIndex > currentIndex) distance.coerceAtMost(0f) else distance.coerceAtLeast(0f)
        val currentView = profilePageView(selectedProfileTabKey) ?: return
        val targetView = profilePageView(targetKey) ?: return
        currentView.visibility = View.VISIBLE
        targetView.visibility = View.VISIBLE
        currentView.translationX = allowed
        targetView.translationX = if (targetIndex > currentIndex) width + allowed else -width + allowed
        updateTabIndicator(currentIndex, targetIndex, (kotlin.math.abs(allowed) / width).coerceIn(0f, 1f))
    }

    private fun releaseProfilePages(distance: Float, commit: Boolean) {
        if (binding.userSheetProfileTabsContainer.visibility != View.VISIBLE) return
        val currentIndex = profileTabKeys.indexOf(selectedProfileTabKey)
        val targetIndex = currentIndex + if (distance < 0f) 1 else -1
        val targetKey = profileTabKeys.getOrNull(targetIndex) ?: return
        val width = binding.userSheetProfilePages.width.toFloat().takeIf { it > 0f } ?: return
        val currentView = profilePageView(selectedProfileTabKey) ?: return
        val targetView = profilePageView(targetKey) ?: return
        val targetSelected = commit && kotlin.math.abs(distance) >= width * 0.2f
        val selectedIndex = if (targetSelected) targetIndex else currentIndex
        val selectedKey = if (targetSelected) targetKey else selectedProfileTabKey
        val direction = if (targetIndex > currentIndex) 1 else -1
        settlingProfilePage = true
        currentView.animate()
            .translationX(if (targetSelected) -direction * width else 0f)
            .setDuration(PAGE_SETTLE_MS)
            .start()
        targetView.animate()
            .translationX(if (targetSelected) 0f else direction * width)
            .setDuration(PAGE_SETTLE_MS)
            .withEndAction {
            settlingProfilePage = false
            if (_binding == null) return@withEndAction
            selectedProfileTabKey = selectedKey
            selectingTabFromSwipe = true
            binding.userSheetProfileTabs.getTabAt(selectedIndex)?.select()
            selectingTabFromSwipe = false
            showSelectedProfileTab()
        }.start()
    }

    private fun profilePageView(key: String): View? = when (key) {
        MAIN_TAB_KEY -> binding.userSheetMainTabContent
        BOARD_TAB_KEY -> binding.userSheetWidgetsView
        else -> {
            val pluginView = PluginUiRegistry.profileTabs().firstOrNull {
                PluginSurfaceCustomization.pluginKey(it.owner, it.contribution.id) == key
            }?.let { pluginProfileTabViews[it.key] } ?: return null
            val container = binding.userSheetPluginTabContent
            if (container.children.toList() != listOf(pluginView)) {
                container.removeAllViews()
                container.addView(pluginView)
            }
            container
        }
    }

    private fun updateTabIndicator(currentIndex: Int, targetIndex: Int, progress: Float) {
        val start = minOf(currentIndex, targetIndex)
        val offset = if (targetIndex > currentIndex) progress else 1f - progress
        binding.userSheetProfileTabs.setScrollPosition(start, offset, false)
    }

    private fun sectionHeaders(): List<TextView> = listOf(
        binding.aboutMeHeader,
        binding.userSheetProfileVoiceSettingsHeader,
        binding.userSheetGuildHeader,
        binding.userSheetAdminHeader,
        binding.userSheetConnectionsHeader,
        binding.userSheetMemberSinceHeader,
        binding.userSheetFriendsSinceHeader,
        binding.userSheetNoteHeader,
        binding.userSheetIncomingFriendRequestHeader,
    )

    private fun profileTextViews(): List<TextView> = buildList {
        fun collect(view: View) {
            if (view is TextView) add(view)
            if (view is ViewGroup) repeat(view.childCount) { collect(view.getChildAt(it)) }
        }
        collect(binding.userSheetContent)
    }

    private fun isDarkTheme() = StoreStream.getUserSettingsSystem().isDarkTheme()

    private fun surfaceColor() = ProfileThemeColors.surfaceOverlay(profileThemeColors)

    private fun borderColor() = profileThemeColors?.borderOverlay
        ?: if (isDarkTheme()) BORDER_OVERLAY_DARK else BORDER_OVERLAY_LIGHT

    private fun configureProfileEffect(state: UserProfileHeaderViewModel.ViewState.Loaded) {
        val effect = requireNotNull(binding.userSheetProfileEffect)
        effect.bind(state.profileEffect, state.reducedMotionEnabled && !state.allowAnimationInReducedMotion)
    }

    /**
     * Mounts the frame beside the sheet rather than inside it.
     *
     * A frame's top staple is drawn hanging above the profile's top edge, and everything above that
     * edge is outside the sheet — a scrolling container that clips. So the two halves are attached
     * either side of the sheet in the dialog itself, where the overhang has somewhere to draw, and
     * follow the sheet's edge as it is dragged.
     */
    private fun configureProfileFrame() {
        val sheet = dialog?.findViewById<View>(MaterialR.id.design_bottom_sheet) ?: return
        val decor = sheet.parent as? ViewGroup ?: return
        val frame = equippedFrameSkuId
            ?.takeIf { FeatureFlags.profileFramesEnabled() }
            ?.let(profileFrames::get)
        if (frame == null && frameViews == null) return

        val views = frameViews ?: FrameViews(
            back = ProfileFrameView(requireContext()).apply { half = ProfileFrameView.Half.BACK },
            front = ProfileFrameView(requireContext()),
        ).also { created ->
            frameViews = created
            val params = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            decor.addView(created.back, decor.indexOfChild(sheet), params)
            decor.addView(created.front, params)
            decor.viewTreeObserver.addOnPreDrawListener(frameAnchorListener)
        }
        views.back.bind(frame)
        views.front.bind(frame)
        anchorProfileFrame()
    }

    private fun anchorProfileFrame(): Boolean {
        val views = frameViews ?: return true
        val sheet = dialog?.findViewById<View>(MaterialR.id.design_bottom_sheet) ?: return true
        val decor = sheet.parent as? ViewGroup ?: return true
        val left = sheet.left
        val top = sheet.top - decor.paddingTop
        views.back.anchorTo(left, top, sheet.width)
        views.front.anchorTo(left, top, sheet.width)
        return true
    }

    private class FrameViews(val back: ProfileFrameView, val front: ProfileFrameView)

    /**
     * The activity card. The holder is rebuilt only when the *shape* of the activity changes;
     * see [ViewHolderUserRichPresence.setRichPresence].
     */
    private fun configureRichPresence(state: WidgetUserSheetViewModel.ViewState.Loaded) {
        val container = binding.richPresenceContainer
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
        holder.applyProfileTheme(profileThemeColors, surfaceColor(), borderColor())
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
        state: WidgetUserSheetViewModel.ViewState.Loaded,
    ) {
        val visibility = state.actionVisibility
        binding.userSheetProfileActionsContainer.show(visibility.showActions)
        binding.userSheetMessageActionButton.show(visibility.showMessage)
        binding.userSheetCallActionButton.show(visibility.showCall)
        binding.userSheetAddFriendActionButton.show(visibility.showAddFriend)
        binding.userSheetPendingFriendRequestActionButton.show(visibility.showPendingRequest)
        compactActionButtons(visibility.showAddFriend || visibility.showPendingRequest)
        binding.userSheetIncomingFriendRequestHeader.show(visibility.showIncomingRequest)
        binding.userSheetFriendRequestIncomingContainer.show(visibility.showIncomingRequest)
        binding.userSheetMoreButton.show(visibility.showMore)
        binding.userSheetRemoveFriendButton.show(visibility.showRemoveFriend)
        binding.userSheetProfileEditContainer.show(visibility.showEditProfile)
        binding.userSheetProfileEditButton.setOnClickListener {
            WidgetEditUserOrGuildMemberProfile.launch(requireContext())
        }
        val guildId = state.currentGuildId?.takeIf { it > 0L }
        binding.userSheetProfileIdentityButton.show(state.isMe && guildId != null)
        binding.userSheetProfileIdentityButton.setOnClickListener { view ->
            guildId ?: return@setOnClickListener
            WidgetEditUserOrGuildMemberProfile.launch(view.context, guildId)
        }
        // Reference also offers "Add to Server" for bots; `WidgetOAuthAuthorize` is not
        // reconstructed, so the row stays hidden rather than leading nowhere.
        binding.userSheetProfileAddToServerContainer.show(false)
    }

    /**
     * About Me, rendered as markdown rather than plain text, with a guild chip when the bio is the
     * guild-specific one.
     */
    /**
     * Explains a bare profile.
     *
     * Everything the privacy setting covers is already blank by the time it reaches us, so without
     * this the sheet just looks like a user who never filled anything in.
     */
    private fun configurePrivateProfile(state: WidgetUserSheetViewModel.ViewState.Loaded) {
        binding.userSheetPrivateProfileCard.show(state.isProfilePrivate)
        if (!state.isProfilePrivate) return
        binding.userSheetPrivateProfileText.text = requireContext().i18nFormat(
            R.string.user_profile_private_info_banner,
            "username" to state.user.getDisplayName(state.guildMember?.identityNick()),
        )
    }

    private fun configureSinceDates(state: WidgetUserSheetViewModel.ViewState.Loaded) {
        binding.userSheetMemberSinceHeader.show(true)
        binding.userSheetMemberSinceCard.show(true)
        binding.userSheetMemberSinceDiscord.text =
            TimeUtils.toMediumDate(TimeUtils.parseSnowflake(state.user.id))

        val guildJoined = state.contextualGuildMember?.joinedAt?.dateTimeMillis
        val guildId = state.currentGuildId?.takeIf { it > 0L }
        val showGuild = guildJoined != null && guildId != null
        binding.userSheetMemberSinceDivider.show(showGuild)
        binding.userSheetMemberSinceGuildIcon.show(showGuild)
        binding.userSheetMemberSinceGuild.show(showGuild)
        if (showGuild) {
            binding.userSheetMemberSinceGuild.text = TimeUtils.toMediumDate(guildJoined!!)
            binding.userSheetMemberSinceGuildIcon.configure(
                IconUtils.getForGuild(guildId, state.guildIcon, allowAnimation = false),
                state.guildName?.let(::computeShortName),
            )
        }

        // Only an actual friendship has a start date, and only ones made since the field
        // existed carry one.
        val friendsSince = StoreStream.getUserRelationships().getSince(state.user.id)
            ?.takeIf { state.userRelationshipType == ModelUserRelationship.TYPE_FRIEND }
        binding.userSheetFriendsSinceHeader.show(friendsSince != null)
        binding.userSheetFriendsSinceCard.show(friendsSince != null)
        friendsSince?.let {
            binding.userSheetFriendsSince.text = TimeUtils.toMediumDate(it.dateTimeMillis)
        }
    }

    private fun configureAboutMe(state: WidgetUserSheetViewModel.ViewState.Loaded) {
        val bioAst = state.bioAst
        val hasBio = bioAst != null
        binding.aboutMeHeader.show(hasBio)
        binding.aboutMeCard.show(hasBio)
        if (bioAst == null) {
            binding.aboutMeGuildIcon.show(false)
            binding.aboutMeGuildIconName.show(false)
            return
        }

        // Re-rendering an unchanged bio re-measures the whole card; the view model hands back the
        // same instance until the text or a revealed spoiler actually changes.
        if (bioAst !== renderedBioAst) {
            renderedBioAst = bioAst
            binding.aboutMeText.text = AstRenderer.render(
                bioAst,
                MessageRenderContext(
                    context = requireContext(),
                    myId = 0L,
                    isAnimationEnabled = true,
                    spoilerOnClick = viewModel::handleBioIndexClicked,
                ),
            )
        }

        // A guild-scoped bio is badged with the guild it belongs to, and says so when tapped.
        val showGuildChip = state.hasGuildMemberBio && state.guildName != null
        val guildIcon = binding.aboutMeGuildIcon
        val guildIconName = binding.aboutMeGuildIconName
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

    private fun configureVoiceSection(state: WidgetUserSheetViewModel.ViewState.Loaded) {
        binding.userSheetProfileVoiceSettingsHeader.show(state.showVoiceSettings)
        binding.voiceSettingsViewCard.show(state.showVoiceSettings)
        if (!state.showVoiceSettings) return
        binding.userSheetProfileVoiceSettingsView.updateView(state.voiceSettingsViewState)
    }

    /** Roles in the guild the sheet is scoped to, plus the moderation card. */
    private fun configureGuildSection(state: WidgetUserSheetViewModel.ViewState.Loaded) {
        val showRoles = state.shouldShowRoles()
        val adminState = state.adminViewState
        val showAdmin = adminState?.isAdminSectionEnabled == true
        binding.userSheetGuildContainer.show(showRoles || showAdmin)
        binding.userSheetGuildHeader.show(showRoles)
        binding.userSheetRolesList.show(showRoles)
        if (showRoles) {
            binding.userSheetGuildHeader.setText(R.string.roles)
            binding.userSheetRolesList.updateView(
                state.roleItems,
                state.currentGuildId ?: 0L,
                profileThemeColors,
            )
        }
        binding.userSheetAdminHeader.show(showAdmin)
        binding.userSheetAdminCard.show(showAdmin)
        binding.userSheetAdminView.apply {
            visibility = if (showAdmin) View.VISIBLE else View.GONE
            adminState?.let(::updateView)
        }
    }

    /** Mutual friends and servers under the name, as in Discord's current mobile profile. */
    private fun configureMutuals(state: WidgetUserSheetViewModel.ViewState.Loaded) {
        val profile = state.userProfile
        val friends = profile.mutualFriends.orEmpty()
        val friendCount = profile.mutualFriendsCount ?: friends.size
        val guildCount = profile.mutualGuilds.size
        val show = !state.isMe && !state.user.isSystemUser && !state.user.isWebhook && (friendCount > 0 || guildCount > 0)
        binding.userSheetMutuals.show(show)
        if (!show) return
        binding.userSheetMutualFriendsPile.show(friends.isNotEmpty())
        binding.userSheetMutualFriendsPile.setItems(
            friends.map { friend ->
                PileView.Item(
                    { size -> IconUtils.getForUser(friend.id, friend.avatar?.value, friend.discriminator.toIntOrNull(), size = size) },
                    null,
                )
            },
        )
        binding.userSheetMutualFriends.show(friendCount > 0)
        binding.userSheetMutualFriends.text =
            requireContext().i18nPlural(R.plurals.user_profile_mutual_friends_count, friendCount, "count" to friendCount)
        binding.userSheetMutualFriends.setOnClickListener {
            WidgetUserMutualFriends.show(requireContext(), state.user)
        }
        binding.userSheetMutualsSeparator.show(friendCount > 0 && guildCount > 0)
        binding.userSheetMutualGuilds.show(guildCount > 0)
        binding.userSheetMutualGuilds.text =
            requireContext().i18nPlural(R.plurals.user_profile_mutual_guilds_count, guildCount, "count" to guildCount)
        binding.userSheetMutualGuilds.setOnClickListener {
            WidgetUserMutualGuilds.show(requireContext(), state.user.id)
        }
    }

    private fun configureConnectionsSection(
        state: WidgetUserSheetViewModel.ViewState.Loaded,
    ) {
        val visible = state.connectionsViewState.showConnectionsSection
        binding.userSheetConnectionsHeader.show(visible)
        binding.userSheetConnectionsView.apply {
            visibility = if (visible) View.VISIBLE else View.GONE
            updateViewState(
                state.connectionsViewState,
                { _, _, url -> UriHandler.handle(context, url) },
                { WidgetUserMutualGuilds.show(requireContext(), state.user.id) },
                { WidgetUserMutualFriends.show(requireContext(), state.user) },
                profileThemeColors,
            )
        }
    }

    private fun configureNote(state: WidgetUserSheetViewModel.ViewState.Loaded) {
        val loading = state.userNoteFetchState is StoreUserNotes.UserNoteState.Loading
        val note = state.userNote.orEmpty()
        val row = binding.userSheetNoteText
        val visibility = if (state.user.isWebhook) View.GONE else View.VISIBLE
        binding.userSheetNoteHeader.visibility = visibility
        row.visibility = visibility
        row.text = note
        row.isEnabled = !loading
        row.setOnClickListener {
            if (!loading) WidgetEditUserNote.show(parentFragmentManager, note)
        }
    }

    /** Copy-ID row, shown only with developer mode on. */
    // ---------------------------------------------------------------------------------------
    // Wiring
    // ---------------------------------------------------------------------------------------

    private fun bindActionButtons() {
        binding.userSheetMessageActionButton.setOnClickListener {
            viewModel.launchMessage()
        }
        binding.userSheetCallActionButton.setOnClickListener { viewModel.launchVoiceCall() }
        binding.userSheetAddFriendActionButton.setOnClickListener {
            viewModel.addRelationship(null, username(), R.string.friend_request_sent)
        }
        binding.userSheetPendingFriendRequestActionButton
            .setOnClickListener { viewModel.removeRelationship(R.string.friend_request_cancelled) }
        binding.userSheetFriendRequestAcceptButton.setOnClickListener {
            viewModel.addRelationship(null, username(), R.string.friend_request_sent)
        }
        binding.userSheetFriendRequestIgnoreButton
            .setOnClickListener { viewModel.removeRelationship(R.string.friend_request_ignored) }
        binding.userSheetMoreButton.setOnClickListener { anchor ->
            val userId = argumentsOrDefault.getLong(ARG_USER_ID)
            val guildId = menuGuildId()
            val serverProfiles = !FeatureFlags.disableServerProfilesEnabled()
            UserActionsDialog.show(
                parentFragmentManager,
                userId,
                argumentsOrDefault.getLong(ARG_CHANNEL_ID),
                anchor,
                guildId = guildId,
                isShowingGuildProfile = serverProfiles && currentGuildId() != null,
                onViewMainProfile = { reopen(userId, guildId, showGuildProfile = false) },
                onViewServerProfile = if (serverProfiles) {
                    { reopen(userId, guildId, showGuildProfile = true) }
                } else null,
            )
        }
        binding.userSheetRemoveFriendButton.setOnClickListener { confirmRemoveFriend() }
    }

    private fun currentGuildId(): Long? =
        (viewModel.viewState as? WidgetUserSheetViewModel.ViewState.Loaded)
            ?.currentGuildId?.takeIf { it > 0L }

    /** Survives the switch to the main profile, so the way back is still offered. */
    private fun menuGuildId(): Long? = currentGuildId()
        ?: argumentsOrDefault.getLong(ARG_ORIGIN_GUILD_ID).takeIf { it != 0L }

    private fun reopen(userId: Long, guildId: Long?, showGuildProfile: Boolean) {
        // dismissNow, because show() de-dupes on tag and a queued dismiss still holds it.
        val manager = parentFragmentManager
        dismissNow()
        show(
            manager,
            userId,
            guildId = guildId.takeIf { showGuildProfile },
            originGuildId = guildId,
        )
    }

    private fun confirmRemoveFriend() {
        val state = viewModel.viewState as? WidgetUserSheetViewModel.ViewState.Loaded ?: return
        val name = state.user.getDisplayName(state.guildMember?.identityNick())
        WidgetNoticeDialog.Builder(requireContext())
            .setTitle(requireContext().i18nRender(R.string.remove_friend_title, "name" to name))
            .setMessage(requireContext().i18nRender(R.string.remove_friend_body, "name" to name))
            .setNegativeButton(R.string.cancel)
            .setPositiveButton(R.string.remove_friend) {
                viewModel.removeRelationship(R.string.friend_has_been_deleted)
            }
            .setDialogAttrTheme(R.attr.notice_theme_positive_red)
            .show(parentFragmentManager)
    }

    private fun configureStageActions(state: WidgetUserSheetViewModel.ViewState.Loaded) {
        val actions = binding.userSheetProfileStageActionsView
        actions.updateView(state.stageViewState)
        binding.userSheetProfileStageActionsCard.visibility = actions.visibility
    }

    private fun bindAdminView() = binding.userSheetAdminView.apply {
        val view = this
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
        view.setOnVerifyMember(viewModel::toggleMemberVerification)
        view.setOnServerMute(viewModel::guildMuteUser)
        view.setOnServerDeafen(viewModel::guildDeafenUser)
        view.setOnServerMove(viewModel::guildMoveForUser)
        view.setOnDisconnect { viewModel.disconnectUser() }
    }

    private fun bindVoiceSettings() = binding.userSheetProfileVoiceSettingsView.apply {
        setOnMuteChecked(viewModel::toggleMute)
        setOnDeafenChecked(viewModel::toggleDeafen)
        setOnVolumeChange { volume, _ -> viewModel.setUserOutputVolume(volume) }
    }

    private fun markAccessibilityHeadings() {
        listOf(
            binding.aboutMeHeader,
            binding.userSheetProfileVoiceSettingsHeader,
            binding.userSheetGuildHeader,
            binding.userSheetConnectionsHeader,
            binding.userSheetNoteHeader,
            binding.userSheetIncomingFriendRequestHeader,
        ).forEach { ViewCompat.setAccessibilityHeading(it, true) }
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

            is WidgetUserSheetViewModel.Event.ConfirmStrangerRequest ->
                StrangerFriendRequest.confirm(parentFragmentManager, requireContext()) {
                    viewModel.addRelationship(event.type, event.username, event.successToast, confirmStranger = true)
                }

            is WidgetUserSheetViewModel.Event.LaunchMessage -> messageNavigator.open(event.userId)

            is WidgetUserSheetViewModel.Event.LaunchVoiceCall -> {
                dismiss()
                onCall?.invoke(event.channelId, false)
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

    private fun View.show(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun bindStageActions() {
        binding.userSheetProfileStageActionsView.apply {
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
        private const val ARG_ORIGIN_GUILD_ID = "ARG_ORIGIN_GUILD_ID"
        private const val ARG_IS_VOICE_CONTEXT = "ARG_IS_VOICE_CONTEXT"
        private const val ARG_STREAM_PREVIEW_CLICK_BEHAVIOR = "ARG_STREAM_PREVIEW_CLICK_BEHAVIOR"
        private const val ARG_FRIEND_TOKEN = "ARG_FRIEND_TOKEN"
        private const val BORDER_OVERLAY_DARK = 0x14FFFFFF
        private const val BORDER_OVERLAY_LIGHT = 0x14000000

        private const val MAIN_TAB = 0
        private const val BOARD_TAB = 1
        private const val MAIN_TAB_KEY = "main"
        private const val BOARD_TAB_KEY = "board"
        private const val PAGE_SETTLE_MS = 180L

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
            originGuildId: Long? = null,
        ) {
            WidgetUserSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_USER_ID, userId)
                    putLong(ARG_CHANNEL_ID, channelId ?: 0L)
                    putLong(ARG_GUILD_ID, guildId ?: 0L)
                    putBoolean(ARG_IS_VOICE_CONTEXT, isVoiceContext)
                    putSerializable(ARG_STREAM_PREVIEW_CLICK_BEHAVIOR, streamPreviewClickBehavior)
                    putString(ARG_FRIEND_TOKEN, friendToken)
                    putLong(ARG_ORIGIN_GUILD_ID, originGuildId ?: 0L)
                }
            }.show(fragmentManager, WidgetUserSheet::class.java.name)
        }

        /** Legacy two-argument entry point kept for existing call sites. */
        @JvmStatic
        fun show(userId: Long, fragmentManager: FragmentManager) = show(fragmentManager, userId)
    }
}

internal fun applyUserSheetNoteTheme(
    binding: WidgetUserSheetBinding,
    colors: ProfileThemeColors?,
    defaults: UserSheetNoteTheme,
) {
    // Nothing but text now, so the profile palette is all it needs: no card, no filled input, and
    // so none of the Material surfaces that used to paint over the gradient.
    binding.userSheetNoteHeader.setTextColor(
        colors?.textMuted?.let(ColorStateList::valueOf) ?: defaults.headerTextColor,
    )
    binding.userSheetNoteText.setTextColor(
        colors?.textDefault?.let(ColorStateList::valueOf) ?: defaults.noteTextColor,
    )
}

internal data class UserSheetNoteTheme(
    val headerTextColor: ColorStateList,
    val noteTextColor: ColorStateList,
) {
    companion object {
        fun from(binding: WidgetUserSheetBinding) = UserSheetNoteTheme(
            headerTextColor = binding.userSheetNoteHeader.textColors,
            noteTextColor = binding.userSheetNoteText.textColors,
        )
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

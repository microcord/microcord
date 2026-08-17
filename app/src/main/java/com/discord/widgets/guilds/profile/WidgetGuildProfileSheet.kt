package com.discord.widgets.guilds.profile

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintSet
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.app.AppBottomSheet
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.databinding.WidgetGuildProfileSheetBinding
import com.discord.databinding.WidgetGuildProfileActionsBinding
import com.discord.i18n.i18nFormat
import com.discord.i18n.i18nPlural
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.resources.getI18nPluralString
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.views.CheckedSetting
import com.discord.widgets.channels.WidgetCreateChannel
import com.discord.widgets.guilds.antiraid.WidgetGuildReportRaid
import com.discord.widgets.guilds.contextmenu.ContextMenuNavigation
import com.discord.widgets.guilds.invite.WidgetGuildInviteShare
import com.discord.widgets.guilds.onboarding.WidgetChannelsAndRoles
import com.discord.widgets.guildscheduledevent.WidgetGuildScheduledEventLocationSelect
import com.discord.widgets.media.WidgetMedia
import com.discord.widgets.servers.WidgetServerNotifications
import com.discord.widgets.channels.WidgetChannelContent
import com.discord.widgets.servers.WidgetServerSettings
import com.discord.widgets.servers.WidgetServerSettingsEmojis
import com.discord.widgets.settings.profile.WidgetEditUserOrGuildMemberProfile
import com.discord.views.VitoImageView
import rx.subscriptions.CompositeSubscription

class WidgetGuildProfileSheet : AppBottomSheet() {
    private var _binding: WidgetGuildProfileSheetBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val guildId get() = argumentsOrDefault.getLong(EXTRA_GUILD_ID)
    private val viewingGuild get() = argumentsOrDefault.getBoolean(EXTRA_VIEWING_GUILD)
    private val channelId get() = argumentsOrDefault.getLong(EXTRA_CHANNEL_ID).takeIf { it != 0L }
    private val viewModel: WidgetGuildProfileSheetViewModel by appViewModels {
        WidgetGuildProfileSheetViewModel(viewingGuild, guildId)
    }
    private var actionsRoot: View? = null
    private var profileActionsBinding: WidgetGuildProfileActionsBinding? = null
    private val emojisAdapter = WidgetGuildProfileSheetEmojisAdapter()

    override fun getContentViewResId() = R.layout.widget_guild_profile_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = WidgetGuildProfileSheetBinding.bind(view)
    }

    override fun onDestroyView() {
        actionsRoot = null
        profileActionsBinding = null
        _binding = null
        super.onDestroyView()
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        viewModel.observeViewState().ui(this)
            .appSubscribe(WidgetGuildProfileSheet::class.java, onNext = ::configureUI)
        viewModel.observeEvents().ui(this)
            .appSubscribe(WidgetGuildProfileSheet::class.java) { event ->
                when (event) {
                    is WidgetGuildProfileSheetViewModel.Event.DismissAndShowToast -> {
                        AppToast.show(context, event.stringRes)
                        dismiss()
                    }
                }
            }
    }

    private fun configureUI(state: WidgetGuildProfileSheetViewModel.ViewState) {
        if (_binding == null) return
        when (state) {
            WidgetGuildProfileSheetViewModel.ViewState.Loading -> binding.guildProfileSheetFlipper.displayedChild = 0
            WidgetGuildProfileSheetViewModel.ViewState.Invalid -> dismiss()
            is WidgetGuildProfileSheetViewModel.ViewState.Loaded -> {
                binding.guildProfileSheetFlipper.displayedChild = 1
                bindHeader(state)
                bindTabs(state)
                bindActions(state)
            }
        }
    }

    private fun bindHeader(state: WidgetGuildProfileSheetViewModel.ViewState.Loaded) {
        binding.guildProfileSheetName.text =
            if (state.isPrivate) getString(R.string.guild_profile_private_name) else state.guildName
        binding.guildProfileSheetIconName.apply {
            text = state.guildShortName
            visibility = if (state.guildIcon == null) View.VISIBLE else View.GONE
        }
        IconUtils.setIcon(binding.guildProfileSheetIcon, IconUtils.getForGuild(
            state.guildId, state.guildIcon, "asset://asset/images/default_icon_selected.jpg", true,
            size = GUILD_PROFILE_ICON_SIZE_DP,
        ))
        binding.guildProfileSheetIcon.setOnClickListener(
            if (state.guildIcon != null && FeatureFlags.viewProfileImagesEnabled()) {
                View.OnClickListener {
                    IconUtils.getForGuild(state.guildId, state.guildIcon, allowAnimation = true, size = IconUtils.AVATAR_MAX_SIZE_DP)
                        ?.let { WidgetMedia.launchImage(requireContext(), it, state.guildName) }
                }
            } else null,
        )
        binding.guildProfileSheetDescription.apply {
            text = if (state.isPrivate) getString(R.string.guild_profile_private_description) else state.guildDescription
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        binding.guildProfileSheetGuildVerifiedPremiumIcon.apply {
            state.verifiedPartneredIconRes?.let(::setImageResource)
            visibility = if (state.verifiedPartneredIconRes == null) View.GONE else View.VISIBLE
        }
        binding.guildProfileSheetOnlineCount.visibility =
            if (state.onlineCount == null) View.GONE else View.VISIBLE
        binding.guildProfileSheetOnlineCountText.text = state.onlineCount?.let {
            requireContext().i18nFormat(R.string.instant_invite_guild_members_online, "membersOnline" to it)
        }
        binding.guildProfileSheetMemberCount.visibility =
            if (state.memberCount == null) View.GONE else View.VISIBLE
        binding.guildProfileSheetMemberCountText.text = state.memberCount?.let {
            requireContext().i18nPlural(R.plurals.members_header_members, it, "members" to it)
        }
        binding.guildProfileSheetBanner.apply {
            val url = when (state.banner.type) {
                WidgetGuildProfileSheetViewModel.Banner.Type.BANNER ->
                    IconUtils.getBannerForGuild(state.banner.guildId, state.banner.hash)
                WidgetGuildProfileSheetViewModel.Banner.Type.SPLASH ->
                    state.banner.hash?.let { IconUtils.getGuildSplashUrl(state.banner.guildId, it) }
            }
            visibility = if (url == null) View.GONE else View.VISIBLE
            if (url != null) MGImages.setImage(this, url)
            setOnClickListener(
                if (url != null && FeatureFlags.viewProfileImagesEnabled()) {
                    View.OnClickListener {
                        val fullUrl = when (state.banner.type) {
                            WidgetGuildProfileSheetViewModel.Banner.Type.BANNER ->
                                IconUtils.getBannerForGuild(state.banner.guildId, state.banner.hash, IconUtils.AVATAR_MAX_SIZE_DP, true)
                            WidgetGuildProfileSheetViewModel.Banner.Type.SPLASH ->
                                state.banner.hash?.let { IconUtils.getGuildSplashUrl(state.banner.guildId, it, IconUtils.AVATAR_MAX_SIZE_DP) }
                        }
                        fullUrl?.let { WidgetMedia.launchImage(requireContext(), it, state.guildName) }
                    }
                } else null,
            )
            if (url == null) constrainIconToParent() else constrainIconToBanner()
        }
    }

    private fun constrainIconToBanner() {
        val layout = binding.guildProfileSheetConstraintLayout
        val icon = binding.guildProfileSheetIconCard
        val banner = binding.guildProfileSheetBanner
        ConstraintSet().apply {
            clone(layout)
            clear(icon.id, ConstraintSet.TOP)
            clear(icon.id, ConstraintSet.BOTTOM)
            connect(icon.id, ConstraintSet.TOP, banner.id, ConstraintSet.BOTTOM)
            connect(icon.id, ConstraintSet.BOTTOM, banner.id, ConstraintSet.BOTTOM)
            applyTo(layout)
        }
    }

    private fun constrainIconToParent() {
        val layout = binding.guildProfileSheetConstraintLayout
        val icon = binding.guildProfileSheetIconCard
        val topMargin = (icon.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        ConstraintSet().apply {
            clone(layout)
            clear(icon.id, ConstraintSet.TOP)
            clear(icon.id, ConstraintSet.BOTTOM)
            connect(icon.id, ConstraintSet.TOP, layout.id, ConstraintSet.TOP, topMargin)
            applyTo(layout)
        }
    }

    private fun bindTabs(state: WidgetGuildProfileSheetViewModel.ViewState.Loaded) {
        val tabs = state.tabItems
        binding.guildProfileSheetTabItems.visibility =
            if (tabs == null || state.isGuildHub) View.GONE else View.VISIBLE
        if (tabs == null || state.isGuildHub) return
        binding.guildProfileSheetNotifications.setOnClickAndDismissListener {
            WidgetServerNotifications.launch(requireContext(), state.guildId)
        }
        binding.guildProfileSheetMedia.setOnClickAndDismissListener {
            WidgetChannelContent.launch(requireContext(), state.guildId, 0L)
        }
        binding.guildProfileSheetSettings.apply {
            visibility = if (tabs.canAccessSettings) View.VISIBLE else View.GONE
            setOnClickAndDismissListener { WidgetServerSettings.launch(requireContext(), state.guildId) }
        }
        binding.guildProfileSheetInvite.apply {
            visibility = if (tabs.ableToInstantInvite) View.VISIBLE else View.GONE
            setOnClickAndDismissListener {
                WidgetGuildInviteShare.launch(
                    requireContext(),
                    parentFragmentManager,
                    state.guildId,
                    source = "Guild Profile",
                )
            }
        }
        binding.guildProfileSheetBoosts.text = resources.getI18nPluralString(
            R.plurals.premium_guild_subscription_num_subscriptions_numSubscriptions,
            R.string.premium_guild_subscription,
            tabs.premiumSubscriptionCount,
            "#" to tabs.premiumSubscriptionCount,
        )
    }

    private fun bindActions(state: WidgetGuildProfileSheetViewModel.ViewState.Loaded) {
        if (actionsRoot == null) {
            actionsRoot = if (state.isGuildHub) {
                binding.widgetGuildHubProfileActions.inflate()
            } else {
                binding.widgetGuildProfileActions.inflate().also {
                    profileActionsBinding = WidgetGuildProfileActionsBinding.bind(it)
                }
            }
        }
        val actionsRoot = actionsRoot ?: return
        val actions = state.actions
        actionsRoot.findViewById<View>(R.id.guild_profile_sheet_actions)?.visibility =
            if (actions == null) View.GONE else View.VISIBLE
        actions?.let { bindMemberActions(actionsRoot, state, it) }

        bindEmojis(actionsRoot, state)

        val bottom = state.bottomActions
        profileActionsBinding?.guildProfileSheetBottomActions?.visibility =
            if (bottom.showUploadEmoji || bottom.showViewServer) View.VISIBLE else View.GONE
        profileActionsBinding?.guildProfileSheetUploadEmoji?.apply {
            visibility = if (bottom.showUploadEmoji) View.VISIBLE else View.GONE
            setOnClickAndDismissListener { WidgetServerSettingsEmojis.launch(requireContext(), state.guildId) }
        }
        profileActionsBinding?.guildProfileSheetViewServer?.apply {
            visibility = if (bottom.showViewServer) View.VISIBLE else View.GONE
            (layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.marginStart = if (bottom.showUploadEmoji) (8 * resources.displayMetrics.density).toInt() else 0
                layoutParams = params
            }
            setOnClickAndDismissListener { viewModel.onClickViewServer(state.guildId, channelId) }
        }
    }

    private fun bindEmojis(root: View, state: WidgetGuildProfileSheetViewModel.ViewState.Loaded) {
        val card = root.findViewById<CardView>(R.id.guild_profile_sheet_emojis) ?: return
        card.visibility = if (state.emojis.isEmpty()) View.GONE else View.VISIBLE
        if (state.emojis.isEmpty()) return
        val recycler = root.findViewById<RecyclerView>(R.id.guild_profile_sheet_emojis_recycler) ?: return
        if (recycler.adapter == null) {
            recycler.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
            recycler.adapter = emojisAdapter
        }
        emojisAdapter.onClickEmoji = {
            dismiss()
            WidgetServerSettingsEmojis.launch(requireContext(), state.guildId)
        }
        val density = resources.displayMetrics.density
        val available = resources.displayMetrics.widthPixels - (64 * density)
        emojisAdapter.setData(state.emojis, (available / (32 * density)).toInt().coerceAtLeast(1))
    }

    private fun bindMemberActions(
        root: View,
        state: WidgetGuildProfileSheetViewModel.ViewState.Loaded,
        actions: WidgetGuildProfileSheetViewModel.Actions,
    ) {
        root.findViewById<View>(R.id.guild_profile_sheet_mark_as_read)?.apply {
            visibility = if (actions.isUnread) View.VISIBLE else View.GONE
            setOnClickListener { viewModel.onClickMarkAsRead(state.guildId) }
        }
        root.findViewById<TextView>(R.id.guild_profile_sheet_mute)?.apply {
            setText(if (actions.isMuted) R.string.unmute_server else R.string.mute_server)
            setOnClickAndDismissListener {
                viewModel.setMuted(requireContext().applicationContext, state.guildId, !actions.isMuted)
            }
        }
        root.findViewById<View>(R.id.guild_profile_sheet_channels_and_roles)?.apply {
            visibility = if (actions.isCommunity) View.VISIBLE else View.GONE
            setOnClickAndDismissListener { WidgetChannelsAndRoles.launch(requireContext(), state.guildId) }
        }
        bindPrimaryActions(root, state, actions)
        (root.findViewById<View>(R.id.change_identity)
            ?: root.findViewById(R.id.guild_profile_sheet_change_nickname))?.setOnClickAndDismissListener {
            WidgetEditUserOrGuildMemberProfile.launch(requireContext(), state.guildId)
        }
        root.findViewById<View>(R.id.guild_profile_sheet_invite)?.setOnClickAndDismissListener {
            WidgetGuildInviteShare.launch(
                requireContext(), parentFragmentManager, state.guildId, source = "Guild Profile",
            )
        }
        root.findViewById<View>(R.id.guild_profile_sheet_notifications)?.setOnClickAndDismissListener {
            WidgetServerNotifications.launch(requireContext(), state.guildId)
        }
        root.findViewById<TextView>(R.id.guild_profile_sheet_nickname)?.apply {
            text = actions.nick ?: actions.username
        }
        root.findViewById<LinearLayout>(R.id.per_guild_identity_container)?.visibility =
            if (actions.displayGuildIdentityRow) View.VISIBLE else View.GONE
        root.findViewById<VitoImageView>(R.id.guild_profile_sheet_guild_avatar)?.apply {
            visibility = if (actions.guildAvatar.isNullOrEmpty()) View.GONE else View.VISIBLE
            IconUtils.getForGuildMember(actions.guildAvatar, state.guildId, state.meUser.id, 32)
                ?.let { MGImages.setImage(this, it) }
        }
        root.findViewById<CheckedSetting>(R.id.guild_profile_sheet_allow_dm)?.apply {
            isChecked = actions.isAllowDMChecked
            setOnCheckedListener { viewModel.setAllowDM(state.guildId, it) }
        }
        root.findViewById<CheckedSetting>(R.id.guild_profile_sheet_allow_message_requests)?.apply {
            isChecked = actions.isAllowMessageRequestsChecked
            setOnCheckedListener { viewModel.setAllowMessageRequests(state.guildId, it) }
        }
        root.findViewById<CheckedSetting>(R.id.guild_profile_sheet_hide_muted_channels)?.apply {
            isChecked = actions.hideMutedChannels
            setOnCheckedListener { viewModel.setHideMutedChannels(state.guildId, it) }
        }
        root.findViewById<CheckedSetting>(R.id.guild_profile_sheet_show_all_channels)?.apply {
            visibility = if (actions.isCommunity) View.VISIBLE else View.GONE
            isChecked = actions.showAllChannels
            setOnCheckedListener {
                viewModel.setShowAllChannels(requireContext().applicationContext, state.guildId, it)
            }
        }
        root.findViewById<View>(R.id.guild_profile_sheet_report_raid)?.apply {
            visibility = if (actions.canReportRaid) View.VISIBLE else View.GONE
            setOnClickAndDismissListener { WidgetGuildReportRaid.launch(requireContext(), state.guildId) }
        }
        root.findViewById<View>(R.id.guild_profile_sheet_leave_server)?.apply {
            visibility = if (actions.canLeaveGuild) View.VISIBLE else View.GONE
            setOnClickAndDismissListener { ContextMenuNavigation.showLeaveGuild(parentFragmentManager, state.guildId) }
        }
        root.findViewById<View>(R.id.guild_profile_sheet_copy_id)?.apply {
            visibility = if (actions.isDeveloper) View.VISIBLE else View.GONE
            setOnClickListener { AppToast.copyToClipboard(requireContext(), state.guildId.toString()) }
        }
    }

    private fun bindPrimaryActions(
        root: View,
        state: WidgetGuildProfileSheetViewModel.ViewState.Loaded,
        actions: WidgetGuildProfileSheetViewModel.Actions,
    ) {
        root.findViewById<View>(R.id.guild_profile_sheet_create_event)?.apply {
            visibility = if (actions.canManageEvents) View.VISIBLE else View.GONE
            setOnClickAndDismissListener {
                WidgetGuildScheduledEventLocationSelect.launch(requireContext(), state.guildId)
            }
        }
        root.findViewById<View>(R.id.guild_profile_sheet_create_channel)?.apply {
            visibility = if (actions.canManageChannels) View.VISIBLE else View.GONE
            setOnClickAndDismissListener {
                WidgetCreateChannel.show(requireContext(), state.guildId, Channel.GUILD_TEXT)
            }
        }
        root.findViewById<View>(R.id.guild_profile_sheet_create_category)?.apply {
            visibility = if (actions.canManageChannels) View.VISIBLE else View.GONE
            setOnClickAndDismissListener {
                WidgetCreateChannel.show(requireContext(), state.guildId, Channel.CATEGORY)
            }
        }
        root.findViewById<CardView>(R.id.guild_profile_sheet_primary_actions)?.visibility =
            if (actions.canManageEvents || actions.canManageChannels) View.VISIBLE else View.GONE
    }

    companion object {
        /** Deliberately above the 80dp it renders at, matching the reference. */
        private const val GUILD_PROFILE_ICON_SIZE_DP = 96

        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val EXTRA_VIEWING_GUILD = "com.discord.intent.extra.EXTRA_VIEWING_GUILD"
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"

        fun show(fragmentManager: FragmentManager, viewingGuild: Boolean, guildId: Long, channelId: Long? = null) {
            WidgetGuildProfileSheet().apply {
                arguments = Bundle().apply {
                    putLong(EXTRA_GUILD_ID, guildId)
                    putBoolean(EXTRA_VIEWING_GUILD, viewingGuild)
                    channelId?.let { putLong(EXTRA_CHANNEL_ID, it) }
                }
            }.show(fragmentManager, WidgetGuildProfileSheet::class.java.name)
        }
    }
}

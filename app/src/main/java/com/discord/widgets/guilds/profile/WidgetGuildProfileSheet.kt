package com.discord.widgets.guilds.profile

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppToast
import com.discord.app.AppViewFlipper
import com.discord.app.appViewModels
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.views.CheckedSetting
import com.discord.widgets.guilds.contextmenu.ContextMenuNavigation
import com.discord.widgets.guilds.invite.WidgetGuildInviteShare
import com.discord.widgets.servers.WidgetServerNotifications
import com.discord.widgets.servers.WidgetServerSettings
import com.discord.widgets.servers.WidgetServerSettingsEmojis
import com.facebook.drawee.view.SimpleDraweeView
import rx.subscriptions.CompositeSubscription

class WidgetGuildProfileSheet : AppBottomSheet() {
    private val guildId get() = argumentsOrDefault.getLong(EXTRA_GUILD_ID)
    private val viewingGuild get() = argumentsOrDefault.getBoolean(EXTRA_VIEWING_GUILD)
    private val channelId get() = argumentsOrDefault.getLong(EXTRA_CHANNEL_ID).takeIf { it != 0L }
    private val viewModel: WidgetGuildProfileSheetViewModel by appViewModels {
        WidgetGuildProfileSheetViewModel(viewingGuild, guildId)
    }
    private val emojisAdapter = WidgetGuildProfileSheetEmojisAdapter().apply {
        onClickEmoji = { viewModel.onClickEmoji() }
    }
    private var actionsRoot: View? = null

    override fun getContentViewResId() = R.layout.widget_guild_profile_sheet

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
        val root = view ?: return
        val flipper = root.findViewById<AppViewFlipper>(R.id.guild_profile_sheet_flipper)
        when (state) {
            WidgetGuildProfileSheetViewModel.ViewState.Loading -> flipper.displayedChild = 0
            WidgetGuildProfileSheetViewModel.ViewState.Invalid -> dismiss()
            is WidgetGuildProfileSheetViewModel.ViewState.Loaded -> {
                flipper.displayedChild = 1
                bindHeader(root, state)
                bindTabs(root, state)
                bindActions(root, state)
            }
        }
    }

    private fun bindHeader(root: View, state: WidgetGuildProfileSheetViewModel.ViewState.Loaded) {
        root.findViewById<TextView>(R.id.guild_profile_sheet_name).text = state.guildName
        root.findViewById<TextView>(R.id.guild_profile_sheet_icon_name).apply {
            text = state.guildShortName
            visibility = if (state.guildIcon == null) View.VISIBLE else View.GONE
        }
        IconUtils.setIcon(
            root.findViewById(R.id.guild_profile_sheet_icon),
            IconUtils.getForGuild(state.guildId, state.guildIcon, "asset://asset/images/default_icon_selected.jpg", true),
        )
        root.findViewById<TextView>(R.id.guild_profile_sheet_description).apply {
            text = state.guildDescription
            visibility = if (state.guildDescription.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        root.findViewById<ImageView>(R.id.guild_profile_sheet_guild_verified_premium_icon).apply {
            state.verifiedPartneredIconRes?.let(::setImageResource)
            visibility = if (state.verifiedPartneredIconRes == null) View.GONE else View.VISIBLE
        }
        root.findViewById<LinearLayout>(R.id.guild_profile_sheet_online_count).visibility =
            if (state.onlineCount == null) View.GONE else View.VISIBLE
        root.findViewById<TextView>(R.id.guild_profile_sheet_online_count_text).text = state.onlineCount?.toString()
        root.findViewById<LinearLayout>(R.id.guild_profile_sheet_member_count).visibility =
            if (state.memberCount == null) View.GONE else View.VISIBLE
        root.findViewById<TextView>(R.id.guild_profile_sheet_member_count_text).text = state.memberCount?.toString()
        root.findViewById<SimpleDraweeView>(R.id.guild_profile_sheet_banner).apply {
            val url = when (state.banner.type) {
                WidgetGuildProfileSheetViewModel.Banner.Type.BANNER ->
                    IconUtils.getBannerForGuild(state.banner.guildId, state.banner.hash)
                WidgetGuildProfileSheetViewModel.Banner.Type.SPLASH ->
                    state.banner.hash?.let { IconUtils.getGuildSplashUrl(state.banner.guildId, it) }
            }
            visibility = if (url == null) View.GONE else View.VISIBLE
            if (url != null) MGImages.setImage(this, url)
            if (url == null) constrainIconToParent(root) else constrainIconToBanner(root)
        }
    }

    private fun constrainIconToBanner(root: View) {
        val layout = root.findViewById<ConstraintLayout>(R.id.guild_profile_sheet_constraint_layout)
        val icon = root.findViewById<CardView>(R.id.guild_profile_sheet_icon_card)
        val banner = root.findViewById<View>(R.id.guild_profile_sheet_banner)
        ConstraintSet().apply {
            clone(layout)
            clear(icon.id, ConstraintSet.TOP)
            clear(icon.id, ConstraintSet.BOTTOM)
            connect(icon.id, ConstraintSet.TOP, banner.id, ConstraintSet.BOTTOM)
            connect(icon.id, ConstraintSet.BOTTOM, banner.id, ConstraintSet.BOTTOM)
            applyTo(layout)
        }
    }

    private fun constrainIconToParent(root: View) {
        val layout = root.findViewById<ConstraintLayout>(R.id.guild_profile_sheet_constraint_layout)
        val icon = root.findViewById<CardView>(R.id.guild_profile_sheet_icon_card)
        val topMargin = (icon.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        ConstraintSet().apply {
            clone(layout)
            clear(icon.id, ConstraintSet.TOP)
            clear(icon.id, ConstraintSet.BOTTOM)
            connect(icon.id, ConstraintSet.TOP, layout.id, ConstraintSet.TOP, topMargin)
            applyTo(layout)
        }
    }

    private fun bindTabs(root: View, state: WidgetGuildProfileSheetViewModel.ViewState.Loaded) {
        val tabs = state.tabItems
        root.findViewById<LinearLayout>(R.id.guild_profile_sheet_tab_items).visibility =
            if (tabs == null || state.isGuildHub) View.GONE else View.VISIBLE
        if (tabs == null || state.isGuildHub) return
        root.findViewById<View>(R.id.guild_profile_sheet_notifications).setOnClickAndDismissListener {
            WidgetServerNotifications.launch(requireContext(), state.guildId)
        }
        root.findViewById<View>(R.id.guild_profile_sheet_settings).apply {
            visibility = if (tabs.canAccessSettings) View.VISIBLE else View.GONE
            setOnClickAndDismissListener { WidgetServerSettings.launch(requireContext(), state.guildId) }
        }
        root.findViewById<View>(R.id.guild_profile_sheet_invite).apply {
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
        root.findViewById<TextView>(R.id.guild_profile_sheet_boosts).text = tabs.premiumSubscriptionCount.toString()
    }

    private fun bindActions(root: View, state: WidgetGuildProfileSheetViewModel.ViewState.Loaded) {
        val expectedLayout = if (state.isGuildHub) R.id.widget_guild_hub_profile_actions else R.id.widget_guild_profile_actions
        if (actionsRoot == null) actionsRoot = root.findViewById<ViewStub>(expectedLayout)?.inflate()
        val actionsRoot = actionsRoot ?: return
        val actions = state.actions
        actionsRoot.findViewById<View>(R.id.guild_profile_sheet_actions)?.visibility =
            if (actions == null) View.GONE else View.VISIBLE
        actions?.let { bindMemberActions(actionsRoot, state, it) }

        actionsRoot.findViewById<RecyclerView>(R.id.guild_profile_sheet_emojis)?.apply {
            if (adapter !== emojisAdapter) adapter = emojisAdapter
            val columns = (resources.displayMetrics.widthPixels / (48 * resources.displayMetrics.density)).toInt().coerceAtLeast(1)
            (layoutManager as? GridLayoutManager)?.spanCount = columns
            emojisAdapter.setData(state.emojisData.emojis, if (state.emojisData.isExpanded) Int.MAX_VALUE else columns * 2)
        }
        actionsRoot.findViewById<CardView>(R.id.guild_profile_sheet_emojis_card)?.visibility =
            if (state.emojisData.emojis.isEmpty()) View.GONE else View.VISIBLE
        actionsRoot.findViewById<TextView>(R.id.guild_profile_sheet_emojis_count)?.text = state.emojisData.emojis.size.toString()

        val bottom = state.bottomActions
        actionsRoot.findViewById<LinearLayout>(R.id.guild_profile_sheet_bottom_actions)?.visibility =
            if (bottom.showUploadEmoji || bottom.showJoinServer || bottom.showViewServer) View.VISIBLE else View.GONE
        actionsRoot.findViewById<View>(R.id.guild_profile_sheet_upload_emoji)?.apply {
            visibility = if (bottom.showUploadEmoji) View.VISIBLE else View.GONE
            setOnClickAndDismissListener { WidgetServerSettingsEmojis.launch(requireContext(), state.guildId) }
        }
        actionsRoot.findViewById<View>(R.id.guild_profile_sheet_join_server)?.apply {
            visibility = if (bottom.showJoinServer) View.VISIBLE else View.GONE
            setOnClickAndDismissListener { viewModel.onClickJoinServer(state.guildId) }
        }
        actionsRoot.findViewById<View>(R.id.guild_profile_sheet_view_server)?.apply {
            visibility = if (bottom.showViewServer) View.VISIBLE else View.GONE
            setOnClickAndDismissListener { viewModel.onClickViewServer(state.guildId, channelId) }
        }
    }

    private fun bindMemberActions(
        root: View,
        state: WidgetGuildProfileSheetViewModel.ViewState.Loaded,
        actions: WidgetGuildProfileSheetViewModel.Actions,
    ) {
        root.findViewById<CardView>(R.id.guild_profile_sheet_mark_as_read_action)?.visibility =
            if (actions.isUnread) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.guild_profile_sheet_mark_as_read)?.setOnClickListener {
            viewModel.onClickMarkAsRead(state.guildId)
        }
        (root.findViewById<View>(R.id.change_identity)
            ?: root.findViewById(R.id.guild_profile_sheet_change_nickname))?.setOnClickAndDismissListener {
            WidgetChangeGuildIdentity.launch(state.guildId, "Guild Bottom Sheet", requireContext())
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
        root.findViewById<SimpleDraweeView>(R.id.guild_profile_sheet_guild_avatar)?.apply {
            visibility = if (actions.guildAvatar.isNullOrEmpty()) View.GONE else View.VISIBLE
            IconUtils.getForGuildMember(actions.guildAvatar, state.guildId, state.meUser.id, 32)
                ?.let { MGImages.setImage(this, it) }
        }
        root.findViewById<CheckedSetting>(R.id.guild_profile_sheet_allow_dm)?.apply {
            isChecked = actions.isAllowDMChecked
            setOnCheckedListener { viewModel.setAllowDM(state.guildId, it) }
        }
        root.findViewById<CheckedSetting>(R.id.guild_profile_sheet_hide_muted_channels)?.apply {
            isChecked = actions.hideMutedChannels
            setOnCheckedListener { viewModel.setHideMutedChannels(state.guildId, it) }
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

    companion object {
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

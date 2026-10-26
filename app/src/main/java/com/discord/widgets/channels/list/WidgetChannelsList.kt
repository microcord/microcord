package com.discord.widgets.channels.list

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.discord.R
import com.discord.panels.PanelState
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isGuildVocalChannel
import com.discord.widgets.voice.fullscreen.WidgetGuildCallOnboardingSheet
import com.discord.api.guild.GuildFeature
import com.discord.app.AppFragment
import com.discord.models.guild.Guild
import com.discord.stores.SelectedChannelAnalyticsLocation
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelSelector
import com.discord.widgets.channels.SpoilerChannelGate
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.persister.Persister
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.rx.ui
import com.discord.utilities.view.rounded.RoundedCoordinatorLayout
import com.discord.views.CustomAppBarLayout
import com.discord.views.ThemedColor
import com.discord.widgets.channels.invite.GroupInviteFriendsSheet
import com.discord.widgets.channels.list.items.ChannelListItemPrivate
import com.discord.widgets.friends.EmptyFriendsStateView
import com.discord.widgets.hubs.events.WidgetHubEventsPage
import com.discord.widgets.guilds.onboarding.WidgetChannelsAndRoles
import com.discord.widgets.message_requests.WidgetMessageRequests
import com.discord.widgets.user.search.WidgetGlobalSearchDialog
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.views.VitoImageView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.discord.widgets.servers.member_verification.MemberVerificationPendingDialog
import com.discord.widgets.servers.member_verification.MemberVerificationPendingViewModel

/**
 * The channels sidebar: the guild banner and header, the channel list itself, the sticky
 * jump-to-unread bars, and the private-channel ("Direct Messages") variant of all of the above.
 *
 * Everything the list renders comes from [WidgetChannelListModel.get]; this screen only binds that
 * model and routes the adapter's interactions.
 */
class WidgetChannelsList : AppFragment(R.layout.widget_channels_list) {

    private lateinit var adapter: WidgetChannelsListAdapter
    private var channelListUnreads: WidgetChannelListUnreads? = null

    private lateinit var container: RoundedCoordinatorLayout
    private lateinit var appBarLayout: CustomAppBarLayout
    private lateinit var collapsingToolbar: CollapsingToolbarLayout
    private lateinit var banner: VitoImageView
    private lateinit var bannerForeground: FrameLayout
    private lateinit var header: TextView
    private lateinit var privateChannelsHeader: Toolbar
    private lateinit var directMessagesTitle: TextView
    private lateinit var search: TextView
    private lateinit var startGroup: View
    private lateinit var messageRequests: View
    private lateinit var messageRequestsCount: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyFriendsState: EmptyFriendsStateView
    private lateinit var premiumGuildHint: ConstraintLayout

    /** Tracks the collapsing-toolbar state so the header only restyles when it actually flips. */
    private var isCollapsed: Boolean = false

    /** The guild whose list is on screen, used to detect a switch and reset the scroll. */
    private var selectedGuildId: Long? = null
    private var focusedPrivateChannelId: Long? = null
    private var pendingPrivateChannelAnchor: Pair<Int, Int>? = null
    private var isStartPanelOpen = false

    /** Re-uploading the same banner on every emission would flicker the drawee view. */
    private val bannerChangeDetector = MGImages.DistinctChangeDetector()

    private val storeNavigation: StoreNavigation get() = StoreStream.getNavigation()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        bindViews(view)
        emptyFriendsState.updateView(ANALYTICS_SOURCE)

        adapter = MGRecyclerAdapter.configure(WidgetChannelsListAdapter(recycler))
        wireAdapter()

        // The rows have wildly different heights (banner spacer, event card, plain channel), so
        // the fixed-size optimisation the shared configure() applies has to be undone here, and
        // the default change animation would cross-fade every rebind of a selected row.
        recycler.setHasFixedSize(false)
        recycler.itemAnimator = null

        channelListUnreads = WidgetChannelListUnreads(
            unreadsStub = view.findViewById(R.id.channels_list_unreads_stub),
            recycler = recycler,
            appBarLayout = appBarLayout,
            getItemCount = { adapter.itemCount },
        )
        adapter.setOnUpdated { _, newData -> channelListUnreads?.onDatasetChanged(newData) }

        search.setOnClickListener {
            WidgetGlobalSearchDialog.show(parentFragmentManager, "@")
        }
        startGroup.setOnClickListener {
            GroupInviteFriendsSheet.show(parentFragmentManager, source = ANALYTICS_SOURCE)
        }
        messageRequests.setOnClickListener { WidgetMessageRequests.show(requireContext()) }
        view.findViewById<View>(R.id.channels_list_premium_guild_hint_button)
            .setOnClickListener { ackPremiumGuildHint() }

        ViewCompat.setAccessibilityHeading(header, true)
        ViewCompat.setAccessibilityHeading(directMessagesTitle, true)

        // The real subscription below only delivers its first value after a scheduler hop (the
        // model's combineLatest chain runs on the computation scheduler), so a freshly (re)created
        // view would otherwise show an empty list for a frame or two even when the data was already
        // computed during a previous visit — including one from before the app process was last
        // killed, since the cache below survives a restart. Render that stale copy immediately; the
        // subscription's first real emission — often for the same, unchanged state — replaces it
        // moments later.
        lastModelCache.get().takeIf { it.selectedGuild != null || it.items.isNotEmpty() }
            ?.let(::configureUI)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        WidgetChannelListModel.get()
            .ui(this)
            .appSubscribe(WidgetChannelsList::class.java, onNext = ::configureUI)

        StoreStream.getUsersTyping().observeTypingChannelIds()
            .ui(this)
            .appSubscribe(WidgetChannelsList::class.java) { adapter.typingChannelIds = it }

        storeNavigation.observeLeftPanelState()
            .ui(this)
            .appSubscribe(WidgetChannelsList::class.java) { state ->
                isStartPanelOpen = state == PanelState.Open
                if (isStartPanelOpen) applyPendingPrivateChannelAnchor()
            }

        if (mostRecentIntent.getBooleanExtra(INTENT_EXTRA_OPEN_PANEL, false)) {
            storeNavigation.setNavigationPanelAction(StoreNavigation.PanelAction.OPEN)
            mostRecentIntent.removeExtra(INTENT_EXTRA_OPEN_PANEL)
        }

        // The global status indicator (voice / go-live bar) sits above this panel; when it takes
        // over the top of the screen the panel must drop its rounded corners or a seam shows.
        ChannelListNavigation.customStatusBackground()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetChannelsList::class.java) { isCustomBackground ->
                val radius = if (isCustomBackground) 0f else dpToPx(PANEL_CORNER_RADIUS_DP)
                container.updateTopLeftRadius(radius)
                container.updateTopRightRadius(radius)
            }

        ChannelListNavigation.bottomNavHeight()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetChannelsList::class.java) { bottomNavHeight ->
                adapter.handleBottomNavHeight(bottomNavHeight)
                // The stub replaces itself with the inflated bar the first time the jump
                // indicator is shown, so look the view up by id rather than holding a reference.
                val unreadsBar: View? = view?.findViewById<View>(R.id.channels_list_unreads_stub)
                    ?: view?.findViewById<View>(R.id.channels_list_unreads_background)
                        ?.parent as? View
                (unreadsBar?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                    params.bottomMargin = bottomNavHeight
                    unreadsBar.layoutParams = params
                }
            }
    }

    private fun bindViews(view: View) {
        container = view.findViewById(R.id.widget_channels_list_container)
        appBarLayout = view.findViewById(R.id.app_bar_layout)
        collapsingToolbar = view.findViewById(R.id.collapsing_toolbar)
        banner = view.findViewById(R.id.channels_list_banner)
        bannerForeground = view.findViewById(R.id.channels_list_banner_foreground)
        header = view.findViewById(R.id.channels_list_header)
        privateChannelsHeader = view.findViewById(R.id.channels_list_private_channels_header)
        directMessagesTitle = view.findViewById(R.id.channels_list_direct_messages_title)
        search = view.findViewById(R.id.channels_list_search)
        startGroup = view.findViewById(R.id.channels_list_start_group)
        messageRequests = view.findViewById(R.id.channels_list_message_requests)
        messageRequestsCount = view.findViewById(R.id.channels_list_message_requests_count)
        recycler = view.findViewById(R.id.channels_list)
        emptyFriendsState = view.findViewById(R.id.widget_channels_list_empty_friends_state_view)
        premiumGuildHint = view.findViewById(R.id.channels_list_premium_guild_hint)
    }

    private fun wireAdapter() {
        adapter.onSelectChannel = ::selectChannel
        adapter.onSelectChannelOptions = ::showChannelOptions
        adapter.onCallChannel = { channel -> ChannelListNavigation.callChannel(this, channel) }
        adapter.onSelectGuildRoleSubscriptionLockedChannel = {
            selectedGuildId?.let { guildId ->
                ChannelListNavigation.showGuildRoleSubscriptions(
                    this,
                    guildId,
                    "Role Subscription Gated Channel",
                )
            }
        }
        adapter.onViewGuildRoleSubscriptions = {
            selectedGuildId?.let { guildId ->
                ChannelListNavigation.showGuildRoleSubscriptions(
                    this,
                    guildId,
                    "Role Subscriptions Tab",
                )
            }
        }
        adapter.onViewGuildOnboarding = {
            selectedGuildId?.let { WidgetChannelsAndRoles.launch(requireContext(), it) }
        }
        adapter.onCollapseCategory = ::collapseCategory
        adapter.onSelectInvite = { anchor ->
            ChannelListNavigation.copyGuildInvite(this, anchor, selectedGuildId)
        }
        adapter.onShareGuildInvite = { guildId ->
            ChannelListNavigation.shareGuildInvite(this, guildId)
        }
        adapter.onSelectUserOptions = { user, channel ->
            WidgetUserSheet.show(
                fragmentManager = parentFragmentManager,
                userId = user.id,
                channelId = channel.id,
                guildId = channel.guildId,
                isVoiceContext = channel.isGuildVocalChannel(),
            )
        }
        adapter.onViewGuildScheduledEvents = ::showGuildScheduledEvents
        adapter.onViewGuildScheduledEventDetails = { eventId ->
            ChannelListNavigation.showGuildScheduledEventDetails(this, eventId)
        }
        adapter.onCreateChannel = { guildId, channelType, categoryId ->
            ChannelListNavigation.createChannel(this, guildId, channelType, categoryId)
        }
        adapter.onOpenMemberVerification = {
            selectedGuildId?.let { ChannelListNavigation.showMemberVerification(this, it) }
        }
        adapter.onShowApplicationStatus = { dialogState ->
            selectedGuildId?.let {
                MemberVerificationPendingDialog.show(parentFragmentManager, it, dialogState)
            }
        }
        adapter.onAddServer = { ChannelListNavigation.addServerToHub(this) }
    }

    /** `null` clears the selection, which is how the hub returns the user to the DM list. */
    private fun selectChannel(channel: Channel?) {
        if (channel == null) {
            ChannelSelector.getInstance().selectChannel(0L, 0L)
            return
        }
        if (channel.isGuildVocalChannel() && SpoilerChannelGate.maybeShowForVoiceChannel(
                requireContext(),
                parentFragmentManager,
                channel.id,
            ) { selectChannel(channel) }
        ) return
        // First guild voice channel a viewer opens gets the "how calls work" sheet instead of a
        // silent join, which is the only thing that ever showed it.
        if (channel.isGuildVocalChannel() &&
            !WidgetGuildCallOnboardingSheet.hasUserSeenVoiceChannelOnboarding(requireContext())
        ) {
            WidgetGuildCallOnboardingSheet.markSeenUserVoiceChannelOnboarding(requireContext())
            WidgetGuildCallOnboardingSheet.show(parentFragmentManager, channel.id)
            return
        }
        ChannelSelector.getInstance().selectChannel(
            channel,
            analyticsLocation = SelectedChannelAnalyticsLocation.CHANNEL_LIST,
        )
        storeNavigation.setNavigationPanelAction(StoreNavigation.PanelAction.UNLOCK_LEFT)
        storeNavigation.setNavigationPanelAction(StoreNavigation.PanelAction.CLOSE)
    }

    /**
     * Long-press routing. Threads get their own sheet; every other long-pressable channel type
     * shares the channel sheet. A voice channel opens its settings screen directly, because its
     * actions are settings rather than list actions.
     */
    /**
     * A long press opens the channel's actions, never a settings screen directly.
     *
     * Voice used to jump straight to its settings, which put an editing screen behind a gesture
     * that everywhere else means "show me what I can do here" — and hid the rest of the actions
     * from anyone without permission to manage the channel.
     */
    private fun showChannelOptions(channel: Channel) {
        when (channel.type) {
            Channel.ANNOUNCEMENT_THREAD, Channel.PUBLIC_THREAD, Channel.PRIVATE_THREAD ->
                WidgetChannelsListItemThreadActions.show(parentFragmentManager, channel.id)
            Channel.GUILD_TEXT,
            Channel.DM,
            Channel.GROUP_DM,
            Channel.CATEGORY,
            Channel.GUILD_ANNOUNCEMENT,
            Channel.GUILD_FORUM,
            Channel.GUILD_MEDIA,
            Channel.GUILD_VOICE,
            Channel.GUILD_STAGE_VOICE,
            ->
                WidgetChannelsListItemChannelActions.show(parentFragmentManager, channel.id)
            else -> Unit
        }
    }

    /**
     * [wasCollapsed] is the state the row was showing, so the new state is its inverse. The
     * announcement is posted by hand because the arrow, not the row, carries the state and a
     * rebind alone tells a screen reader nothing.
     */
    private fun collapseCategory(category: Channel, wasCollapsed: Boolean) {
        val manager =
            context?.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (manager != null) {
            @Suppress("DEPRECATION")
            val event = AccessibilityEvent.obtain()
            event.eventType = AccessibilityEvent.TYPE_ANNOUNCEMENT
            val state = getString(if (wasCollapsed) R.string.expanded else R.string.collapsed)
            event.text.add("$state ${category.getDisplayName()}")
            // Throws if accessibility is turned off between the lookup and the send.
            runCatching { manager.sendAccessibilityEvent(event) }
        }
        StoreStream.getCollapsedChannelCategories()
            .setCollapsedCategory(category.guildId, category.id, !wasCollapsed)
    }

    private fun showGuildScheduledEvents(guild: Guild) {
        if (guild.isHub()) {
            val directoryChannel = StoreStream.getChannels()
                .getChannelsForGuild(guild.id)
                .values
                .firstOrNull { it.type == Channel.DIRECTORY }
                ?: return
            WidgetHubEventsPage.show(parentFragmentManager, guild.id, directoryChannel.id)
        } else {
            ChannelListNavigation.showGuildScheduledEventList(this, guild.id)
        }
    }

    private fun ackPremiumGuildHint() {
        premiumGuildHint.visibility = View.GONE
        ChannelListNavigation.ackPremiumGuildHint()
    }

    private fun configureUI(model: WidgetChannelListModel) {
        lastModelCache.set(model)
        val guild = model.selectedGuild
        val hasBanner = guild?.banner != null

        emptyFriendsState.visibility = if (model.showEmptyState) View.VISIBLE else View.GONE
        // INVISIBLE rather than GONE: the recycler keeps its measured size, so the first DM to
        // arrive does not trigger a relayout of the whole panel.
        recycler.visibility = if (model.showEmptyState) View.INVISIBLE else View.VISIBLE

        if (selectedGuildId != guild?.id) {
            if (hasBanner) {
                recycler.scrollToPosition(0)
                appBarLayout.setExpanded(true)
                configureHeaderColors(guild, hasBanner = true)
            }
            selectedGuildId = guild?.id
            guild?.id?.let { adapter.selectedGuildId = it }
        }

        val privateChannelAnchor = capturePrivateChannelAnchor(model)
        adapter.setData(model.items)
        restorePrivateChannelAnchor(privateChannelAnchor)

        collapsingToolbar.visibility = if (model.isGuildSelected) View.VISIBLE else View.GONE
        (collapsingToolbar.layoutParams as? AppBarLayout.LayoutParams)?.scrollFlags =
            if (model.isGuildSelected && hasBanner) {
                AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                    AppBarLayout.LayoutParams.SCROLL_FLAG_EXIT_UNTIL_COLLAPSED
            } else {
                0
            }

        privateChannelsHeader.visibility =
            if (!model.isGuildSelected && !model.showEmptyState) View.VISIBLE else View.GONE
        messageRequests.visibility = if (model.messageRequestCount > 0) View.VISIBLE else View.GONE
        messageRequestsCount.text = model.messageRequestCount.coerceAtMost(99).toString()
        search.visibility = if (model.isGuildSelected) View.GONE else View.VISIBLE

        header.text = guild?.name
        header.setOnClickListener {
            ackPremiumGuildHint()
            ChannelListNavigation.showGuildProfile(this, guild?.id ?: 0L)
        }
        configureHeaderIcons(guild, isCollapsed = false)

        banner.visibility = if (hasBanner) View.VISIBLE else View.GONE
        bannerForeground.visibility = if (hasBanner && !isCollapsed) View.VISIBLE else View.GONE
        if (hasBanner) configureBanner(guild, model.startsWithPaddedElement)

        appBarLayout.setOnPercentCollapsedCallback { percentCollapsed ->
            banner.alpha = 1f - percentCollapsed
            if (isCollapsed != appBarLayout.isCollapsed) {
                isCollapsed = appBarLayout.isCollapsed
                configureHeaderIcons(guild, isCollapsed)
                configureHeaderColors(guild, hasBanner)
            }
        }

        premiumGuildHint.visibility = if (model.showPremiumGuildHint) View.VISIBLE else View.GONE
    }

    private fun capturePrivateChannelAnchor(model: WidgetChannelListModel): Pair<Int, Int>? {
        if (model.isGuildSelected) {
            focusedPrivateChannelId = null
            pendingPrivateChannelAnchor = null
            return null
        }
        val newIndex = model.items.indexOfFirst { it is ChannelListItemPrivate && it.selected }
        if (newIndex < 0) return null
        val channelId = (model.items[newIndex] as ChannelListItemPrivate).channel.id
        val oldIndex = (0 until adapter.itemCount).firstOrNull { index ->
            (adapter.getItem(index) as? ChannelListItemPrivate)?.channel?.id == channelId
        }
        if (focusedPrivateChannelId == channelId && oldIndex == newIndex) return null
        val oldTop = oldIndex?.let { index -> recycler.layoutManager?.findViewByPosition(index)?.top } ?: 0
        focusedPrivateChannelId = channelId
        return newIndex to oldTop
    }

    private fun restorePrivateChannelAnchor(anchor: Pair<Int, Int>?) {
        pendingPrivateChannelAnchor = anchor ?: return
        applyPendingPrivateChannelAnchor()
    }

    private fun applyPendingPrivateChannelAnchor() {
        if (!isStartPanelOpen) return
        val (index, top) = pendingPrivateChannelAnchor ?: return
        pendingPrivateChannelAnchor = null
        recycler.post {
            (recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(index, top)
                ?: recycler.scrollToPosition(index)
        }
    }

    private fun configureBanner(guild: Guild?, startsWithPaddedElement: Boolean) {
        // Rows that draw their own leading space would otherwise sit a gap below the banner.
        (banner.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            params.bottomMargin =
                if (startsWithPaddedElement) 0 else dpToPx(BANNER_BOTTOM_GAP_DP).toInt()
            banner.layoutParams = params
        }
        MGImages.setImage(
            view = banner,
            url = IconUtils.getBannerForGuild(
                guild,
                banner.resources.getDimensionPixelSize(R.dimen.nav_panel_width),
                guild?.canHaveAnimatedBanner() ?: false,
            ),
            changeDetector = bannerChangeDetector,
        )
    }

    /**
     * Over a banner the header is white with a drop shadow; once the toolbar collapses onto the
     * solid background it reverts to the theme's interactive colour and the scrim is hidden.
     */
    private fun configureHeaderColors(guild: Guild?, hasBanner: Boolean) {
        header.setTextColor(tintColor(requireContext(), guild, isCollapsed))
        header.setShadowLayer(
            BANNER_TEXT_SHADOW_RADIUS,
            BANNER_TEXT_SHADOW_DX,
            BANNER_TEXT_SHADOW_DY,
            if (hasBanner && !isCollapsed) {
                ContextCompat.getColor(requireContext(), R.color.black_alpha_80)
            } else {
                ThemedColor.resolve(requireContext(), R.attr.colorBackgroundSecondary, Color.WHITE)
            },
        )
        bannerForeground.visibility = if (hasBanner && !isCollapsed) View.VISIBLE else View.GONE
    }

    /** The guild badge sits before the name, the overflow chevron after it. */
    private fun configureHeaderIcons(guild: Guild?, isCollapsed: Boolean) {
        val badge = guildBadge(guild, appBarLayout.isCollapsed)
        val badgeDrawable =
            if (badge == 0) null else ContextCompat.getDrawable(requireContext(), badge)
        val moreRes = requireContext().getThemedDrawableRes(R.attr.ic_more)
        val moreDrawable = if (moreRes == 0) {
            null
        } else {
            ContextCompat.getDrawable(requireContext(), moreRes)
                ?.mutate()
                ?.also {
                    DrawableCompat.setTint(it, tintColor(requireContext(), guild, isCollapsed))
                }
        }
        header.setCompoundDrawablesWithIntrinsicBounds(badgeDrawable, null, moreDrawable, null)
    }

    @DrawableRes
    private fun guildBadge(guild: Guild?, collapsed: Boolean): Int = when {
        guild == null -> 0
        guild.hasFeature(GuildFeature.VERIFIED) ->
            if (collapsed) R.drawable.ic_verified_badge else R.drawable.ic_verified_badge_banner
        guild.hasFeature(GuildFeature.PARTNERED) ->
            if (collapsed) R.drawable.ic_partnered_badge else R.drawable.ic_partnered_badge_banner
        guild.premiumTier == 0 && guild.premiumSubscriptionCount > 0 ->
            if (collapsed) {
                R.drawable.ic_guild_nitro_progress
            } else {
                R.drawable.ic_guild_nitro_progress_banner
            }
        guild.premiumTier == 1 ->
            if (collapsed) {
                R.drawable.ic_guild_badge_premium_tier_1
            } else {
                R.drawable.ic_guild_badge_premium_tier_1_banner
            }
        guild.premiumTier == 2 ->
            if (collapsed) {
                R.drawable.ic_guild_badge_premium_tier_2
            } else {
                R.drawable.ic_guild_badge_premium_tier_2_banner
            }
        guild.premiumTier == 3 ->
            if (collapsed) {
                R.drawable.ic_guild_badge_premium_tier_3
            } else {
                R.drawable.ic_guild_badge_premium_tier_3_banner
            }
        else -> 0
    }

    @ColorInt
    private fun tintColor(context: Context, guild: Guild?, isCollapsed: Boolean): Int =
        if (guild?.banner != null && !isCollapsed) {
            ContextCompat.getColor(context, R.color.white)
        } else {
            ThemedColor.resolve(context, R.attr.colorInteractiveActive, Color.WHITE)
        }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    companion object {
        const val ANALYTICS_SOURCE = "Channels List"
        const val BANNER_TEXT_SHADOW_RADIUS = 1f
        const val BANNER_TEXT_SHADOW_DX = 0f
        const val BANNER_TEXT_SHADOW_DY = 4f

        private const val INTENT_EXTRA_OPEN_PANEL = "com.discord.intent.extra.EXTRA_OPEN_PANEL"
        private const val PANEL_CORNER_RADIUS_DP = 8f
        private const val BANNER_BOTTOM_GAP_DP = 8f

        /**
         * The last model rendered, disk-cached (same mechanism StoreGuilds/StoreChannels already
         * use for Guild/Channel) so a freshly (re)created view — including after the app process
         * itself was restarted — can paint immediately instead of sitting empty until the next
         * async emission. `set()` only marks this dirty; the existing app-wide persistence
         * schedule (PersisterConfig: on background, and every minute) flushes it to disk, so this
         * never does file IO on the main thread itself.
         */
        private val lastModelCache = Persister(
            "channels_list_last_model",
            WidgetChannelListModel(selectedGuild = null, items = emptyList()),
        )
    }
}

package com.discord.widgets.guilds.invite

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppViewFlipper
import com.discord.models.domain.ModelInvite
import com.discord.models.invite.InviteUtils
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.utilities.accessibility.AccessibilityUtils
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.takeSingleUntilTimeout
import com.discord.utilities.view.extensions.addBindedTextWatcher
import com.discord.utilities.view.extensions.fadeBy
import com.discord.views.CheckedSetting
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.discord.widgets.tabs.WidgetTabsHost
import com.google.android.material.textfield.TextInputLayout
import rx.Observable

/**
 * The full-screen "Invite People" flow: a searchable suggestion list, the current link, and a
 * settings sheet behind an edit affordance.
 *
 * `INTENT_IS_NUX_FLOW` changes two things and nothing else: the up affordance becomes a close
 * icon, and Back finishes onto the home surface instead of popping — this screen is the last step
 * of guild creation, so there is nothing behind it to go back to.
 */
class WidgetGuildInviteShare : AppFragment(R.layout.widget_guild_invite_share) {

    private lateinit var suggestionsFlipper: AppViewFlipper
    private lateinit var suggestionList: RecyclerView
    private lateinit var emptyResults: TextView
    private lateinit var searchInput: TextInputLayout
    private lateinit var inviteLinkView: TextView
    private lateinit var settingsEdit: ImageView
    private lateinit var neverExpire: CheckedSetting
    private lateinit var shareButton: MaterialButton
    private lateinit var bottomSheetTint: View
    private lateinit var settingsSheet: ViewInviteSettingsSheet

    private lateinit var adapter: PrivateChannelAdapter
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<ViewInviteSettingsSheet>

    /** The suggestion-viewed analytic fires once per screen, not once per emission. */
    private var hasTrackedSuggestionsViewed = false

    private val viewModel: WidgetGuildInviteShareViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WidgetGuildInviteShareViewModel(
                    guildId = mostRecentIntent.getLongExtra(EXTRA_GUILD_ID, 0L),
                    eventId = mostRecentIntent.longExtraOrNull(EXTRA_GUILD_SCHEDULED_EVENT_ID),
                    inviteStoreKey = mostRecentIntent.getStringExtra(INTENT_INVITE_STORE_KEY),
                    resources = resources,
                ) as T
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        suggestionsFlipper = view.findViewById(R.id.guild_invite_suggestions_flipper)
        suggestionList = view.findViewById(R.id.guild_invite_suggestion_list)
        emptyResults = view.findViewById(R.id.guild_invite_empty_results)
        searchInput = view.findViewById(R.id.guild_invite_share_search)
        inviteLinkView = view.findViewById(R.id.guild_invite_link)
        settingsEdit = view.findViewById(R.id.guild_invite_settings_edit)
        neverExpire = view.findViewById(R.id.guild_invite_never_expire)
        shareButton = view.findViewById(R.id.guild_invite_share_btn)
        bottomSheetTint = view.findViewById(R.id.bottom_sheet_tint)
        settingsSheet = view.findViewById(R.id.invite_settings_bottom_sheet)

        adapter = MGRecyclerAdapter.configure(PrivateChannelAdapter(suggestionList))

        val isNuxFlow = mostRecentIntent.getBooleanExtra(INTENT_IS_NUX_FLOW, false)
        val channelId = mostRecentIntent.getLongExtra(EXTRA_CHANNEL_ID, 0L)
        if (channelId != 0L) viewModel.selectChannel(channelId)

        setActionBarDisplayHomeAsUpEnabled(
            showHomeAsUp = true,
            iconRes = if (isNuxFlow) requireContext().getThemedDrawableRes(R.attr.ic_close_24dp) else null,
            iconAccessibilityLabel = if (isNuxFlow) R.string.close else null,
        )
        setActionBarTitle(R.string.invite_people)

        // The suggestion list is the scrolling surface; panning it under the IME would hide the
        // link footer, which is the whole point of the screen.
        appActivity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        setOnBackPressed({
            when {
                bottomSheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN -> {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                    true
                }
                // Nothing precedes the NUX invite step, so Back has to land on the home
                // surface with the panels closed, not pop into an empty back stack.
                isNuxFlow -> {
                    StoreStream.getTabsNavigation().selectHomeTab(StoreNavigation.PanelAction.CLOSE)
                    AppScreenRouter.launch(requireContext(), WidgetTabsHost::class.java)
                    true
                }
                else -> false
            }
        })

        searchInput.addBindedTextWatcher(this) { editable ->
            viewModel.updateSearchQuery(editable.toString())
        }

        bottomSheetBehavior = BottomSheetBehavior.from(settingsSheet)
        initBottomSheetBehavior(bottomSheetBehavior)
        initBottomSheet()

        settingsEdit.setOnClickListener {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        bottomSheetTint.setOnClickListener {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .filterIsLoaded()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildInviteShare::class.java) { configureUI(it) }
    }

    private fun initBottomSheet() {
        settingsSheet.viewModel = viewModel
        settingsSheet.onGenerateLinkListener = {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
    }

    private fun initBottomSheetBehavior(
        bottomSheetBehavior: BottomSheetBehavior<ViewInviteSettingsSheet>,
    ) {
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        bottomSheetBehavior.addBottomSheetCallback(
            object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit

                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    when (newState) {
                        // Collapsing discards uncommitted edits, so the screen has to re-render
                        // from the store even though nothing in the store changed.
                        BottomSheetBehavior.STATE_HIDDEN -> {
                            viewModel.refreshUi()
                            settingsEdit.sendAccessibilityEvent(
                                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                            )
                        }
                        BottomSheetBehavior.STATE_EXPANDED ->
                            settingsSheet.sendAccessibilityEvent(
                                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                            )
                    }
                    bottomSheetTint.fadeBy(newState != BottomSheetBehavior.STATE_HIDDEN)
                }
            },
        )
        bottomSheetBehavior.setUpdateImportantForAccessibilityOnSiblings(true)
    }

    private fun configureUI(viewState: WidgetGuildInviteShareViewModel.ViewState.Loaded) {
        val model = viewState.widgetInviteModel
        val invite = model.invite

        suggestionsFlipper.displayedChild =
            if (viewState.inviteSuggestionItems.isEmpty()) FLIPPER_EMPTY else FLIPPER_LIST

        // The "no search results" row is a placeholder, not a suggestion: it must not count as
        // content for the empty state, nor be reported to analytics.
        val items = viewState.inviteSuggestionItems
            .takeUnless { it.singleOrNull() === InviteSuggestionItem.SearchNoResultsItem }
            .orEmpty()

        if (items.isNotEmpty() && !hasTrackedSuggestionsViewed) {
            trackSuggestionsViewed(viewState, items)
            hasTrackedSuggestionsViewed = true
        }

        emptyResults.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        suggestionList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        adapter.setData(items)
        adapter.onClick = { item ->
            sendInvite(item, viewState)
            AccessibilityUtils.sendAnnouncement(
                requireContext(),
                getString(R.string.invite_sent),
            )
        }

        settingsSheet.configureUi(model)

        inviteLinkView.text = invite?.toLink(resources, InviteUtils.HOST_INVITE)
            ?: InviteUtils.HOST_INVITE
        inviteLinkView.setOnClickListener { view ->
            copyLinkClick(view.context, invite, mostRecentIntent)
        }

        neverExpire.setChecked(model.settings?.maxAge == ModelInvite.Settings.NEVER, false)
        neverExpire.setSubtext(getInviteLinkText(requireContext()), false)
        neverExpire.setContainerClickListener(
            View.OnClickListener {
                neverExpire.toggle()
                val settings = model.settings
                if (settings != null) {
                    val maxAge = if (neverExpire.isChecked()) {
                        ModelInvite.Settings.NEVER
                    } else {
                        ModelInvite.Settings.SEVEN_DAYS
                    }
                    viewModel.updateInviteSettings(settings.mergeMaxAge(maxAge))
                }
                model.targetChannel?.let { viewModel.generateInviteLink(it.id) }
            },
        )

        shareButton.setOnClickListener {
            invite?.let { shareLinkClick(context, it) }
        }

        settingsEdit.visibility = if (viewState.showInviteSettings) View.VISIBLE else View.GONE
        neverExpire.visibility = if (viewState.showInviteSettings) View.VISIBLE else View.GONE
    }

    private fun trackSuggestionsViewed(
        viewState: WidgetGuildInviteShareViewModel.ViewState.Loaded,
        items: List<InviteSuggestionItem>,
    ) {
        val channels = items.filterIsInstance<InviteSuggestionItem.ChannelItem>()
        val users = items.filterIsInstance<InviteSuggestionItem.UserItem>()
        AnalyticsTracker.inviteSuggestionOpened(
            guildId = viewState.widgetInviteModel.invite?.guild?.id ?: 0L,
            numSuggestions = items.size,
            numFriends = users.size,
            numDms = channels.count { it.channel.type == Channel.DM },
            numGroupDms = channels.count { it.channel.type == Channel.GROUP_DM },
        )
    }

    private fun sendInvite(
        item: InviteSuggestionItem,
        viewState: WidgetGuildInviteShareViewModel.ViewState.Loaded,
    ) = when (item) {
        is InviteSuggestionItem.ChannelItem ->
            viewModel.sendInviteToChannel(item.channel.id, viewState, viewState.widgetInviteModel.invite)
        is InviteSuggestionItem.UserItem ->
            viewModel.sendInviteToUser(item.user.id, viewState, viewState.widgetInviteModel.invite)
        InviteSuggestionItem.SearchNoResultsItem -> Unit
    }

    companion object {
        const val INTENT_INVITE_STORE_KEY = "INTENT_INVITE_STORE_KEY"
        const val INTENT_IS_NUX_FLOW = "INTENT_IS_NUX_FLOW"

        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_GUILD_SCHEDULED_EVENT_ID =
            "com.discord.intent.extra.EXTRA_GUILD_SCHEDULED_EVENT_ID"

        private const val FLIPPER_EMPTY = 0
        private const val FLIPPER_LIST = 1

        /**
         * Opens whichever invite surface fits.
         *
         * The reference races the compact-layout experiment and the suggestion list against a
         * 50 ms timeout and picks the destination from whichever answer arrives first, falling
         * back to the empty-suggestions screen on timeout. That race is reproduced, because the
         * timeout is what keeps the invite button responsive on a cold store.
         *
         * The bottom-sheet variant, when its experiment is on, replaces all three.
         */
        @JvmOverloads
        fun launch(
            context: Context,
            fragmentManager: FragmentManager,
            guildId: Long,
            channelId: Long? = null,
            isNuxFlow: Boolean = false,
            guildScheduledEventId: Long? = null,
            inviteStoreKey: String? = null,
            source: String,
        ) {
            // The reference opens `WidgetGuildInviteShareSheet` here instead; neither it nor
            // `GuildInviteShareSheetViewModel` is reconstructed yet.
            @Suppress("UNUSED_EXPRESSION")
            fragmentManager

            val arguments = Intent()
                .putExtra(INTENT_IS_NUX_FLOW, isNuxFlow)
                .putExtra(EXTRA_GUILD_ID, guildId)
                .putExtra(EXTRA_CHANNEL_ID, channelId ?: 0L)
                .putExtra(EXTRA_ORIGIN_SOURCE, source)
                .putExtra(EXTRA_GUILD_SCHEDULED_EVENT_ID, guildScheduledEventId ?: 0L)
                .putExtra(INTENT_INVITE_STORE_KEY, inviteStoreKey)

            Observable
                .combineLatest(
                    StoreStream.getExperiments()
                        .observeUserExperiment(COMPACT_LAYOUT_EXPERIMENT, trackExposure = true),
                    InviteSuggestionsManager().observeInviteSuggestions(),
                ) { experiment, suggestions -> experiment to suggestions }
                .computationLatest()
                .takeSingleUntilTimeout(DESTINATION_TIMEOUT_MS, emitError = true)
                .appSubscribe(
                    WidgetGuildInviteShare::class.java,
                    errorHandler = {
                        // Timed out waiting on the stores: the empty-suggestions screen is the one
                        // that needs no suggestion data to render.
                        AnalyticsTracker.openModal(INSTANT_INVITE_MODAL, source)
                        AppScreenRouter.launch(
                            context,
                            WidgetGuildInviteShareEmptySuggestions::class.java,
                            arguments,
                        )
                    },
                ) { (experiment, suggestions) ->
                    AnalyticsTracker.openModal(INSTANT_INVITE_MODAL, source)
                    val screen = when {
                        suggestions.isEmpty() -> WidgetGuildInviteShareEmptySuggestions::class.java
                        experiment?.bucket == 1 -> WidgetGuildInviteShareCompact::class.java
                        else -> WidgetGuildInviteShare::class.java
                    }
                    AppScreenRouter.launch(context, screen, arguments)
                }
        }

        private const val COMPACT_LAYOUT_EXPERIMENT = "2020-01_mobile_invite_suggestion_compact"
        private const val INSTANT_INVITE_MODAL = "Instant Invite Modal"
        private const val DESTINATION_TIMEOUT_MS = 50L
    }
}

/** `Intent.getLongExtra` that distinguishes "absent" from "zero". */
private fun Intent.longExtraOrNull(name: String): Long? =
    if (hasExtra(name)) getLongExtra(name, 0L).takeIf { it != 0L } else null

private fun Observable<WidgetGuildInviteShareViewModel.ViewState>.filterIsLoaded():
    Observable<WidgetGuildInviteShareViewModel.ViewState.Loaded> =
    filter { it is WidgetGuildInviteShareViewModel.ViewState.Loaded }
        .map { it as WidgetGuildInviteShareViewModel.ViewState.Loaded }

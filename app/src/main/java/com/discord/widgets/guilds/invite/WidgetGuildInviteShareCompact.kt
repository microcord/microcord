package com.discord.widgets.guilds.invite

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppViewFlipper
import com.discord.models.invite.InviteUtils
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.extensions.addBindedTextWatcher
import com.discord.utilities.view.extensions.fadeBy
import com.discord.widgets.tabs.WidgetTabsHost
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout

/**
 * The `2020-01_mobile_invite_suggestion_compact` variant of [WidgetGuildInviteShare].
 *
 * Same view model, same suggestion rows; the layout collapses the link footer into the Share
 * button itself — its *label* is the link — and turns the never-expire switch into a plain caption
 * that opens the settings sheet.
 *
 * The reference declares its own private copy of `PrivateChannelAdapter`, byte-identical to the
 * shared one. That duplicate is not reproduced.
 */
class WidgetGuildInviteShareCompact : AppFragment(R.layout.widget_guild_invite_share_compact) {

    private lateinit var suggestionsFlipper: AppViewFlipper
    private lateinit var suggestionList: RecyclerView
    private lateinit var emptyResults: TextView
    private lateinit var searchInput: TextInputLayout
    private lateinit var shareButton: MaterialButton
    private lateinit var settingsEdit: ImageView
    private lateinit var neverExpire: TextView
    private lateinit var bottomSheetTint: View
    private lateinit var settingsSheet: ViewInviteSettingsSheet

    private lateinit var adapter: PrivateChannelAdapter
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<ViewInviteSettingsSheet>

    private var hasTrackedSuggestionsViewed = false

    private val viewModel: WidgetGuildInviteShareViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WidgetGuildInviteShareViewModel(
                    guildId = mostRecentIntent.getLongExtra(EXTRA_GUILD_ID, 0L),
                    eventId = mostRecentIntent
                        .getLongExtra(EXTRA_GUILD_SCHEDULED_EVENT_ID, 0L)
                        .takeIf { it != 0L },
                    inviteStoreKey = mostRecentIntent
                        .getStringExtra(WidgetGuildInviteShare.INTENT_INVITE_STORE_KEY),
                    resources = resources,
                ) as T
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        suggestionsFlipper = view.findViewById(R.id.guild_invite_suggestions_flipper)
        suggestionList = view.findViewById(R.id.guild_invite_suggestion_list)
        emptyResults = view.findViewById(R.id.guild_invite_empty_results)
        searchInput = view.findViewById(R.id.guild_invite_share_compact_search)
        shareButton = view.findViewById(R.id.guild_invite_share_btn)
        settingsEdit = view.findViewById(R.id.guild_invite_settings_edit)
        neverExpire = view.findViewById(R.id.guild_invite_never_expire)
        bottomSheetTint = view.findViewById(R.id.bottom_sheet_tint)
        settingsSheet = view.findViewById(R.id.invite_settings_bottom_sheet)

        adapter = MGRecyclerAdapter.configure(PrivateChannelAdapter(suggestionList))

        val isNuxFlow = mostRecentIntent.getBooleanExtra(WidgetGuildInviteShare.INTENT_IS_NUX_FLOW, false)
        val channelId = mostRecentIntent.getLongExtra(EXTRA_CHANNEL_ID, 0L)
        if (channelId != 0L) viewModel.selectChannel(channelId)

        setActionBarDisplayHomeAsUpEnabled(
            showHomeAsUp = true,
            iconRes = if (isNuxFlow) requireContext().getThemedDrawableRes(R.attr.ic_close_24dp) else null,
            iconAccessibilityLabel = if (isNuxFlow) R.string.close else null,
        )
        setActionBarTitle(R.string.invite_people)

        setOnBackPressed({
            when {
                bottomSheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN -> {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                    true
                }
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
        settingsSheet.viewModel = viewModel
        settingsSheet.onGenerateLinkListener = {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }

        // Both the caption and the edit icon open the sheet; the caption is the only affordance a
        // pointer-less user can reach without hunting for the icon.
        neverExpire.setOnClickListener {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
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
            .filter { it is WidgetGuildInviteShareViewModel.ViewState.Loaded }
            .map { it as WidgetGuildInviteShareViewModel.ViewState.Loaded }
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildInviteShareCompact::class.java) { configureUI(it) }
    }

    private fun initBottomSheetBehavior(
        bottomSheetBehavior: BottomSheetBehavior<ViewInviteSettingsSheet>,
    ) {
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        bottomSheetBehavior.addBottomSheetCallback(
            object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit

                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    if (newState == BottomSheetBehavior.STATE_HIDDEN) viewModel.refreshUi()
                    bottomSheetTint.fadeBy(newState != BottomSheetBehavior.STATE_HIDDEN)
                }
            },
        )
    }

    private fun configureUI(viewState: WidgetGuildInviteShareViewModel.ViewState.Loaded) {
        val model = viewState.widgetInviteModel
        val invite = model.invite

        suggestionsFlipper.displayedChild =
            if (viewState.inviteSuggestionItems.isEmpty()) FLIPPER_EMPTY else FLIPPER_LIST

        val items = viewState.inviteSuggestionItems
            .takeUnless { it.singleOrNull() === InviteSuggestionItem.SearchNoResultsItem }
            .orEmpty()

        if (items.isNotEmpty() && !hasTrackedSuggestionsViewed) {
            val channels = items.filterIsInstance<InviteSuggestionItem.ChannelItem>()
            AnalyticsTracker.inviteSuggestionOpened(
                guildId = invite?.guild?.id ?: 0L,
                numSuggestions = items.size,
                numFriends = items.count { it is InviteSuggestionItem.UserItem },
                numDms = channels.count { it.channel.type == Channel.DM },
                numGroupDms = channels.count { it.channel.type == Channel.GROUP_DM },
            )
            hasTrackedSuggestionsViewed = true
        }

        emptyResults.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        suggestionList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        adapter.setData(items)
        adapter.onClick = { item ->
            when (item) {
                is InviteSuggestionItem.ChannelItem ->
                    viewModel.sendInviteToChannel(item.channel.id, viewState, invite)
                is InviteSuggestionItem.UserItem ->
                    viewModel.sendInviteToUser(item.user.id, viewState, invite)
                InviteSuggestionItem.SearchNoResultsItem -> Unit
            }
        }

        settingsSheet.configureUi(model)

        // The Share button doubles as the link display in this layout.
        shareButton.text = invite?.toLink(resources, InviteUtils.HOST_INVITE) ?: InviteUtils.HOST_INVITE
        shareButton.setOnClickListener { invite?.let { shareLinkClick(context, it) } }

        settingsEdit.visibility = if (viewState.showInviteSettings) View.VISIBLE else View.GONE
        neverExpire.visibility = if (viewState.showInviteSettings) View.VISIBLE else View.GONE
        neverExpire.text = getInviteLinkText(requireContext(), viewModel.getDefaultInviteExperiment())
    }

    private companion object {
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_GUILD_SCHEDULED_EVENT_ID =
            "com.discord.intent.extra.EXTRA_GUILD_SCHEDULED_EVENT_ID"

        private const val FLIPPER_EMPTY = 0
        private const val FLIPPER_LIST = 1
    }
}

package com.discord.widgets.friends

import android.content.Context
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.Menu
import android.view.View
import android.widget.PopupMenu
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppViewFlipper
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.analytics.Traits
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rest.RestAPIAbortMessages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.rx.ui
import com.discord.widgets.channels.invite.GroupInviteFriendsSheet
import com.discord.widgets.contact_sync.ContactSyncFlowAnalytics
import com.discord.widgets.contact_sync.WidgetContactSync
import com.discord.widgets.tabs.NavigationTab
import com.discord.widgets.user.calls.PrivateCallLauncher
import com.discord.widgets.user.usersheet.WidgetUserSheet

/**
 * The Friends tab.
 *
 * All state comes from [FriendsListViewModel]; this class only renders it, routes row callbacks
 * back into the view model, and performs the navigation the view model cannot do itself (opening a
 * profile sheet, launching a call, starting the contact-sync flow).
 *
 * The three-child flipper is the reference's: recycler, empty state, loading skeleton.
 */
class WidgetFriendsList : AppFragment(R.layout.widget_friends_list) {

    private val viewModel: FriendsListViewModel by viewModels()

    private lateinit var adapter: WidgetFriendsListAdapter
    private lateinit var loadingAdapter: WidgetFriendsListLoadingAdapter
    private lateinit var privateCallLauncher: PrivateCallLauncher

    /**
     * Mirrors the current view state so [configureToolbar] can be re-run on tab selection, when no
     * view state is being delivered.
     */
    private var showContactSyncIcon = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        privateCallLauncher = PrivateCallLauncher(this, this, requireContext(), parentFragmentManager)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)

        view.findViewById<EmptyFriendsStateView>(R.id.friends_list_empty_friends_state_view)
            .updateView(ANALYTICS_SOURCE)

        adapter = MGRecyclerAdapter.configure(
            WidgetFriendsListAdapter(view.findViewById(R.id.friends_list_recycler)),
        )
        adapter.onClickUserProfile = { _, user ->
            WidgetUserSheet.show(parentFragmentManager, user.id)
        }
        adapter.onClickPendingHeaderExpand = { viewModel.handleClickPendingHeader() }
        adapter.onClickSuggestedHeaderExpandCollapse = { viewModel.handleClickSuggestedHeader() }
        adapter.onClickCall = { user -> viewModel.launchVoiceCall(user.id) }
        adapter.onClickChat = { user ->
            ChannelSelector.getInstance().findAndSetDirectMessage(requireContext(), user.id) {
                StoreStream.getTabsNavigation().selectHomeTab(StoreNavigation.PanelAction.CLOSE)
            }
        }
        adapter.onClickAcceptFriend = { user ->
            viewModel.acceptFriendRequest(user.id, user.username)
        }
        adapter.onClickDeclineFriend = { user, relationshipType ->
            viewModel.removeFriendRequest(user.id, relationshipType)
        }
        adapter.onClickApproveSuggestion = { user ->
            viewModel.acceptFriendSuggestion(user.username, user.discriminator)
        }
        adapter.onClickRemoveSuggestion = { userId -> viewModel.ignoreSuggestion(userId) }
        adapter.onClickContactSyncUpsell = {
            launchContactSync(requireContext(), LOCATION_UPSELL)
        }
        adapter.onClickContactSyncUpsellLongClick = { upsellView -> showUpsellDismissMenu(upsellView) }

        loadingAdapter = WidgetFriendsListLoadingAdapter()
        view.findViewById<RecyclerView>(R.id.friends_list_loading).adapter = loadingAdapter
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .ui(this)
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetFriendsList::class.java) { configureUI(it) }
        viewModel.observeEvents()
            .ui(this)
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetFriendsList::class.java) { handleEvent(it) }

        // The reference registers an `OnTabSelectedListener` with `WidgetTabsHost`. That interface
        // does not exist here and the host keeps every tab's fragment resumed, only toggling
        // container visibility — so nothing would re-run when the user comes back to this tab. The
        // selection is observed directly instead, which produces the same single call per switch.
        StoreStream.getTabsNavigation().observeSelectedTab()
            .distinctUntilChanged()
            .ui(this)
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetFriendsList::class.java) { tab ->
                if (tab == NavigationTab.FRIENDS) onTabSelected()
            }
    }

    /**
     * Runs when the Friends tab becomes the visible one. The toolbar is shared between tabs, so it
     * has to be re-inflated here rather than only on bind.
     */
    fun onTabSelected() {
        configureToolbar()
        AnalyticsTracker.friendsListViewed()
        setActionBarTitleAccessibilityViewFocused()
    }

    // region rendering

    private fun configureUI(viewState: FriendsListViewModel.ViewState) = when (viewState) {
        is FriendsListViewModel.ViewState.Uninitialized -> displayFlipperChild(VIEW_INDEX_LOADING)
        is FriendsListViewModel.ViewState.Empty -> {
            displayFlipperChild(VIEW_INDEX_EMPTY)
            showContactSyncIcon = viewState.showContactSyncIcon
            configureToolbar()
        }
        is FriendsListViewModel.ViewState.Loaded -> {
            displayFlipperChild(VIEW_INDEX_RECYCLER)
            adapter.setData(viewState.items)
            showContactSyncIcon = viewState.showContactSyncIcon
            configureToolbar()
        }
    }

    private fun displayFlipperChild(index: Int) {
        val flipper = view?.findViewById<AppViewFlipper>(R.id.friends_list_flipper) ?: return
        flipper.visibility = View.VISIBLE
        flipper.displayedChild = index
    }

    private fun configureToolbar() {
        bindToolbar()
        setActionBarTitle(R.string.friends)
        setActionBarTitleLayoutMinimumTappableArea()
        setActionBarOptionsMenu(
            R.menu.menu_friends,
            onSelected = { menuItem ->
                when (menuItem.itemId) {
                    R.id.menu_friends_add_friend ->
                        WidgetFriendsAdd.show(requireContext(), source = ANALYTICS_SOURCE)
                    R.id.menu_friends_contact_sync ->
                        launchContactSync(requireContext(), LOCATION_ICON)
                    R.id.menu_friends_start_group ->
                        GroupInviteFriendsSheet.show(parentFragmentManager, source = ANALYTICS_SOURCE)
                    else -> Unit
                }
            },
            onConfigured = { menu: Menu ->
                menu.findItem(R.id.menu_friends_contact_sync)?.isVisible = showContactSyncIcon
                menu.findItem(R.id.menu_friends_start_group)?.isVisible = true
            },
        )
    }

    // endregion

    // region events

    private fun handleEvent(event: FriendsListViewModel.Event) = when (event) {
        is FriendsListViewModel.Event.ShowToast ->
            showToast(getString(event.stringRes))
        is FriendsListViewModel.Event.ShowFriendRequestErrorToast ->
            showToast(
                RestAPIAbortMessages.ResponseResolver.getRelationshipResponse(
                    context,
                    event.abortCode,
                    event.username,
                ),
            )
        is FriendsListViewModel.Event.LaunchVoiceCall ->
            privateCallLauncher.launchVoiceCall(event.channelId)
    }

    private fun showToast(text: CharSequence?) {
        val context = context ?: return
        if (text.isNullOrBlank()) return
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    // endregion

    private fun launchContactSync(context: Context, locationPage: String) {
        ContactSyncFlowAnalytics.trackStart(
            additionalProps = mapOf("location_page" to locationPage),
        )
        AnalyticsTracker.openModal("Contact Sync", Traits.Location(page = locationPage))
        WidgetContactSync.launch(context)
    }

    private fun showUpsellDismissMenu(upsellView: View) {
        val popupMenu = PopupMenu(
            ContextThemeWrapper(upsellView.context, R.style.AppTheme_Dark),
            upsellView,
        )
        popupMenu.menuInflater.inflate(R.menu.menu_contact_sync_upsell, popupMenu.menu)
        popupMenu.menu.findItem(R.id.menu_contact_sync_upsell_dismiss)
            .setOnMenuItemClickListener {
                viewModel.dismissContactSyncUpsell()
                true
            }
        popupMenu.show()
    }

    companion object {
        private const val ANALYTICS_SOURCE = "Friends"
        private const val LOCATION_ICON = "Friends List Icon"
        private const val LOCATION_UPSELL = "Friends List Upsell"

        private const val VIEW_INDEX_RECYCLER = 0
        private const val VIEW_INDEX_EMPTY = 1
        private const val VIEW_INDEX_LOADING = 2
    }
}

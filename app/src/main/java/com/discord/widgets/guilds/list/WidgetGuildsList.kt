package com.discord.widgets.guilds.list

import com.discord.widgets.settings.SettingsStrings
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.rest.RestAPI
import com.discord.models.guild.GeoRestrictedGuild
import android.content.Context
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.i18n.i18nRenderPlural
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.widgets.channels.list.WidgetChannelListUnreads
import com.discord.widgets.guilds.create.CreateGuildTrigger
import com.discord.widgets.guilds.contextmenu.WidgetFolderContextMenu
import com.discord.widgets.guilds.contextmenu.WidgetGuildContextMenu
import com.discord.widgets.nux.WidgetGuildTemplates
import com.discord.widgets.tabs.TabsHostBottomNavigationView

/**
 * The guild sidebar.
 *
 * All state lives in [WidgetGuildsListViewModel]; this fragment only inflates, forwards gestures
 * to it, and turns its one-shot [WidgetGuildsListViewModel.Event]s into navigation. The two
 * exceptions are deliberate: a long press needs the touch point to anchor a context menu, and the
 * drag preview needs the item animator switched off so the recycler does not animate over it.
 */
class WidgetGuildsList : AppFragment(R.layout.widget_guilds_list), WidgetGuildListAdapter.InteractionListener {

    private val viewModel: WidgetGuildsListViewModel by viewModels()

    private lateinit var adapter: WidgetGuildListAdapter
    private lateinit var guildList: RecyclerView
    private var guildListUnreads: WidgetChannelListUnreads? = null
    private var unreadsStub: ViewStub? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        guildList = view.findViewById(R.id.guild_list)
        unreadsStub = view.findViewById(R.id.guild_list_unreads_stub)
        setupRecycler()

        guildListUnreads = WidgetChannelListUnreads(
            unreadsStub = requireNotNull(unreadsStub),
            recycler = guildList,
            getItemCount = { adapter.itemCount },
            // The sidebar only ever jumps to mentions; a merely-unread guild is not worth a pill.
            mentionResId = R.string._new,
            unreadsEnabled = false,
        )

        configureBottomNavSpace()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        configureBottomNavSpace()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildsList::class.java) { configureUI(it) }
        viewModel.listenForEvents()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildsList::class.java) { handleEvent(it) }
    }

    private fun setupRecycler() {
        val layoutManager = LinearLayoutManager(guildList.context, RecyclerView.VERTICAL, false)
        adapter = WidgetGuildListAdapter(layoutManager, this)
        adapter.setHasStableIds(true)

        // A drag rewrites the whole list on every frame; the default animator would cross-fade
        // each of those rewrites over the row the finger is holding.
        guildList.itemAnimator = null
        guildList.layoutManager = layoutManager
        guildList.adapter = adapter

        ItemTouchHelper(GuildsDragAndDropCallback(adapter)).attachToRecyclerView(guildList)

        guildList.addItemDecoration(
            FolderItemDecoration(
                drawableNoChildren = themedDrawable(R.attr.bg_folder_no_children),
                tintableDrawableNoChildren = themedDrawable(R.attr.bg_folder_tintable_no_children),
                drawableWithChildren = themedDrawable(R.attr.bg_folder_with_children),
                sizePx = resources.getDimensionPixelSize(R.dimen.avatar_size_large),
            ),
        )
    }

    private fun configureUI(viewState: WidgetGuildsListViewModel.ViewState) {
        if (viewState !is WidgetGuildsListViewModel.ViewState.Loaded) return
        adapter.setItems(viewState.items, calculateDiff = !viewState.wasDragResult)
        guildListUnreads?.onDatasetChanged(viewState.items)
    }

    private fun handleEvent(event: WidgetGuildsListViewModel.Event) = when (event) {
        is WidgetGuildsListViewModel.Event.ShowChannelActions ->
            GuildsListNavigation.showChannelActions(parentFragmentManager, event.channelId)
        is WidgetGuildsListViewModel.Event.ShowUnavailableGuilds ->
            showUnavailableGuildsToast(event.unavailableGuildCount)
        is WidgetGuildsListViewModel.Event.ShowGeoRestrictedGuild ->
            showGeoRestrictedDialog(event.guild)
        is WidgetGuildsListViewModel.Event.AnnounceFolderToggleForAccessibility ->
            announceFolderToggle(event.item)
        WidgetGuildsListViewModel.Event.ShowCreateGuild ->
            GuildsListNavigation.showCreateGuild(requireContext())
        WidgetGuildsListViewModel.Event.ShowDiscovery ->
            GuildsListNavigation.showDiscovery(requireContext())
        WidgetGuildsListViewModel.Event.ShowHelp ->
            GuildsListNavigation.showNavigationHelp(parentFragmentManager)
        WidgetGuildsListViewModel.Event.FocusFirstElement -> focusFirstElement()
        WidgetGuildsListViewModel.Event.ShowHubUnavailable ->
            Toast.makeText(context, R.string.discord_u_coming_soon_to_mobile, Toast.LENGTH_SHORT).show()
    }

    /**
     * TalkBack does not report a collapse/expand that only changes rows further down the list, so
     * the folder announces the new state itself.
     */
    private fun announceFolderToggle(item: GuildListItem.FolderItem) {
        val state = getString(if (item.isOpen) R.string.collapsed else R.string.expanded)
        view?.announceForAccessibility("$state, ${item.name.orEmpty()}")
    }

    private fun focusFirstElement() {
        guildList.getChildAt(0)?.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    /**
     * A region-blocked guild cannot be opened, so the row explains why and offers the only action
     * left: leaving it.
     */
    private fun showGeoRestrictedDialog(guild: GeoRestrictedGuild) {
        val context = requireContext()
        WidgetNoticeDialog.Builder(context)
            .setTitle(R.string.geo_restricted_modal_title)
            .setMessage(
                SettingsStrings.render(context, R.string.geo_restricted_modal_body, "serverName" to guild.name),
            )
            .setNegativeButton(R.string.leave_server) {
                RestAPI.api.leaveGuild(guild.id)
                    .restSubscribeOn(retry = false)
                    .ui(this)
                    .appSubscribe(javaClass) { }
            }
            .setPositiveButton(R.string.okay)
            .show(parentFragmentManager)
    }

    private fun showUnavailableGuildsToast(unavailableGuildCount: Int) {
        val message = requireContext().i18nRenderPlural(
            R.plurals.partial_outage_count,
            unavailableGuildCount,
            "count" to unavailableGuildCount,
        )
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Pads the end of the list by the tab bar's height so the last guild stays reachable.
     *
     * The reference observes the bar through `BottomNavViewObserver` and reacts to every height
     * change. `TabsHostBottomNavigationView` here only offers `addHeightChangedListener` with no
     * matching remove, so registering one from a fragment would leak it; the height is sampled on
     * each resume instead. **Switch to the observer once it exists in `widgets/tabs`.**
     */
    private fun configureBottomNavSpace() {
        val navView: TabsHostBottomNavigationView =
            activity?.findViewById(R.id.widget_tabs_host_bottom_navigation_view) ?: return
        navView.post {
            if (!::adapter.isInitialized) return@post
            val height = navView.height
            adapter.handleBottomNavHeight(height)
            unreadsStub?.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = height }
        }
    }

    override fun onDestroyView() {
        guildListUnreads = null
        unreadsStub = null
        super.onDestroyView()
    }

    private fun themedDrawable(@AttrRes attribute: Int): Drawable {
        val resourceId = requireContext().getThemedDrawableRes(attribute)
        return requireNotNull(ContextCompat.getDrawable(requireContext(), resourceId)) {
            "no drawable for attribute ${resources.getResourceName(attribute)}"
        }
    }

    // region WidgetGuildListAdapter.InteractionListener

    override fun onItemClicked(view: View, item: GuildListItem) {
        viewModel.onItemClicked(item, requireContext(), parentFragmentManager)
    }

    override fun onItemLongPressed(view: View, item: GuildListItem) {
        if (item !is GuildListItem.GuildItem && item !is GuildListItem.FolderItem) {
            viewModel.onItemLongPressed(item)
            return
        }
        // The menu hangs off the right edge of the one-icon-wide sidebar, so it is anchored a
        // row's width to the right of the row that was pressed.
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val anchor = PointF(location[0] + dpToPixels(CONTEXT_MENU_X_OFFSET_DP), location[1].toFloat())
        when (item) {
            is GuildListItem.GuildItem -> WidgetGuildContextMenu.show(requireActivity(), anchor, item.guild.id)
            is GuildListItem.FolderItem -> WidgetFolderContextMenu.show(requireActivity(), anchor, item.folderId)
            else -> Unit
        }
    }

    /** A context menu anchored to a row that is moving would point at the wrong guild. */
    override fun onItemMoved() {
        WidgetGuildContextMenu.hide(requireActivity(), animate = true)
        WidgetFolderContextMenu.hide(requireActivity(), animate = true)
    }

    override fun onOperation(operation: WidgetGuildListAdapter.Operation) = when (operation) {
        is WidgetGuildListAdapter.Operation.MoveAbove ->
            viewModel.moveAbove(operation.fromPosition, operation.targetPosition)
        is WidgetGuildListAdapter.Operation.MoveBelow ->
            viewModel.moveBelow(operation.fromPosition, operation.targetPosition)
        is WidgetGuildListAdapter.Operation.TargetOperation ->
            viewModel.target(operation.fromPosition, operation.targetPosition)
    }

    override fun onDrop(): Boolean = viewModel.onDrop()

    // endregion

    private fun dpToPixels(dp: Int): Float = dp * resources.displayMetrics.density

    private companion object {
        private const val CONTEXT_MENU_X_OFFSET_DP = 64
    }
}

/**
 * Sidebar destinations that do not exist in this project yet.
 *
 * `WidgetNavigationHelp` and `WidgetChannelsListItemChannelActions` have not been reconstructed, so
 * the "?" and DM long-press routes have nowhere to go. Install those callbacks from app start-up
 * and delete each field as its screen lands.
 */
object GuildsListNavigation {

    fun showCreateGuild(context: Context) =
        WidgetGuildTemplates.launch(context, CreateGuildTrigger.IN_APP, false)

    /** `WidgetNavigationHelp.show(fragmentManager)`. */
    @Volatile
    var showNavigationHelp: (FragmentManager) -> Unit = {}

    /** `WidgetChannelsListItemChannelActions.show(fragmentManager, channelId)`. */
    @Volatile
    var showChannelActions: (FragmentManager, Long) -> Unit = { _, _ -> }

    @Volatile
    var showDiscovery: (Context) -> Unit = {}
}

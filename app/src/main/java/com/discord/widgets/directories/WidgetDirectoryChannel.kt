package com.discord.widgets.directories

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.viewpager2.widget.ViewPager2
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appActivityViewModels
import com.discord.i18n.i18nFormat
import com.discord.models.guild.Guild
import com.discord.stores.StoreDirectories
import com.discord.stores.StoreStream
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.views.ChatActionItem
import com.discord.views.directories.ServerDiscoveryHeader
import com.discord.widgets.hubs.toArgsIntent
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * The screen a `Channel.DIRECTORY` (type 14) channel opens.
 *
 * A directory channel holds no messages, so before this existed selecting one drove the ordinary
 * chat surface and the user landed on a permanently empty conversation. It shows, instead, the
 * hub's server directory: a search header, a category tab strip, and one
 * [WidgetDirectoryCategory] page per tab.
 *
 * Its four render states come from [WidgetDirectoriesViewModel.ViewState.loadState]:
 * - **Loading** — the entry fetch is in flight; the copy card says so and offers nothing to tap.
 * - **Content** — header, tab strip and pager.
 * - **Empty** — the hub has no servers yet: the reference's welcome copy plus the two action cards.
 * - **Error** — the fetch failed; the card becomes a retry.
 */
class WidgetDirectoryChannel : AppFragment(R.layout.widget_directory_channel) {

    private val viewModel: WidgetDirectoriesViewModel by appActivityViewModels {
        WidgetDirectoriesViewModel()
    }

    // Rebuilt with each view: `FragmentStateAdapter` asserts it is attached to exactly one
    // RecyclerView, so an instance cannot be carried across a view recreation.
    private lateinit var pagerAdapter: WidgetDirectoryChannelViewPagerAdapter

    private val addServerLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.retry() }

    private lateinit var header: ServerDiscoveryHeader
    private lateinit var toolbar: Toolbar
    private lateinit var tabs: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var emptyContainer: View
    private lateinit var emptyHeader: TextView
    private lateinit var emptySubheader: TextView
    private lateinit var addServerCard: ChatActionItem
    private lateinit var inviteFriendsCard: ChatActionItem

    private var tabMediator: TabLayoutMediator? = null

    /** 48dp of breathing room under the collapsing header, applied only while tabs are visible. */
    private val tabLayoutMargins: Int
        get() = (TAB_LAYOUT_MARGINS_DP * resources.displayMetrics.density).toInt()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        header = view.findViewById(R.id.header)
        toolbar = view.findViewById(R.id.action_bar_toolbar)
        tabs = view.findViewById(R.id.tabs)
        viewPager = view.findViewById(R.id.view_pager)
        emptyContainer = view.findViewById(R.id.widget_directory_channel_empty)
        emptyHeader = emptyContainer.findViewById(R.id.item_directory_empty_header)
        emptySubheader = emptyContainer.findViewById(R.id.item_directory_empty_subheader)
        addServerCard = emptyContainer.findViewById(R.id.welcome_card_add_server)
        inviteFriendsCard = emptyContainer.findViewById(R.id.welcome_card_invite_friends)

        header.setTitle(getString(R.string.hub_directory_search_title))
        header.setDescription(getString(R.string.hub_directory_search_subtitle))

        pagerAdapter = WidgetDirectoryChannelViewPagerAdapter(this)
        viewPager.adapter = pagerAdapter
        tabMediator = TabLayoutMediator(tabs, viewPager, ::configureTab).also { it.attach() }
    }

    override fun onDestroyView() {
        tabMediator?.detach()
        tabMediator = null
        super.onDestroyView()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .ui(this)
            .appSubscribe(WidgetDirectoryChannel::class.java) { configureUi(it) }
    }

    private fun configureTab(tab: TabLayout.Tab, position: Int) {
        val (category, count) = viewModel.viewState?.mappedTabs?.getOrNull(position) ?: return
        val tabView = layoutInflater.inflate(R.layout.directory_channel_tab, tabs, false) as TextView
        tabView.text = if (count > 0) "${category.getTitle(requireContext())} ($count)" else category.getTitle(requireContext())
        tab.customView = tabView
    }

    private fun configureUi(state: WidgetDirectoriesViewModel.ViewState) {
        configureSearchHeader(state.channel)
        configureEmptyCards(state)
        maybePromptForHubName(state.guild)

        val hasContent = state.loadState == DirectoryLoadState.Content
        header.visibility = if (hasContent) View.VISIBLE else View.GONE
        viewPager.visibility = if (hasContent) View.VISIBLE else View.GONE
        emptyContainer.visibility = if (hasContent) View.GONE else View.VISIBLE

        val showTabs = hasContent && state.mappedTabs.size > 1
        toggleHeaderMargins(showTabs)
        tabs.visibility = if (showTabs) View.VISIBLE else View.GONE

        pagerAdapter.tabs = state.mappedTabs.map { it.first }
    }

    private fun maybePromptForHubName(guild: Guild?) {
        if (!isVisible || guild?.isHub() != true) return
        val me = StoreStream.getUsers().getMe()
        val member = StoreStream.getGuilds().getMember(guild.id, me.id) ?: return
        val nickname = member.nick
        if (!nickname.isNullOrBlank()) return
        val wasSeen = (DirectoryStores.directories as StoreDirectories).getAndSetSeenNamePrompt(guild.id)
        if (shouldPromptForHubName(nickname, wasSeen)) {
            DirectoryNavigation.promptForHubName?.invoke(requireContext(), guild.id)
        }
    }

    private fun configureSearchHeader(channel: Channel?) {
        header.setButtonOnClickListener(
            if (channel == null) {
                null
            } else {
                View.OnClickListener { view ->
                    AppScreenRouter.launch(
                        view.context,
                        WidgetDirectoriesSearch::class.java,
                        DirectoriesSearchArgs(channel.id).toArgsIntent(),
                    )
                }
            },
        )
    }

    /**
     * The empty container doubles as the loading and error surface: `widget_directory_channel.xml`
     * carries no separate spinner or error view, and inventing resource ids for one is not allowed.
     * The three states are told apart by their copy and by which action cards are offered.
     */
    private fun configureEmptyCards(state: WidgetDirectoriesViewModel.ViewState) {
        when (state.loadState) {
            DirectoryLoadState.Content -> return
            DirectoryLoadState.Loading -> {
                emptyHeader.text = getString(R.string.loading)
                emptySubheader.text = ""
                emptyContainer.setOnClickListener(null)
                addServerCard.visibility = View.GONE
                inviteFriendsCard.visibility = View.GONE
            }
            DirectoryLoadState.Error -> {
                emptyHeader.text = getString(R.string.network_error_unknown)
                emptySubheader.text = getString(R.string.try_again)
                emptyContainer.setOnClickListener { viewModel.retry() }
                addServerCard.visibility = View.GONE
                inviteFriendsCard.visibility = View.GONE
            }
            DirectoryLoadState.Empty -> {
                val guild = state.guild
                emptyHeader.text = guild?.let {
                    requireContext().i18nFormat(
                        R.string.hub_directory_channel_empty_title,
                        "guildName" to it.name,
                    )
                }.orEmpty()
                emptySubheader.text = getString(R.string.hub_directory_channel_empty_subtitle)
                emptyContainer.setOnClickListener(null)

                addServerCard.visibility =
                    if (DirectoryNavigation.addServerToHub != null) View.VISIBLE else View.GONE
                addServerCard.setOnClickListener {
                    DirectoryNavigation.addServerToHub?.invoke(requireContext(), addServerLauncher)
                }

                // Inviting people to the hub needs `widgets/guilds/invite/WidgetGuildInviteShare`,
                // which does not exist yet; the card is hidden rather than leading nowhere.
                inviteFriendsCard.visibility =
                    if (guild != null && DirectoryNavigation.inviteToHub != null) View.VISIBLE else View.GONE
                inviteFriendsCard.setOnClickListener {
                    guild ?: return@setOnClickListener
                    DirectoryNavigation.inviteToHub?.invoke(this@WidgetDirectoryChannel, guild.id)
                }
            }
        }
    }

    private fun toggleHeaderMargins(showTabLayout: Boolean) {
        val margin = if (showTabLayout) tabLayoutMargins else 0
        (header.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.bottomMargin = margin
            header.layoutParams = it
        }
        (toolbar.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.bottomMargin = margin
            toolbar.layoutParams = it
        }
    }

    private companion object {
        private const val TAB_LAYOUT_MARGINS_DP = 48
    }
}

internal fun shouldPromptForHubName(
    nickname: String?,
    wasSeen: Boolean,
    killSwitchEnabled: Boolean = false,
): Boolean = nickname.isNullOrBlank() && !wasSeen && !killSwitchEnabled

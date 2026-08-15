package com.discord.widgets.tabs

import android.content.Context
import android.content.res.ColorStateList
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import com.discord.R
import com.discord.databinding.TabsHostBottomNavigationViewBinding
import com.discord.plugins.OwnedBottomTabContribution
import com.discord.plugins.PluginUiRegistry
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.feature.PluginSurfaceCustomization
import com.discord.utilities.feature.PluginSurfaceCustomization.Surface
import com.discord.views.user.UserAvatarPresenceView
import com.discord.views.user.UserAvatarPresenceViewController
import dev.mantikafasi.manticord.plugins.api.PluginBottomTabContext
import dev.mantikafasi.manticord.plugins.api.pluginBottomTabContext

class TabsHostBottomNavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    fun interface HeightChangedListener { fun onHeightChanged(height: Int) }

    private val binding = TabsHostBottomNavigationViewBinding.inflate(LayoutInflater.from(context), this, true)
    private val tabsContainer: LinearLayout
    private val homeBadge: TextView
    private val friendsBadge: TextView
    private val avatar: UserAvatarPresenceView
    private val navigationTabToViewMap: Map<NavigationTab, View>
    private val iconToNavigationTabMap: Map<View, NavigationTab>
    private val tintableIconToNavigationTabMap: Map<ImageView, NavigationTab>
    private val heightChangedListeners = linkedSetOf<HeightChangedListener>()
    private val pluginViews = mutableMapOf<String, View>()

    /**
     * Drives the settings-tab avatar from the stores rather than from a snapshot, so the picture and
     * the presence dot follow the user's own status changes without the host re-rendering.
     */
    private val userAvatarPresenceViewController: UserAvatarPresenceViewController

    private var selectedTab = NavigationTab.HOME
    private var visibleTabs: Set<NavigationTab> = NavigationTab.entries.toSet()
    private var homeNotificationsCount = 0
    private var friendsNotificationsCount = 0

    init {
        orientation = VERTICAL
        tabsContainer = binding.tabsHostBottomNavTabsContainer
        homeBadge = binding.tabsHostBottomNavHomeNotificationsBadge
        friendsBadge = binding.tabsHostBottomNavFriendsNotificationsBadge
        avatar = binding.tabsHostBottomNavUserAvatarPresenceView
        val homeIcon = binding.tabsHostBottomNavHomeIcon
        val friendsIcon = binding.tabsHostBottomNavFriendsIcon
        val searchIcon = binding.tabsHostBottomNavSearchIcon
        val mentionsIcon = binding.tabsHostBottomNavMentionsIcon
        val homeItem = binding.tabsHostBottomNavHomeItem
        val friendsItem = binding.tabsHostBottomNavFriendsItem
        val searchItem = binding.tabsHostBottomNavSearchItem
        val mentionsItem = binding.tabsHostBottomNavMentionsItem
        val settingsItem = binding.tabsHostBottomNavUserSettingsItem
        val displaySize = if (resources.configuration.orientation == 1) {
            resources.displayMetrics.widthPixels
        } else {
            resources.displayMetrics.heightPixels
        }
        tabsContainer.layoutParams = tabsContainer.layoutParams.apply { width = displaySize }
        tintableIconToNavigationTabMap = mapOf(
            homeIcon to NavigationTab.HOME,
            friendsIcon to NavigationTab.FRIENDS,
            searchIcon to NavigationTab.SEARCH,
            mentionsIcon to NavigationTab.MENTIONS,
        )
        iconToNavigationTabMap = tintableIconToNavigationTabMap + (avatar to NavigationTab.SETTINGS)
        navigationTabToViewMap = mapOf(
            NavigationTab.HOME to homeItem,
            NavigationTab.FRIENDS to friendsItem,
            NavigationTab.SEARCH to searchItem,
            NavigationTab.MENTIONS to mentionsItem,
            NavigationTab.SETTINGS to settingsItem,
        )
        userAvatarPresenceViewController = UserAvatarPresenceViewController(avatar)
        isSaveEnabled = true
    }

    fun updateView(
        selectedTab: NavigationTab,
        onTabSelected: (NavigationTab) -> Unit,
        buttonsEnabled: Boolean,
        myUserId: Long,
        visibleTabs: Set<NavigationTab>,
        homeNotificationsCount: Int,
        friendsNotificationsCount: Int,
        onSearchClick: () -> Unit,
        onSettingsLongPress: () -> Unit,
        onMentionsLongPress: () -> Unit,
    ) {
        this.selectedTab = selectedTab
        this.visibleTabs = visibleTabs.toSet()
        this.homeNotificationsCount = homeNotificationsCount
        this.friendsNotificationsCount = friendsNotificationsCount
        applyVisualState()
        // Re-binding on every render would restart the subscription for no reason; only an actual
        // change of user needs one.
        updateNotificationBadges(homeNotificationsCount, friendsNotificationsCount)
        val noClick: (View) -> Unit = {}
        navigationTabToViewMap[NavigationTab.HOME]!!.setOnClickListener(
            if (buttonsEnabled) View.OnClickListener { onTabSelected(NavigationTab.HOME) } else View.OnClickListener(noClick),
        )
        navigationTabToViewMap[NavigationTab.FRIENDS]!!.setOnClickListener(
            if (buttonsEnabled) View.OnClickListener { onTabSelected(NavigationTab.FRIENDS) } else View.OnClickListener(noClick),
        )
        navigationTabToViewMap[NavigationTab.SEARCH]!!.setOnClickListener(
            if (buttonsEnabled) View.OnClickListener { onSearchClick() } else View.OnClickListener(noClick),
        )
        navigationTabToViewMap[NavigationTab.MENTIONS]!!.setOnClickListener(
            if (buttonsEnabled) View.OnClickListener { onTabSelected(NavigationTab.MENTIONS) } else View.OnClickListener(noClick),
        )
        navigationTabToViewMap[NavigationTab.SETTINGS]!!.setOnClickListener(
            if (buttonsEnabled) View.OnClickListener { onTabSelected(NavigationTab.SETTINGS) } else View.OnClickListener(noClick),
        )
        navigationTabToViewMap[NavigationTab.SETTINGS]!!.setOnLongClickListener(
            if (buttonsEnabled) View.OnLongClickListener { onSettingsLongPress(); true } else null,
        )
        navigationTabToViewMap[NavigationTab.MENTIONS]!!.setOnLongClickListener(
            if (buttonsEnabled) View.OnLongClickListener { onMentionsLongPress(); true } else null,
        )
        val reordered = bindPluginTabs(pluginBottomTabContext(selectedTab.name, myUserId, buttonsEnabled))
        val previousUserId = userAvatarPresenceViewController.userId
        userAvatarPresenceViewController.userId = myUserId
        if (reordered || previousUserId != myUserId) userAvatarPresenceViewController.bind()
    }

    private fun bindPluginTabs(pluginContext: PluginBottomTabContext): Boolean {
        val contributions = PluginUiRegistry.bottomTabs()
        pluginViews.keys.retainAll(contributions.mapTo(HashSet(), OwnedBottomTabContribution::key))
        val defaultOrder = PluginSurfaceCustomization.defaultOrder(
            navigationTabToViewMap.keys.map { tab ->
                PluginSurfaceCustomization.builtinTabKey(tab.name) to
                    PluginSurfaceCustomization.builtinTabPriority(tab.name)
            } + contributions.map { owned ->
                PluginSurfaceCustomization.pluginKey(owned.owner, owned.contribution.id) to
                    owned.contribution.priority
            },
        )
        val enabledKeys = PluginSurfaceCustomization.get(Surface.BOTTOM_TABS, defaultOrder)
            .filter { it.visible || PluginSurfaceCustomization.isRequiredBottomTab(it.key) }
            .mapTo(LinkedHashSet(), PluginSurfaceCustomization.Entry::key)
        val builtins = navigationTabToViewMap.mapNotNull { (tab, view) ->
            val key = PluginSurfaceCustomization.builtinTabKey(tab.name)
            OrderedTab(key, view).takeIf { tab in visibleTabs && key in enabledKeys }
        }
        val plugins = contributions.mapNotNull { owned ->
            val contribution = owned.contribution
            val key = PluginSurfaceCustomization.pluginKey(owned.owner, contribution.id)
            if (key !in enabledKeys || !runCatching { contribution.isVisible(pluginContext) }.getOrDefault(false)) {
                return@mapNotNull null
            }
            val view = pluginViews[owned.key]
                ?: runCatching { contribution.createView(context) }.getOrNull()
                    ?.also { pluginViews[owned.key] = it }
                ?: return@mapNotNull null
            runCatching { contribution.bind(view, pluginContext) }
            view.contentDescription = contribution.title
            view.setOnClickListener(
                if (pluginContext.buttonsEnabled) View.OnClickListener {
                    runCatching { contribution.onClick(pluginContext) }
                } else null,
            )
            OrderedTab(key, view)
        }
        val byKey = (builtins + plugins).associateBy(OrderedTab::key)
        val ordered = enabledKeys.mapNotNull(byKey::get)
        val orderedViews = ordered.map(OrderedTab::view)
        val reordered = (0 until tabsContainer.childCount).map(tabsContainer::getChildAt) != orderedViews
        if (reordered) {
            tabsContainer.removeAllViews()
            orderedViews.forEach { view ->
                view.layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                tabsContainer.addView(view)
            }
        }
        tabsContainer.weightSum = ordered.size.toFloat()
        return reordered
    }

    private fun updateNotificationBadges(homeCount: Int, friendsCount: Int) {
        homeBadge.text = homeCount.toString()
        homeBadge.visibility = if (homeCount > 0) View.VISIBLE else View.GONE
        homeBadge.contentDescription = resources.getString(R.string.mentions_count).replace("{count}", homeCount.toString())
        friendsBadge.text = friendsCount.toString()
        friendsBadge.visibility = if (friendsCount > 0) View.VISIBLE else View.GONE
        friendsBadge.contentDescription = resources.getString(R.string.incoming_friend_requests_count)
            .replace("{count}", friendsCount.toString())
    }

    private fun applyVisualState() {
        navigationTabToViewMap.forEach { (tab, view) ->
            view.visibility = if (tab in visibleTabs) View.VISIBLE else View.GONE
            view.isSelected = tab == selectedTab
        }
        val activeColor = ColorCompat.getThemedColor(context, R.attr.colorTabsIconActive)
        val normalColor = ColorCompat.getThemedColor(context, R.attr.colorInteractiveNormal)
        tintableIconToNavigationTabMap.forEach { (icon, tab) ->
            ImageViewCompat.setImageTintList(
                icon,
                ColorStateList.valueOf(if (tab == selectedTab) activeColor else normalColor),
            )
        }
        iconToNavigationTabMap.forEach { (icon, tab) -> icon.alpha = if (tab == selectedTab) 1f else 0.5f }
    }

    fun addHeightChangedListener(heightChangedListener: HeightChangedListener) {
        heightChangedListeners += heightChangedListener
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        heightChangedListeners.forEach { it.onHeightChanged(h) }
    }

    override fun onSaveInstanceState(): Parcelable = SavedState(super.onSaveInstanceState()).also {
        it.selectedTab = selectedTab.ordinal
        it.visibleTabs = visibleTabs.fold(0) { mask, tab -> mask or (1 shl tab.ordinal) }
        it.homeNotificationsCount = homeNotificationsCount
        it.friendsNotificationsCount = friendsNotificationsCount
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is SavedState) {
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(state.superState)
        selectedTab = NavigationTab.entries[state.selectedTab]
        visibleTabs = NavigationTab.entries.filterTo(linkedSetOf()) { state.visibleTabs and (1 shl it.ordinal) != 0 }
        homeNotificationsCount = state.homeNotificationsCount
        friendsNotificationsCount = state.friendsNotificationsCount
        applyVisualState()
        updateNotificationBadges(homeNotificationsCount, friendsNotificationsCount)
    }

    private class SavedState : BaseSavedState {
        var selectedTab = 0
        var visibleTabs = 0
        var homeNotificationsCount = 0
        var friendsNotificationsCount = 0

        constructor(superState: Parcelable?) : super(superState)
        constructor(source: Parcel) : super(source) {
            selectedTab = source.readInt()
            visibleTabs = source.readInt()
            homeNotificationsCount = source.readInt()
            friendsNotificationsCount = source.readInt()
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeInt(selectedTab)
            out.writeInt(visibleTabs)
            out.writeInt(homeNotificationsCount)
            out.writeInt(friendsNotificationsCount)
        }

        companion object CREATOR : Parcelable.Creator<SavedState> {
            override fun createFromParcel(source: Parcel) = SavedState(source)
            override fun newArray(size: Int) = arrayOfNulls<SavedState>(size)
        }
    }

    private data class OrderedTab(val key: String, val view: View)

}

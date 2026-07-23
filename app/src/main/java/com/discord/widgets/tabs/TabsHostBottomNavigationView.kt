package com.discord.widgets.tabs

import android.content.Context
import android.content.res.ColorStateList
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import com.discord.R
import com.discord.models.user.CoreUser
import com.discord.views.user.UserAvatarPresenceView

enum class NavigationTab { HOME, FRIENDS, SEARCH, MENTIONS, SETTINGS, DISCOVERY }

class TabsHostBottomNavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    fun interface HeightChangedListener { fun onHeightChanged(height: Int) }

    private val tabsContainer: LinearLayout
    private val homeBadge: TextView
    private val friendsBadge: TextView
    private val avatar: UserAvatarPresenceView
    private val navigationTabToViewMap: Map<NavigationTab, View>
    private val iconToNavigationTabMap: Map<View, NavigationTab>
    private val tintableIconToNavigationTabMap: Map<ImageView, NavigationTab>
    private val heightChangedListeners = linkedSetOf<HeightChangedListener>()
    private var selectedTab = NavigationTab.HOME
    private var visibleTabs: Set<NavigationTab> = NavigationTab.entries.toSet()
    private var myUserId = 0L
    private var homeNotificationsCount = 0
    private var friendsNotificationsCount = 0

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.tabs_host_bottom_navigation_view, this, true)
        tabsContainer = requireNotNull(findViewById(R.id.tabs_host_bottom_nav_tabs_container))
        homeBadge = requireNotNull(findViewById(R.id.tabs_host_bottom_nav_home_notifications_badge))
        friendsBadge = requireNotNull(findViewById(R.id.tabs_host_bottom_nav_friends_notifications_badge))
        avatar = requireNotNull(findViewById(R.id.tabs_host_bottom_nav_user_avatar_presence_view))
        val homeIcon = requireNotNull<ImageView>(findViewById(R.id.tabs_host_bottom_nav_home_icon))
        val friendsIcon = requireNotNull<ImageView>(findViewById(R.id.tabs_host_bottom_nav_friends_icon))
        val searchIcon = requireNotNull<ImageView>(findViewById(R.id.tabs_host_bottom_nav_search_icon))
        val mentionsIcon = requireNotNull<ImageView>(findViewById(R.id.tabs_host_bottom_nav_mentions_icon))
        val homeItem = requireNotNull<View>(findViewById(R.id.tabs_host_bottom_nav_home_item))
        val friendsItem = requireNotNull<View>(findViewById(R.id.tabs_host_bottom_nav_friends_item))
        val searchItem = requireNotNull<View>(findViewById(R.id.tabs_host_bottom_nav_search_item))
        val mentionsItem = requireNotNull<View>(findViewById(R.id.tabs_host_bottom_nav_mentions_item))
        val settingsItem = requireNotNull<View>(findViewById(R.id.tabs_host_bottom_nav_user_settings_item))
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
        tabsContainer.weightSum = visibleTabs.size.toFloat()
        applyVisualState()
        if (this.myUserId != myUserId) {
            this.myUserId = myUserId
            configureAvatar()
        }
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
        val activeColor = themedColor(R.attr.colorTabsIconActive)
        val normalColor = themedColor(R.attr.colorInteractiveNormal)
        tintableIconToNavigationTabMap.forEach { (icon, tab) ->
            ImageViewCompat.setImageTintList(
                icon,
                ColorStateList.valueOf(if (tab == selectedTab) activeColor else normalColor),
            )
        }
        iconToNavigationTabMap.forEach { (icon, tab) -> icon.alpha = if (tab == selectedTab) 1f else 0.5f }
    }

    private fun configureAvatar() {
        avatar.configure(
            UserAvatarPresenceView.ViewState(
                CoreUser(myUserId, discriminator = ((myUserId ushr 22) % 5).toInt()),
                presence = null,
                showPresence = true,
            ),
        )
    }

    fun addHeightChangedListener(heightChangedListener: HeightChangedListener) {
        heightChangedListeners += heightChangedListener
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        heightChangedListeners.forEach { it.onHeightChanged(h) }
    }

    private fun themedColor(attribute: Int): Int = TypedValue().let {
        context.theme.resolveAttribute(attribute, it, true)
        it.data
    }

    override fun onSaveInstanceState(): Parcelable = SavedState(super.onSaveInstanceState()).also {
        it.selectedTab = selectedTab.ordinal
        it.visibleTabs = visibleTabs.fold(0) { mask, tab -> mask or (1 shl tab.ordinal) }
        it.myUserId = myUserId
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
        myUserId = state.myUserId
        homeNotificationsCount = state.homeNotificationsCount
        friendsNotificationsCount = state.friendsNotificationsCount
        tabsContainer.weightSum = visibleTabs.size.toFloat()
        applyVisualState()
        configureAvatar()
        updateNotificationBadges(homeNotificationsCount, friendsNotificationsCount)
    }

    private class SavedState : BaseSavedState {
        var selectedTab = 0
        var visibleTabs = 0
        var myUserId = 0L
        var homeNotificationsCount = 0
        var friendsNotificationsCount = 0

        constructor(superState: Parcelable?) : super(superState)
        constructor(source: Parcel) : super(source) {
            selectedTab = source.readInt()
            visibleTabs = source.readInt()
            myUserId = source.readLong()
            homeNotificationsCount = source.readInt()
            friendsNotificationsCount = source.readInt()
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeInt(selectedTab)
            out.writeInt(visibleTabs)
            out.writeLong(myUserId)
            out.writeInt(homeNotificationsCount)
            out.writeInt(friendsNotificationsCount)
        }

        companion object CREATOR : Parcelable.Creator<SavedState> {
            override fun createFromParcel(source: Parcel) = SavedState(source)
            override fun newArray(size: Int) = arrayOfNulls<SavedState>(size)
        }
    }
}

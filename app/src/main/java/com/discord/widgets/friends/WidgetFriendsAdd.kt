package com.discord.widgets.friends

import android.content.Context
import android.content.Intent
import android.view.View
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.simple_pager.SimplePager

/**
 * Host for the two ways to add a friend: by username tag, and by scanning for people nearby.
 *
 * Reconstruction of the reference `com.discord.widgets.friends.WidgetFriendsAdd`. It carries no
 * state of its own — [Companion.show] is the single entry point every other screen uses, and it is
 * also where the "Add Friend Modal" analytics event is emitted so no caller can open the screen
 * without it being recorded.
 */
class WidgetFriendsAdd : AppFragment(R.layout.widget_friends_add) {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.friends_section_add_friend)
        view.findViewById<SimplePager>(R.id.add_friend_view_pager).apply {
            adapter = SimplePager.Adapter(
                parentFragmentManager,
                SimplePager.Item(getString(R.string.username)) { WidgetFriendsAddById() },
                SimplePager.Item(getString(R.string.nearby_scan)) { WidgetFriendsFindNearby() },
            )
            setTabLayout(view.findViewById(R.id.action_bar_tabs))
        }
    }

    companion object {
        /**
         * @param query pre-fills the username field, used when the quick switcher hands a typed
         *   query straight to this screen.
         * @param source analytics location the modal was opened from.
         */
        @JvmStatic
        @JvmOverloads
        fun show(context: Context, query: String? = null, source: String) {
            AnalyticsTracker.openModal("Add Friend Modal", source)
            AppScreenRouter.launch(
                context,
                WidgetFriendsAdd::class.java,
                Intent().putExtra(Intent.EXTRA_TEXT, query),
            )
        }
    }
}

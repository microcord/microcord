package com.discord.widgets.safetyhub

import android.view.View
import com.discord.R
import com.discord.stores.StoreStream
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.auth.AuthFragment
import com.discord.widgets.auth.AuthRoute

/**
 * Where a suspended login lands: the safety hub with no app behind it.
 *
 * Closing it drops the suspended token and returns to the login screen, since nothing else here is
 * reachable without an account token.
 */
class WidgetSuspendedUser : AuthFragment(R.layout.widget_suspended_user, AuthRoute.ACCOUNT_STANDING) {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        if (childFragmentManager.findFragmentById(R.id.suspended_user_content) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.suspended_user_content, WidgetSafetyHub.embedded())
                .commit()
        }
        view.findViewById<View>(R.id.suspended_user_learn_more).setOnClickListener {
            UriHandler.handle(it.context, WARNING_SYSTEM_HELP_URL)
        }
        view.findViewById<View>(R.id.suspended_user_close).setOnClickListener {
            StoreStream.getAuthentication().closeSuspendedUser()
        }
    }

    private companion object {
        const val WARNING_SYSTEM_HELP_URL = "https://support.discord.com/hc/articles/18210965981847"
    }
}

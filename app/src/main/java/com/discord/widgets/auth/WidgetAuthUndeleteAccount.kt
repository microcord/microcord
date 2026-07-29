package com.discord.widgets.auth

import android.view.View
import com.discord.R
import com.discord.views.ScreenTitleView
import com.google.android.material.button.MaterialButton

class WidgetAuthUndeleteAccount : AuthFragment(R.layout.widget_auth_undelete_account, AuthRoute.UNDELETE_ACCOUNT) {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<MaterialButton>(R.id.auth_undelete_delete)
            .setOnClickListener { submit(AuthAction.UndeleteAccount(false)) }
        view.findViewById<MaterialButton>(R.id.auth_undelete_cancel)
            .setOnClickListener { submit(AuthAction.UndeleteAccount(true)) }
    }

    override fun render(state: AuthUiState) {
        if (state.disabledAccount) requireView().findViewById<ScreenTitleView>(R.id.auth_undelete_title).apply {
            setTitle(getString(R.string.account_disabled_title))
            setSubtitle(getString(R.string.account_disabled_description))
        }
    }

    override fun onDestroy() {
        if (activity?.isChangingConfigurations != true) AuthRequests.cancelPendingLogin()
        super.onDestroy()
    }
}

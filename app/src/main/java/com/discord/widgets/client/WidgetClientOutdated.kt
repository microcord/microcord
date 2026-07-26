package com.discord.widgets.client

import android.content.Context
import android.view.View
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.utilities.uri.UriHandler
import com.google.android.material.button.MaterialButton

/**
 * The blocking "your client is out of date" screen.
 *
 * It is a dead end on purpose: Back is swallowed so the user cannot return to an app the backend
 * has already refused to talk to. The only way out is the Play Store button.
 */
class WidgetClientOutdated : AppFragment(R.layout.widget_client_outdated) {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<MaterialButton>(R.id.client_outdated_update).setOnClickListener { button ->
            UriHandler.directToPlayStore(button.context)
        }
        setOnBackPressed({ true })
    }

    companion object {
        fun launch(context: Context) {
            AppScreenRouter.launch(context, WidgetClientOutdated::class.java)
        }
    }
}

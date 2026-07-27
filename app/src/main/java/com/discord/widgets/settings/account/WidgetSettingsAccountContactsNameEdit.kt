package com.discord.widgets.settings.account

import android.content.Context
import android.view.View
import com.discord.R
import com.discord.api.connectedaccounts.ConnectedAccount
import com.discord.app.AppFragment
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.extensions.setOnImeActionDone
import com.discord.utilities.view.extensions.setText
import com.discord.widgets.settings.SettingsNavigation
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout

class WidgetSettingsAccountContactsNameEdit :
    AppFragment(R.layout.widget_settings_account_contacts_name_edit) {

    private lateinit var name: TextInputLayout
    private lateinit var save: FloatingActionButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarSubtitle(R.string.user_settings)
        setActionBarTitle(R.string.contact_sync_update_name_title)
        setActionBarDisplayHomeAsUpEnabled()

        name = view.findViewById(R.id.edit_account_name_wrap)
        save = view.findViewById(R.id.settings_account_save)
        name.setOnImeActionDone { submitName(name.getTextOrEmpty().trim()) }
        save.setOnClickListener { submitName(name.getTextOrEmpty().trim()) }
        view.findViewById<View>(R.id.settings_account_name_clear).setOnClickListener { submitName("") }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        StoreStream.getUserConnections().observeConnectedAccounts()
            .ui(this)
            .appSubscribe(WidgetSettingsAccountContactsNameEdit::class.java) { state ->
                configureUI(state.connectedAccounts)
            }
    }

    private fun configureUI(connections: List<ConnectedAccount>) {
        name.setText(connections.lastOrNull { it.type == CONTACTS_TYPE }?.name.orEmpty())
    }

    private fun submitName(rawName: String) {
        hideKeyboard()
        val submittedName = normalizeContactName(rawName)
        AnalyticsTracker.nameSubmitted(rawName.split(" ").size, rawName.length)
        // ponytail: RestAPI has no serialize-nulls client; clearing needs that shared REST seam.
        RestAPI.api.updateConnectionName(
            CONTACTS_TYPE,
            CURRENT_CONNECTION,
            RestAPIParams.ConnectedAccountNameOnly(submittedName),
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetSettingsAccountContactsNameEdit::class.java, context) { response ->
                if (response.isSuccessful) {
                    @Suppress("DEPRECATION")
                    activity?.onBackPressed()
                } else {
                    throw retrofit2.HttpException(response)
                }
            }
    }

    companion object {
        private const val CONTACTS_TYPE = "contacts"
        private const val CURRENT_CONNECTION = "@me"

        internal fun normalizeContactName(name: String): String? = name.trim().ifBlank { null }

        @JvmStatic
        fun launch(context: Context) {
            SettingsNavigation.launch(context, WidgetSettingsAccountContactsNameEdit::class.java)
        }
    }
}

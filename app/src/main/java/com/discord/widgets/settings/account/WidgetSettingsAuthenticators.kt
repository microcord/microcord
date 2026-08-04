package com.discord.widgets.settings.account

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.auth.mfa.Authenticator
import com.discord.app.AppFragment
import com.discord.restapi.RestAPIParams
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.views.SimpleRecyclerAdapter
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.settings.SettingsNavigation

/**
 * Passkeys and security keys enrolled on the account.
 *
 * No "add": enrolling needs platform attestation tied to the retail package and signing
 * certificate, which this build cannot produce.
 */
class WidgetSettingsAuthenticators : AppFragment(R.layout.widget_settings_authenticators) {

    private lateinit var empty: TextView

    private val adapter = SimpleRecyclerAdapter<Authenticator, AuthenticatorViewHolder> { inflater, parent ->
        AuthenticatorViewHolder(inflater, parent, ::promptRename, ::confirmDelete)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)
        empty = view.findViewById(R.id.authenticators_empty)
        view.findViewById<RecyclerView>(R.id.authenticators_list).adapter = adapter
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        loadAuthenticators()
    }

    private fun loadAuthenticators() {
        RestAPI.api.getAuthenticators()
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetSettingsAuthenticators::class.java, context) { authenticators ->
                adapter.setData(authenticators)
                empty.visibility = if (authenticators.isEmpty()) View.VISIBLE else View.GONE
            }
    }

    private fun promptRename(authenticator: Authenticator) {
        val activity = appActivity ?: return
        WidgetNoticeDialog.showInputModal(
            activity = activity,
            headerText = getString(R.string.passkey_and_security_key_rename),
            bodyText = getString(R.string.passkey_and_security_key_rename_body),
            hintText = getString(R.string.passkey_and_security_key_name_label),
            onOKClicked = { _, name ->
                if (name.isNotBlank()) rename(authenticator, name)
            },
        )
    }

    /** MFA-gated, so `MfaInterceptor` may prompt before this completes. */
    private fun rename(authenticator: Authenticator, name: String) {
        RestAPI.api.renameAuthenticator(authenticator.id, RestAPIParams.RenameAuthenticator(name))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetSettingsAuthenticators::class.java,
                context = context,
                onCompleted = ::loadAuthenticators,
                onNext = {},
            )
    }

    private fun confirmDelete(authenticator: Authenticator) {
        WidgetNoticeDialog.Builder(requireContext())
            .setTitle(R.string.passkey_and_security_key_delete)
            .setMessage(
                if (adapter.itemCount == 1) {
                    R.string.passkey_and_security_key_delete_last_body
                } else {
                    R.string.passkey_and_security_key_delete_body
                },
            )
            .setNegativeButton(R.string.cancel)
            .setPositiveButton(R.string.remove) { delete(authenticator) }
            .show(parentFragmentManager)
    }

    /** MFA-gated. Removing the last one turns MFA off for the account entirely. */
    private fun delete(authenticator: Authenticator) {
        RestAPI.api.deleteAuthenticator(authenticator.id)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetSettingsAuthenticators::class.java,
                context = context,
                onCompleted = ::loadAuthenticators,
                onNext = {},
            )
    }

    private class AuthenticatorViewHolder(
        inflater: LayoutInflater,
        parent: ViewGroup,
        private val onRename: (Authenticator) -> Unit,
        private val onDelete: (Authenticator) -> Unit,
    ) : SimpleRecyclerAdapter.ViewHolder<Authenticator>(
        inflater.inflate(R.layout.widget_settings_authenticators_list_item, parent, false),
    ) {
        private val name: TextView = itemView.findViewById(R.id.authenticator_name)
        private val rename: ImageView = itemView.findViewById(R.id.authenticator_rename)
        private val delete: ImageView = itemView.findViewById(R.id.authenticator_delete)

        override fun bind(data: Authenticator) {
            name.text = data.name?.takeIf(String::isNotBlank)
                ?: itemView.context.getString(R.string.passkey_and_security_key)
            itemView.contentDescription = name.text
            rename.setOnClickListener { onRename(data) }
            delete.setOnClickListener { onDelete(data) }
        }
    }

    companion object {
        fun launch(context: Context) {
            SettingsNavigation.launch(context, WidgetSettingsAuthenticators::class.java)
        }
    }
}

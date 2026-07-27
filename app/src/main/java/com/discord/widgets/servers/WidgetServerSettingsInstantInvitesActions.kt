package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppToast
import com.discord.models.domain.ModelInvite
import com.discord.models.invite.InviteUtils
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.settings.SettingsStrings

/**
 * What can be done with one server invite: revoke it, copy its link, or share it.
 *
 * Reference: `com.discord.widgets.servers.WidgetServerSettingsInstantInvitesActions`. The sheet is
 * given only the invite **code**, not the invite, and rebuilds the link from it — which is why it
 * survives the invite disappearing from the list underneath it.
 *
 * Revoking reports back through `StoreInstantInvites.onInviteRemoved`, so every other surface
 * holding this invite drops it too; the list screen re-reads when this sheet is dismissed.
 */
class WidgetServerSettingsInstantInvitesActions : AppBottomSheet() {

    private lateinit var title: TextView
    private lateinit var revoke: TextView
    private lateinit var copy: TextView
    private lateinit var share: TextView

    override fun getContentViewResId(): Int = R.layout.widget_server_settings_instant_invite_actions

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        title = view.findViewById(R.id.invite_actions_title)
        revoke = view.findViewById(R.id.invite_actions_revoke)
        copy = view.findViewById(R.id.invite_actions_copy)
        share = view.findViewById(R.id.invite_actions_share)
    }

    override fun onResume() {
        super.onResume()
        val inviteCode = argumentsOrDefault.getString(ARG_INVITE_CODE)
        // Nothing identifies the invite, so there is no action to offer.
        if (inviteCode.isNullOrBlank()) {
            dismiss()
            return
        }
        val inviteUrl = InviteUtils.createLinkFromCode(inviteCode)
        title.text = inviteCode

        revoke.setOnClickListener { revokeInvite(inviteCode) }
        copy.setOnClickListener {
            AppToast.copyToClipboard(it.context, inviteUrl)
            dismiss()
        }
        share.setOnClickListener {
            performChooserSendIntent(
                it.context,
                inviteUrl,
                SettingsStrings.render(
                    it.context,
                    R.string.share_invite_mobile,
                    "inviteUrl" to inviteUrl,
                ),
            )
            dismiss()
        }
    }

    private fun revokeInvite(inviteCode: String) {
        val api = RestAPI.apiOrNull ?: return
        api.revokeInvite(inviteCode)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                WidgetServerSettingsInstantInvitesActions::class.java,
                context,
            ) { invite: ModelInvite ->
                // The response carries the revoked invite, which is what the store keys removal on.
                StoreStream.getInstantInvites().onInviteRemoved(invite)
                dismiss()
            }
    }

    /**
     * Hands the invite link to the system share sheet.
     *
     * The reference calls `IntentUtils.performChooserSendIntent`; that utility has not been
     * reconstructed, so its body lives here — the same stopgap `widgets/media/WidgetMedia` already
     * carries.
     */
    private fun performChooserSendIntent(context: Context, text: String, chooserTitle: CharSequence) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }

    companion object {
        private const val ARG_INVITE_CODE = "ARG_INVITE_CODE"

        fun create(fragmentManager: FragmentManager, inviteCode: String) {
            WidgetServerSettingsInstantInvitesActions()
                .apply { arguments = Bundle().apply { putString(ARG_INVITE_CODE, inviteCode) } }
                .show(fragmentManager, WidgetServerSettingsInstantInvitesActions::class.java.name)
        }
    }
}

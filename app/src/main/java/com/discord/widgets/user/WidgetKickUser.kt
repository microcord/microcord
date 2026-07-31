package com.discord.widgets.user

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.databinding.WidgetKickUserBinding
import com.discord.i18n.i18nFormat
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.getTextOrEmpty
import rx.Observable

/**
 * Confirms kicking a member out of a guild, with an optional audit-log reason.
 *
 * A kick is reversible — the member can rejoin with a new invite — so this is a plain confirmation
 * rather than the multi-option dialog [WidgetBanUser] uses.
 */
class WidgetKickUser : AppDialog(R.layout.widget_kick_user) {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val binding = WidgetKickUserBinding.bind(view)
        val userName = argumentsOrDefault.getString(EXTRA_USER_NAME, "").orEmpty()
        val guildId = argumentsOrDefault.getLong(EXTRA_GUILD_ID, -1L)
        val userId = argumentsOrDefault.getLong(EXTRA_USER_ID, -1L)

        binding.kickUserTitle.text = view.i18nFormat(R.string.kick_user_title, "user" to userName)
        binding.kickUserBody.text = view.i18nFormat(R.string.kick_user_body, "user" to userName)

        binding.kickUserCancel.setOnClickListener { dismiss() }
        binding.kickUserConfirm.setOnClickListener {
            // A blank reason is omitted rather than sent as an empty audit-log header.
            val reason = binding.kickUserReason.getTextOrEmpty().asAuditLogReason()

            completeNoContent(RestAPI.api.kickGuildMember(guildId, userId, reason))
                .restSubscribeOn()
                .ui(this)
                .appSubscribe(
                    errorClass = WidgetKickUser::class.java,
                    context = context,
                ) {
                    Toast.makeText(
                        context,
                        view.i18nFormat(R.string.kick_user_confirmed, "user" to userName),
                        Toast.LENGTH_SHORT,
                    ).show()
                    parentFragmentManager.setFragmentResult(REQUEST_KEY_KICKED, Bundle.EMPTY)
                    dismiss()
                }
        }
    }

    companion object {
        private const val EXTRA_USER_NAME = "com.discord.intent.extra.EXTRA_USER_NAME"
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val EXTRA_USER_ID = "com.discord.intent.extra.EXTRA_USER_ID"
        const val REQUEST_KEY_KICKED = "WidgetKickUser.KICKED"

        @JvmStatic
        fun launch(userName: String, guildId: Long, userId: Long, fragmentManager: FragmentManager) {
            WidgetKickUser().apply {
                arguments = Bundle().apply {
                    putString(EXTRA_USER_NAME, userName)
                    putLong(EXTRA_GUILD_ID, guildId)
                    putLong(EXTRA_USER_ID, userId)
                }
            }.show(fragmentManager, WidgetKickUser::class.java.simpleName)
        }

        /** Argument order used by the user sheet's event handler. */
        @JvmStatic
        fun show(fragmentManager: FragmentManager, guildId: Long, userId: Long, userName: String) =
            launch(userName, guildId, userId, fragmentManager)
    }
}

internal fun completeNoContent(request: Observable<Void>): Observable<Unit> =
    request.map { Unit }.defaultIfEmpty(Unit)

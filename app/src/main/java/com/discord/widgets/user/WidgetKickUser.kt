package com.discord.widgets.user

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.i18n.i18nFormat
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.google.android.material.textfield.TextInputLayout

/**
 * Confirms kicking a member out of a guild, with an optional audit-log reason.
 *
 * A kick is reversible — the member can rejoin with a new invite — so this is a plain confirmation
 * rather than the multi-option dialog [WidgetBanUser] uses.
 */
class WidgetKickUser : AppDialog(R.layout.widget_kick_user) {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val userName = argumentsOrDefault.getString(EXTRA_USER_NAME, "").orEmpty()
        val guildId = argumentsOrDefault.getLong(EXTRA_GUILD_ID, -1L)
        val userId = argumentsOrDefault.getLong(EXTRA_USER_ID, -1L)

        view.findViewById<TextView>(R.id.kick_user_title).text =
            view.i18nFormat(R.string.kick_user_title, "user" to userName)
        view.findViewById<TextView>(R.id.kick_user_body).text =
            view.i18nFormat(R.string.kick_user_body, "user" to userName)

        view.findViewById<View>(R.id.kick_user_cancel).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.kick_user_confirm).setOnClickListener {
            // A blank reason is omitted rather than sent as an empty audit-log header.
            val reason = view.findViewById<TextInputLayout>(R.id.kick_user_reason)
                .getTextOrEmpty()
                .asAuditLogReason()

            RestAPI.api.kickGuildMember(guildId, userId, reason)
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
                    dismiss()
                }
        }
    }

    companion object {
        private const val EXTRA_USER_NAME = "com.discord.intent.extra.EXTRA_USER_NAME"
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val EXTRA_USER_ID = "com.discord.intent.extra.EXTRA_USER_ID"

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

package com.discord.widgets.user

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.i18n.i18nFormat
import com.discord.restapi.RestAPIParams
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.views.CheckedSetting
import com.discord.views.RadioManager
import com.google.android.material.textfield.TextInputLayout

/**
 * Confirms banning a member, choosing how much of their message history to delete along with them.
 *
 * The history choice is the reason this is a dialog rather than a confirmation: deleting a
 * spammer's last week of messages is the common case, and it cannot be undone afterwards.
 */
class WidgetBanUser : AppDialog(R.layout.widget_ban_user) {

    private var deleteHistoryRadioManager: RadioManager? = null

    private lateinit var historyRadios: List<CheckedSetting>

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        historyRadios = listOf(
            view.findViewById(R.id.ban_user_delete_messages_none),
            view.findViewById(R.id.ban_user_delete_messages_1_day),
            view.findViewById(R.id.ban_user_delete_messages_7_days),
        )
        deleteHistoryRadioManager = RadioManager(historyRadios)
        historyRadios.forEach { radio ->
            radio.setContainerClickListener { _ -> deleteHistoryRadioManager?.setChecked(radio) }
        }

        val userName = argumentsOrDefault.getString(EXTRA_USER_NAME, "").orEmpty()
        val guildId = argumentsOrDefault.getLong(EXTRA_GUILD_ID, -1L)
        val userId = argumentsOrDefault.getLong(EXTRA_USER_ID, -1L)

        view.findViewById<TextView>(R.id.ban_user_title).text =
            view.i18nFormat(R.string.ban_user_title, "user" to userName)
        view.findViewById<TextView>(R.id.ban_user_body).text =
            view.i18nFormat(R.string.ban_user_body, "user" to userName)

        view.findViewById<View>(R.id.ban_user_cancel).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.ban_user_confirm).setOnClickListener {
            val reason = view.findViewById<TextInputLayout>(R.id.ban_user_reason)
                .getTextOrEmpty()
                .asAuditLogReason()

            RestAPI.api.banGuildMember(
                guildId,
                userId,
                RestAPIParams.BanGuildMember(deleteMessageDays()),
                reason,
            )
                .restSubscribeOn()
                .ui(this)
                .appSubscribe(
                    errorClass = WidgetBanUser::class.java,
                    context = context,
                ) {
                    Toast.makeText(
                        context,
                        view.i18nFormat(R.string.ban_user_confirmed, "user" to userName),
                        Toast.LENGTH_SHORT,
                    ).show()
                    dismiss()
                }
        }
    }

    /** How many days of the banned member's messages to delete; `0` keeps all of them. */
    private fun deleteMessageDays(): Int {
        val checkedIndex = deleteHistoryRadioManager?.checkedButtonIndex ?: 0
        return deleteMessageDays(historyRadios.getOrNull(checkedIndex)?.id)
    }

    companion object {
        private const val EXTRA_USER_NAME = "com.discord.intent.extra.EXTRA_USER_NAME"
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val EXTRA_USER_ID = "com.discord.intent.extra.EXTRA_USER_ID"

        @JvmStatic
        fun launch(userName: String, guildId: Long, userId: Long, fragmentManager: FragmentManager?) {
            fragmentManager ?: return
            WidgetBanUser().apply {
                arguments = Bundle().apply {
                    putString(EXTRA_USER_NAME, userName)
                    putLong(EXTRA_GUILD_ID, guildId)
                    putLong(EXTRA_USER_ID, userId)
                }
            }.show(fragmentManager, WidgetBanUser::class.java.simpleName)
        }

        /** Argument order used by the user sheet's event handler. */
        @JvmStatic
        fun show(fragmentManager: FragmentManager, guildId: Long, userId: Long, userName: String) =
            launch(userName, guildId, userId, fragmentManager)
    }
}

internal fun deleteMessageDays(checkedId: Int?): Int = when (checkedId) {
    R.id.ban_user_delete_messages_1_day -> 1
    R.id.ban_user_delete_messages_7_days -> 7
    else -> 0
}

internal fun String.asAuditLogReason(): String? = takeUnless(String::isBlank)

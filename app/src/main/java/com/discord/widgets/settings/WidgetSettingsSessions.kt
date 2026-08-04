package com.discord.widgets.settings

import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.auth.AuthSession
import com.discord.app.AppFragment
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.time.TimeUtils
import com.discord.utilities.views.SimpleRecyclerAdapter
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.settings.account.WidgetSettingsAccountChangePassword
import java.util.Locale

/**
 * Where the account is signed in.
 *
 * This connection's own session is listed separately with no revoke button: revoking it would drop
 * the user's token with nothing left to report the result.
 */
class WidgetSettingsSessions : AppFragment(R.layout.widget_settings_sessions) {

    private lateinit var currentHeader: TextView
    private lateinit var othersHeader: TextView
    private lateinit var legacyNotice: View
    private lateinit var logOutOthers: View

    private val currentAdapter = SimpleRecyclerAdapter<AuthSession, SessionViewHolder> { inflater, parent ->
        SessionViewHolder(inflater, parent, onLogOut = null)
    }
    private val othersAdapter = SimpleRecyclerAdapter<AuthSession, SessionViewHolder> { inflater, parent ->
        SessionViewHolder(inflater, parent, onLogOut = ::confirmLogOut)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)
        currentHeader = view.findViewById(R.id.sessions_current_header)
        othersHeader = view.findViewById(R.id.sessions_others_header)
        legacyNotice = view.findViewById(R.id.sessions_legacy_notice)
        logOutOthers = view.findViewById(R.id.sessions_log_out_others)
        view.findViewById<View>(R.id.sessions_legacy_change_password).setOnClickListener {
            WidgetSettingsAccountChangePassword.launch(it.context)
        }
        view.findViewById<RecyclerView>(R.id.sessions_current_list).adapter = currentAdapter
        view.findViewById<RecyclerView>(R.id.sessions_others_list).adapter = othersAdapter
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        loadSessions()
    }

    private fun loadSessions() {
        RestAPI.api.getAuthSessions()
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetSettingsSessions::class.java, context) { response ->
                val currentHash = StoreStream.getAuthentication().getAuthSessionIdHash()
                val (current, others) = response.userSessions.orEmpty()
                    .partition { it.idHash == currentHash }
                currentAdapter.setData(current)
                othersAdapter.setData(others)
                // The reference shows the "Other Devices" group, and its legacy-sessions notice,
                // whenever there are other devices *or* MFA is off — an account without MFA can
                // have sessions too old for the API to list.
                val showOthers = others.isNotEmpty() || !StoreStream.getUsers().getMe().mfaEnabled
                currentHeader.visibility = visibleIf(current.isNotEmpty())
                othersHeader.visibility = visibleIf(showOthers)
                legacyNotice.visibility = visibleIf(showOthers)
                logOutOthers.visibility = visibleIf(others.isNotEmpty())
                logOutOthers.setOnClickListener { confirmLogOutAll(others) }
            }
    }

    private fun confirmLogOut(session: AuthSession) = confirm(
        title = R.string.auth_sessions_session_log_out,
        hashes = listOf(session.idHash),
    )

    private fun confirmLogOutAll(sessions: List<AuthSession>) = confirm(
        title = R.string.auth_sessions_others_log_out_title,
        hashes = sessions.map(AuthSession::idHash),
    )

    private fun confirm(title: Int, hashes: List<String>) {
        if (hashes.isEmpty()) return
        WidgetNoticeDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(R.string.auth_sessions_others_log_out_description)
            .setNegativeButton(R.string.cancel)
            .setPositiveButton(R.string.auth_sessions_others_log_out_action) { logOut(hashes) }
            .show(parentFragmentManager)
    }

    /** MFA-gated, so `MfaInterceptor` may prompt before this completes. */
    private fun logOut(hashes: List<String>) {
        RestAPI.api.logoutAuthSessions(RestAPIParams.LogoutAuthSessions(hashes))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetSettingsSessions::class.java,
                context = context,
                onCompleted = ::loadSessions,
                onNext = {},
            )
    }

    private fun visibleIf(condition: Boolean) = if (condition) View.VISIBLE else View.GONE

    private class SessionViewHolder(
        inflater: LayoutInflater,
        parent: ViewGroup,
        private val onLogOut: ((AuthSession) -> Unit)?,
    ) : SimpleRecyclerAdapter.ViewHolder<AuthSession>(
        inflater.inflate(R.layout.widget_settings_sessions_list_item, parent, false),
    ) {
        private val icon: ImageView = itemView.findViewById(R.id.session_icon)
        private val platform: TextView = itemView.findViewById(R.id.session_platform)
        private val location: TextView = itemView.findViewById(R.id.session_location)
        private val lastUsed: TextView = itemView.findViewById(R.id.session_last_used)
        private val logOut: ImageView = itemView.findViewById(R.id.session_log_out)

        override fun bind(data: AuthSession) {
            val context = itemView.context
            val info = data.clientInfo
            val rawOs = info?.os?.takeIf(String::isNotBlank)
            val osText = rawOs ?: context.getString(R.string.auth_sessions_os_unknown)
            platform.text = listOfNotNull(osText, info?.platform?.takeIf(String::isNotBlank))
                .joinToString(" · ")

            // No placeholder: the reference falls back to the IP and otherwise drops the line.
            val locationText = info?.location?.takeIf(String::isNotBlank)
                ?: info?.ip?.takeIf(String::isNotBlank)
            location.text = locationText
            location.visibility = if (locationText == null) View.GONE else View.VISIBLE

            // The current device shows no "last active": it is active now.
            val lastUsedText = if (onLogOut == null) null else data.approxLastUsedTime?.let { lastActive(context, it) }
            lastUsed.text = lastUsedText
            lastUsed.visibility = if (lastUsedText == null) View.GONE else View.VISIBLE

            icon.setImageResource(
                if (rawOs?.lowercase(Locale.ROOT)?.trim() in MOBILE_OPERATING_SYSTEMS) {
                    R.drawable.ic_smartphone_24dp
                } else {
                    R.drawable.ic_monitor_white_24dp
                },
            )
            itemView.contentDescription = listOfNotNull(platform.text, locationText).joinToString(", ")

            logOut.visibility = if (onLogOut == null) View.GONE else View.VISIBLE
            logOut.setOnClickListener { onLogOut?.invoke(data) }
        }

        /** Anything under an hour reads as "less than an hour ago"; older is a relative span. */
        private fun lastActive(context: Context, utcDate: String): CharSequence? {
            val millis = TimeUtils.parseUTCDate(utcDate).takeIf { it > 0 } ?: return null
            val elapsed = ClockFactory.get().currentTimeMillis() - millis
            if (elapsed < DateUtils.HOUR_IN_MILLIS) {
                return context.getString(R.string.auth_sessions_active_recently)
            }
            return DateUtils.getRelativeTimeSpanString(
                millis,
                ClockFactory.get().currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            )
        }

        private companion object {
            val MOBILE_OPERATING_SYSTEMS = setOf("android", "ios")
        }
    }

    companion object {
        fun launch(context: Context) {
            SettingsNavigation.launch(context, WidgetSettingsSessions::class.java)
        }
    }
}

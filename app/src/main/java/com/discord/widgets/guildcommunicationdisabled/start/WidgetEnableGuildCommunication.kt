package com.discord.widgets.guildcommunicationdisabled.start

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.i18n.I18nSpans
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.user.UserUtils.getUserNameWithDiscriminator
import com.discord.utilities.view.ToastManager
import com.google.android.material.button.MaterialButton
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Subscription

/**
 * "Remove timeout" confirmation, with a live countdown of the time the member has left.
 *
 * The dialog reads the member straight out of the stores in [onViewBound] rather than through a
 * view model — that is what the reference does, and there is nothing to keep across a rotation
 * beyond the countdown, which restarts anyway. If the member is not actually timed out the dialog
 * renders as inflated and none of its buttons are wired, so it cannot issue a pointless PATCH.
 */
class WidgetEnableGuildCommunication : AppDialog(R.layout.widget_enable_guild_communication) {

    private val clock: Clock = ClockFactory.get()
    private var communicationDisabledCountdownSubscription: Subscription? = null
    private val toastManager = ToastManager()

    private lateinit var body: TextView
    private lateinit var bodyHelpText: TextView
    private lateinit var confirm: MaterialButton
    private lateinit var cancel: MaterialButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        body = view.findViewById(R.id.enable_guild_communication_body)
        bodyHelpText = view.findViewById(R.id.enable_guild_communication_body_help_text)
        confirm = view.findViewById(R.id.enable_communication_confirm)
        cancel = view.findViewById(R.id.enable_communication_cancel)

        val guildId = argumentsOrDefault.getLong(EXTRA_GUILD_ID, -1L)
        val userId = argumentsOrDefault.getLong(EXTRA_USER_ID, -1L)

        val member = StoreStream.getGuilds().getMember(guildId, userId)
        val user = StoreStream.getUsers().getUsers()[userId]
        if (member == null || user == null || !member.isCommunicationDisabled()) return

        val userName = user.getUserNameWithDiscriminator()
        val disabledUntilMs = member.communicationDisabledUntil?.dateTimeMillis ?: 0L

        configureCommunicationDisabledTimer(disabledUntilMs, userName)
        renderTimeRemaining(disabledUntilMs, userName)

        bodyHelpText.text = I18nSpans.render(
            getString(R.string.enable_guild_communication_body_help_text),
            "helpdeskArticle" to TIMEOUT_HELP_URL,
        )
        bodyHelpText.setOnClickListener { UriHandler.handle(bodyHelpText.context, TIMEOUT_HELP_URL) }

        cancel.setOnClickListener { dismiss() }
        confirm.setOnClickListener { handleEnableGuildCommunication(guildId, userId) }
    }

    override fun onDestroy() {
        super.onDestroy()
        communicationDisabledCountdownSubscription?.unsubscribe()
        toastManager.close()
    }

    /** Ticks once a second so the "x remaining" line stays honest while the dialog is open. */
    private fun configureCommunicationDisabledTimer(
        communicationDisabledTimestampMs: Long,
        username: CharSequence?,
    ) {
        if (communicationDisabledCountdownSubscription != null) return
        Observable.interval(0L, 1L, TimeUnit.SECONDS)
            .ui(this)
            .appSubscribe(
                errorClass = WidgetEnableGuildCommunication::class.java,
                subscriptionHandler = { communicationDisabledCountdownSubscription = it },
            ) { renderTimeRemaining(communicationDisabledTimestampMs, username) }
    }

    private fun renderTimeRemaining(communicationDisabledTimestampMs: Long, username: CharSequence?) {
        val context = context ?: return
        val remainingMs = (communicationDisabledTimestampMs - clock.currentTimeMillis()).coerceAtLeast(0L)
        body.text = I18nSpans.render(
            context.getString(R.string.enable_guild_communication_body),
            "user" to username,
            "timeRemaining" to humanizeCountdownDuration(context, remainingMs),
        )
    }

    private fun handleEnableGuildCommunication(guildId: Long, userId: Long) {
        api.disableGuildCommunication(
            guildId = guildId,
            userId = userId,
            body = RestAPIParams.DisableGuildCommunication(null),
            reason = null,
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetEnableGuildCommunication::class.java,
                context = context,
            ) {
                dismiss()
                context?.let { toastManager.show(it, R.string.guild_enable_communication_success) }
            }
    }

    companion object {
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val EXTRA_USER_ID = "com.discord.intent.extra.EXTRA_USER_ID"

        /** Zendesk article 4413305239191, "Timeout FAQ" — the same article the start screen links. */
        private const val TIMEOUT_HELP_URL =
            "https://support.discord.com/hc/en-us/articles/4413305239191"

        /**
         * Clearing a timeout means sending `"communication_disabled_until": null`, which only
         * happens on a Gson instance built with `serializeNulls`. The reference keeps a second REST
         * client for exactly this (`RestAPI.apiSerializeNulls`); this project publishes one client,
         * whose Gson drops null fields — so the PATCH would go out with an empty body and clear
         * nothing.
         *
         * `RestAPIBuilder.build(serializeNulls = true)` already supports it, so the store graph only
         * has to publish the second client into this seam. **Reported to the `StoreStream` wiring
         * agent.** Until it does, [api] falls back to the normal client.
         */
        @JvmStatic
        var serializeNullsApi: RestAPIInterface? = null

        private val api: RestAPIInterface
            get() = serializeNullsApi ?: RestAPI.api

        fun launch(userId: Long, guildId: Long, fragmentManager: FragmentManager) {
            WidgetEnableGuildCommunication().apply {
                arguments = Bundle().apply {
                    putLong(EXTRA_USER_ID, userId)
                    putLong(EXTRA_GUILD_ID, guildId)
                }
            }.show(fragmentManager, WidgetEnableGuildCommunication::class.java.simpleName)
            AnalyticsTracker.viewedEnableCommunicationModal(guildId, userId)
        }
    }
}

package com.discord.widgets.nux

import android.content.Context
import android.content.Intent
import android.view.View
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.stores.StoreInviteSettings
import com.discord.utilities.intent.DeepLinkParser
import com.discord.utilities.intent.DeepLinkRoute
import com.discord.utilities.intent.LinkParseResult
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.addBindedTextWatcher
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.extensions.setOnImeActionDone
import com.discord.widgets.guilds.invite.InviteJoinHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout

class WidgetNuxPostRegistrationJoin : AppFragment(R.layout.widget_nux_post_registration) {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled(false)

        val input = view.findViewById<TextInputLayout>(R.id.guild_join_invite)
        val action = view.findViewById<MaterialButton>(R.id.guild_join_action_btn)
        val startedAtMillis = System.currentTimeMillis()

        GuildTemplateAnalytics.track(
            GuildTemplateAnalytics.STEP_REGISTRATION,
            STEP_ASK_TO_JOIN,
            null,
            false,
        )
        action.setOnClickListener {
            val value = input.getTextOrEmpty()
            if (value.isBlank()) {
                GuildTemplateAnalytics.track(STEP_ASK_TO_JOIN, STEP_FRIEND_LIST, startedAtMillis, true)
                activity?.onBackPressedDispatcher?.onBackPressed()
            } else {
                joinInvite(input, action, value, startedAtMillis)
            }
        }
        input.setOnImeActionDone(isEventConsumed = true) { action.performClick() }
        input.addBindedTextWatcher(this) { editable ->
            action.setText(
                if (editable.isBlank()) R.string.nux_post_reg_join_server_skip else R.string.nuf_join_server_button,
            )
            input.error = null
        }
    }

    private fun joinInvite(
        input: TextInputLayout,
        action: MaterialButton,
        rawInvite: String,
        startedAtMillis: Long,
    ) {
        val code = parseInviteCode(rawInvite)
        val api = RestAPI.apiOrNull
        if (code == null || api == null) {
            input.error = getString(R.string.invalid_invite_link_error)
            return
        }

        action.isEnabled = false
        GuildTemplateAnalytics.track(STEP_ASK_TO_JOIN, STEP_ACCEPT_INVITE, startedAtMillis, false)
        api.getInviteCode(code, true)
            .map { response -> requireNotNull(response.body()) { "empty invite response" } }
            .restSubscribeOn(false)
            .ui(this)
            .appSubscribe(
                errorClass = WidgetNuxPostRegistrationJoin::class.java,
                context = context,
                errorHandler = { error ->
                    action.isEnabled = true
                    error.showErrorToasts = false
                    input.error = error.messages.values.flatten().firstOrNull()
                        ?: getString(R.string.invalid_invite_link_error)
                },
            ) { invite ->
                InviteJoinHelper.joinViaInvite(
                    invite = invite,
                    javaClass = WidgetNuxPostRegistrationJoin::class.java,
                    fragment = this,
                    location = StoreInviteSettings.LOCATION_JOIN,
                    onInvitePostError = { error ->
                        action.isEnabled = true
                        error?.showErrorToasts = false
                        input.error = error?.messages?.values?.flatten()?.firstOrNull()
                            ?: getString(R.string.invite_modal_error_default)
                    },
                    onInviteFlowFinished = { activity?.finish() },
                )
            }
    }

    companion object {
        private const val STEP_ASK_TO_JOIN = "Ask to join"
        private const val STEP_FRIEND_LIST = "Friend List"
        private const val STEP_ACCEPT_INVITE = "Accept Instant Invite"
        private val INVITE_CODE = Regex("^[A-Za-z0-9_-]+$")

        internal fun parseInviteCode(value: String): String? {
            val trimmed = value.trim()
            if (INVITE_CODE.matches(trimmed)) return trimmed
            val link = if (trimmed.startsWith("discord.gg/", ignoreCase = true)) "https://$trimmed" else trimmed
            return ((DeepLinkParser.parse(link) as? LinkParseResult.Internal)?.route as? DeepLinkRoute.Invite)?.code
        }

        fun show(context: Context) {
            AppScreenRouter.launch(context, WidgetNuxPostRegistrationJoin::class.java, Intent())
        }
    }
}

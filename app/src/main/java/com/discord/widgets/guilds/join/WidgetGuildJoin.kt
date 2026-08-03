package com.discord.widgets.guilds.join

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.models.domain.ModelInvite
import com.discord.utilities.intent.DeepLinkParser
import com.discord.utilities.intent.DeepLinkRoute
import com.discord.utilities.intent.LinkParseResult
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.guilds.invite.InviteJoinHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout

open class WidgetGuildJoin : AppFragment(R.layout.widget_guild_join) {
    private lateinit var inviteInput: TextInputLayout
    private lateinit var joinButton: MaterialButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        inviteInput = view.findViewById(R.id.guild_join_invite)
        joinButton = view.findViewById(R.id.guild_join_action_btn)
        joinButton.setOnClickListener { handleGuildJoin() }
        inviteInput.editText?.setOnEditorActionListener { _, actionId, _ ->
            (actionId == EditorInfo.IME_ACTION_DONE).also { if (it) handleGuildJoin() }
        }
    }

    protected fun handleGuildJoin() {
        val code = inviteCode(inviteInput.editText?.text?.toString())
        if (code == null) {
            inviteInput.error = getString(R.string.invalid_invite_link_error)
            return
        }
        val api = RestAPI.apiOrNull ?: return
        inviteInput.error = null
        joinButton.isEnabled = false
        api.getInviteCode(code, true)
            .restSubscribeOn(false)
            .ui(this)
            .appSubscribe(
                javaClass,
                context = context,
                errorHandler = {
                    joinButton.isEnabled = true
                    inviteInput.error = getString(R.string.instant_invite_expired)
                },
            ) { invite -> accept(invite) }
    }

    private fun accept(invite: ModelInvite) {
        InviteJoinHelper.joinViaInvite(
            invite = invite,
            javaClass = javaClass,
            fragment = this,
            location = LOCATION_JOIN,
            onInvitePostError = {
                joinButton.isEnabled = true
                inviteInput.error = getString(R.string.instant_invite_expired)
            },
            onInviteFlowFinished = { activity?.finish() },
        )
    }

    class ToolbarButton : WidgetGuildJoin() {
        override fun onViewBound(view: View) {
            super.onViewBound(view)
            activity?.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN,
            )
        }
    }

    companion object {
        private const val LOCATION_JOIN = "Join Guild"

        fun show(context: Context, location: String) = AppScreenRouter.launch(
            context,
            ToolbarButton::class.java,
            Intent().putExtra("location", location),
        )

        internal fun inviteCode(value: String?): String? {
            val candidate = value?.trim().orEmpty()
            if (candidate.isEmpty()) return null
            val parsed = DeepLinkParser.parse(
                if ("://" in candidate) candidate else "https://discord.gg/$candidate",
            )
            return ((parsed as? LinkParseResult.Internal)?.route as? DeepLinkRoute.Invite)?.code
        }
    }
}

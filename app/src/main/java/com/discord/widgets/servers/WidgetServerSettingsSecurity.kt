package com.discord.widgets.servers

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.AppFragment
import com.discord.app.AppViewModel
import com.discord.app.appViewModels
import com.discord.api.guild.GuildFeature
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.SettingsStrings
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import rx.Observable

/**
 * "Security" server settings — the single switch that requires every moderator to have 2FA enabled
 * before they can take a moderation action.
 *
 * Reference: `com.discord.widgets.servers.WidgetServerSettingsSecurity`. This is the one server
 * setting restricted to the **owner alone**: `Model.get` returns `null` — closing the screen —
 * unless `guild.ownerId == me.id`, regardless of permissions. Toggling it always requires the
 * owner's own 2FA code, so the button opens [ToggleMfaDialog] rather than writing directly.
 */
class WidgetServerSettingsSecurity : AppFragment(R.layout.widget_server_settings_security) {

    private val guildId: Long
        get() = arguments?.getLong(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)
            ?.takeIf { it != INVALID_GUILD_ID }
            ?: mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)

    private val viewModel: ServerSettingsSecurityViewModel by appViewModels {
        ServerSettingsSecurityViewModel(guildId)
    }

    private lateinit var toggleButton: MaterialButton
    private lateinit var discoverableWarning: TextView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.security)
        toggleButton = view.findViewById(R.id.server_settings_security_toggle_mfa_button)
        discoverableWarning = view.findViewById(R.id.server_settings_security_discoverable_warning)

        // A dialog restored from saved state has no guild bound to it yet; the reference drops it
        // and lets the next state emission put a correctly configured one back.
        parentFragmentManager.findFragmentByTag(TAG_TOGGLE_MFA_DIALOG)?.let {
            parentFragmentManager.beginTransaction().remove(it).commitAllowingStateLoss()
        }

        view.findViewById<TextView>(R.id.mfa_description_text).text =
            SettingsStrings.render(view.context, R.string.guild_security_req_mfa_body)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).subscribe(::configureUI)
    }

    private fun configureUI(viewState: ServerSettingsSecurityViewModel.ViewState) {
        when (viewState) {
            is ServerSettingsSecurityViewModel.ViewState.Uninitialized -> Unit
            // Only the owner may be here; losing ownership closes the screen.
            is ServerSettingsSecurityViewModel.ViewState.Unauthorized -> appActivity?.onBackPressed()
            is ServerSettingsSecurityViewModel.ViewState.Loaded -> {
                setActionBarSubtitle(viewState.guildName)
                // Turning the requirement off is the destructive direction, so it is the red one.
                toggleButton.setText(
                    if (viewState.isMfaEnabled) {
                        R.string.guild_security_req_mfa_turn_off
                    } else {
                        R.string.guild_security_req_mfa_turn_on
                    },
                )
                toggleButton.backgroundTintList = ColorStateList.valueOf(
                    ColorCompat.getColor(
                        requireContext(),
                        if (viewState.isMfaEnabled) R.color.status_red_500 else R.color.status_green_600,
                    ),
                )
                // A dialog already on screen when the guild changes has to be re-pointed at the new
                // state, otherwise its confirm button would send the stale level.
                (parentFragmentManager.findFragmentByTag(TAG_TOGGLE_MFA_DIALOG) as? ToggleMfaDialog)
                    ?.configure(viewState.guildId, viewState.isMfaEnabled)
                toggleButton.isEnabled = viewState.canToggle
                toggleButton.setOnClickListener {
                    ToggleMfaDialog.show(
                        parentFragmentManager,
                        viewState.guildId,
                        viewState.isMfaEnabled,
                    )
                }
                discoverableWarning.visibility =
                    if (viewState.isDiscoverable) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * Confirms the change with the owner's 2FA code.
     *
     * The new level is the inverse of the current one, so the same dialog both enables and disables
     * the requirement; only its label and colour change.
     */
    class ToggleMfaDialog : AppDialog(R.layout.widget_server_settings_security_dialog) {

        private val guildId: Long get() = argumentsOrDefault.getLong(ARG_GUILD_ID, INVALID_GUILD_ID)
        private val mfaEnabled: Boolean get() = argumentsOrDefault.getBoolean(ARG_MFA_ENABLED, false)

        private lateinit var codeInput: TextInputLayout
        private lateinit var confirm: MaterialButton
        private lateinit var cancel: MaterialButton

        override fun onViewBound(view: View) {
            super.onViewBound(view)
            codeInput = view.findViewById(R.id.server_settings_security_server_mfa_code)
            confirm = view.findViewById(R.id.server_settings_security_dialog_confirm)
            cancel = view.findViewById(R.id.server_settings_security_dialog_cancel)
            cancel.setOnClickListener { dismiss() }
        }

        override fun onViewBoundOrOnResume() {
            super.onViewBoundOrOnResume()
            configure(guildId, mfaEnabled)
        }

        fun configure(guildId: Long, mfaEnabled: Boolean) {
            // Reached with no guild — nothing this dialog could send would be meaningful.
            if (guildId <= 0L) {
                dismiss()
                return
            }
            if (!::confirm.isInitialized) return
            confirm.setText(if (mfaEnabled) R.string.disable else R.string.enable)
            confirm.backgroundTintList = ColorStateList.valueOf(
                ColorCompat.getColor(
                    confirm.context,
                    if (mfaEnabled) R.color.status_red_500 else R.color.status_green_600,
                ),
            )
            confirm.setOnClickListener {
                val api = RestAPI.apiOrNull ?: return@setOnClickListener
                api.setMfaLevel(
                    guildId,
                    RestAPIParams.GuildMFA(
                        level = if (mfaEnabled) MFA_LEVEL_NONE else MFA_LEVEL_ELEVATED,
                        code = codeInput.getTextOrEmpty(),
                    ),
                )
                    .restSubscribeOn()
                    .ui(this)
                    // A wrong code surfaces through appSubscribe's toast; the dialog stays open so
                    // the owner can retype it.
                    .appSubscribe(ToggleMfaDialog::class.java, context) { dismiss() }
            }
        }

        override fun onDismiss(dialog: DialogInterface) {
            super.onDismiss(dialog)
            hideKeyboard()
        }

        companion object {
            private const val ARG_GUILD_ID = "ARG_GUILD_ID"
            private const val ARG_MFA_ENABLED = "ARG_MFA_ENABLED"
            private const val INVALID_GUILD_ID = -1L

            private const val MFA_LEVEL_NONE = 0
            private const val MFA_LEVEL_ELEVATED = 1

            fun show(fragmentManager: FragmentManager, guildId: Long, mfaEnabled: Boolean) {
                ToggleMfaDialog().apply {
                    arguments = Bundle().apply {
                        putLong(ARG_GUILD_ID, guildId)
                        putBoolean(ARG_MFA_ENABLED, mfaEnabled)
                    }
                }.show(fragmentManager, TAG_TOGGLE_MFA_DIALOG)
            }
        }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INVALID_GUILD_ID = -1L
        private const val TAG_TOGGLE_MFA_DIALOG = "TAG_TOGGLE_MFA_DIALOG"

        fun newInstance(guildId: Long) = WidgetServerSettingsSecurity().apply {
            arguments = Bundle().apply { putLong(INTENT_EXTRA_GUILD_ID, guildId) }
        }

        fun launch(context: Context, guildId: Long) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsSecurity::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }
    }
}

/**
 * State for [WidgetServerSettingsSecurity].
 *
 * Reference: `WidgetServerSettingsSecurity.Model` — owner-only, and `isMfaEnabled` is
 * `guild.mfaLevel > 0` rather than a dedicated flag.
 */
class ServerSettingsSecurityViewModel(
    guildId: Long,
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId),
) : AppViewModel<ServerSettingsSecurityViewModel.ViewState>(ViewState.Uninitialized) {

    data class StoreState(
        val guildId: Long,
        val guildName: String?,
        val isOwner: Boolean,
        val mfaLevel: Int,
        val isDiscoverable: Boolean = false,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()

        /** Not the owner, or the guild is gone. The screen closes itself. */
        object Unauthorized : ViewState()

        data class Loaded(
            val guildId: Long,
            val guildName: String?,
            val isMfaEnabled: Boolean,
            val isDiscoverable: Boolean,
        ) : ViewState() {
            /** A guild in Server Discovery has to keep the requirement on. */
            val canToggle: Boolean get() = !(isMfaEnabled && isDiscoverable)
        }
    }

    init {
        storeStateObservable.computationLatest().distinctUntilChanged().ui(this).subscribe {
            updateViewState(toViewState(it))
        }
    }

    companion object {
        internal fun toViewState(storeState: StoreState): ViewState =
            if (!storeState.isOwner) {
                ViewState.Unauthorized
            } else {
                ViewState.Loaded(
                    guildId = storeState.guildId,
                    guildName = storeState.guildName,
                    isMfaEnabled = storeState.mfaLevel > 0,
                    isDiscoverable = storeState.isDiscoverable,
                )
            }

        fun observeStoreState(guildId: Long): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getUsers().observeMe(),
            StoreStream.getGuilds().observeGuild(guildId),
        ) { meUser, guild ->
            StoreState(
                guildId = guild?.id ?: guildId,
                guildName = guild?.name,
                isOwner = guild != null && guild.ownerId == meUser.id,
                mfaLevel = guild?.mfaLevel ?: 0,
                isDiscoverable = guild?.hasFeature(GuildFeature.DISCOVERABLE) == true,
            )
        }
    }
}

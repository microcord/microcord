package com.discord.widgets.servers

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.appViewModels
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIParams
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.widgets.settings.SettingsStrings
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.discord.app.AppViewModel
import com.discord.stores.StoreStream
import rx.Observable
import rx.subjects.PublishSubject

/**
 * Confirms deleting a server. Only the owner can go through with it, and an owner with MFA on must
 * type a current authenticator code.
 *
 * Reference: `com.discord.widgets.servers.WidgetServerDeleteDialog`.
 */
class WidgetServerDeleteDialog : AppDialog(R.layout.widget_server_delete_dialog) {

    private val viewModel: ServerDeleteViewModel by appViewModels {
        ServerDeleteViewModel(argumentsOrDefault.getLong(INTENT_GUILD_ID))
    }

    private lateinit var header: TextView
    private lateinit var body: TextView
    private lateinit var mfaWrap: LinearLayout
    private lateinit var mfaCode: TextInputLayout
    private lateinit var confirm: MaterialButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        header = view.findViewById(R.id.server_settings_delete_server_header)
        body = view.findViewById(R.id.server_settings_delete_server_text)
        mfaWrap = view.findViewById(R.id.server_settings_delete_server_mfa_wrap)
        mfaCode = view.findViewById(R.id.server_settings_delete_server_mfa_code)
        confirm = view.findViewById(R.id.server_settings_delete_server_confirm)
        view.findViewById<View>(R.id.server_settings_delete_server_cancel)
            .setOnClickListener { dismiss() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).subscribe(::configureUI)
        viewModel.observeEvents().ui(this).subscribe { dismiss() }
    }

    private fun configureUI(viewState: ServerDeleteViewModel.ViewState) {
        when (viewState) {
            is ServerDeleteViewModel.ViewState.Uninitialized -> Unit
            // The guild disappeared under us — most likely someone else deleted it.
            is ServerDeleteViewModel.ViewState.GuildGone -> dismiss()
            is ServerDeleteViewModel.ViewState.Loaded -> {
                val context = requireContext()
                header.text =
                    SettingsStrings.render(context, R.string.delete_server_title, "name" to viewState.guildName)
                body.text =
                    SettingsStrings.render(context, R.string.delete_server_body, "name" to viewState.guildName)
                mfaWrap.visibility = if (viewState.isMfaEnabled) View.VISIBLE else View.GONE
                confirm.isEnabled = viewState.isOwner
                confirm.setOnClickListener { viewModel.deleteGuild(mfaCode.getTextOrEmpty()) }
            }
        }
    }

    companion object {
        private const val INTENT_GUILD_ID = "INTENT_GUILD_ID"

        fun show(fragmentManager: FragmentManager, guildId: Long) {
            WidgetServerDeleteDialog()
                .apply { arguments = Bundle().apply { putLong(INTENT_GUILD_ID, guildId) } }
                .show(fragmentManager, WidgetServerDeleteDialog::class.java.simpleName)
        }
    }
}

/**
 * State and the delete call for [WidgetServerDeleteDialog].
 *
 * The reference's post-delete step enqueues a feedback sheet behind the `2020-12_guild_delete_feedback`
 * experiment; neither `StoreExperiments` nor `widgets/guild_delete_feedback` exists here, so the
 * dialog simply dismisses — which is the un-bucketed behaviour.
 */
class ServerDeleteViewModel(
    private val guildId: Long,
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId),
) : AppViewModel<ServerDeleteViewModel.ViewState>(ViewState.Uninitialized) {

    data class StoreState(val meUser: MeUser, val guild: Guild?)

    sealed class ViewState {
        object Uninitialized : ViewState()
        object GuildGone : ViewState()

        data class Loaded(
            val guildName: String,
            val isOwner: Boolean,
            val isMfaEnabled: Boolean,
        ) : ViewState()
    }

    /** Emitted once the server is gone, so the dialog can close. */
    object Deleted

    private val eventSubject = PublishSubject.create<Deleted>()

    init {
        storeStateObservable.computationLatest().ui(this).subscribe { storeState ->
            val guild = storeState.guild
            updateViewState(
                if (guild == null) {
                    ViewState.GuildGone
                } else {
                    ViewState.Loaded(
                        guildName = guild.name,
                        isOwner = guild.isOwner(storeState.meUser.id),
                        isMfaEnabled = storeState.meUser.mfaEnabled,
                    )
                },
            )
        }
    }

    fun observeEvents(): Observable<Deleted> = eventSubject

    fun deleteGuild(mfaCode: String) {
        val api = RestAPI.apiOrNull ?: return
        api.deleteGuild(guildId, RestAPIParams.DeleteGuild(mfaCode.takeIf { it.isNotEmpty() }))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(ServerDeleteViewModel::class.java) { eventSubject.onNext(Deleted) }
    }

    companion object {
        fun observeStoreState(guildId: Long): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getUsers().observeMe(),
            StoreStream.getGuilds().observeGuild(guildId),
        ) { meUser, guild -> StoreState(meUser, guild) }
    }
}

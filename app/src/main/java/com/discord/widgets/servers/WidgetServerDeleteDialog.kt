package com.discord.widgets.servers

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.appViewModels
import com.discord.databinding.WidgetServerDeleteDialogBinding
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

    private var _binding: WidgetServerDeleteDialogBinding? = null
    private val binding get() = checkNotNull(_binding)

    private val viewModel: ServerDeleteViewModel by appViewModels {
        ServerDeleteViewModel(argumentsOrDefault.getLong(INTENT_GUILD_ID))
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetServerDeleteDialogBinding.bind(view)
        binding.serverSettingsDeleteServerCancel
            .setOnClickListener { dismiss() }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).subscribe(::configureUI)
        viewModel.observeEvents().ui(this).subscribe { dismiss() }
    }

    private fun configureUI(viewState: ServerDeleteViewModel.ViewState) {
        if (_binding == null) return
        when (viewState) {
            is ServerDeleteViewModel.ViewState.Uninitialized -> Unit
            // The guild disappeared under us — most likely someone else deleted it.
            is ServerDeleteViewModel.ViewState.GuildGone -> dismiss()
            is ServerDeleteViewModel.ViewState.Loaded -> {
                val context = requireContext()
                binding.serverSettingsDeleteServerHeader.text =
                    SettingsStrings.render(context, R.string.delete_server_title, "name" to viewState.guildName)
                binding.serverSettingsDeleteServerText.text =
                    SettingsStrings.render(context, R.string.delete_server_body, "name" to viewState.guildName)
                binding.serverSettingsDeleteServerMfaWrap.visibility =
                    if (viewState.isMfaEnabled) View.VISIBLE else View.GONE
                binding.serverSettingsDeleteServerConfirm.isEnabled = viewState.isOwner
                binding.serverSettingsDeleteServerConfirm.setOnClickListener {
                    viewModel.deleteGuild(binding.serverSettingsDeleteServerMfaCode.getTextOrEmpty())
                }
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

/** State and the delete call for [WidgetServerDeleteDialog]. */
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

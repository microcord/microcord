package com.discord.widgets.auth

import com.discord.models.domain.ModelGuildTemplate
import com.discord.models.domain.ModelInvite
import com.discord.stores.StoreAuthentication
import com.discord.stores.StoreGuildTemplates
import com.discord.stores.StoreInstantInvites
import com.discord.stores.StoreInviteSettings
import com.discord.stores.StoreStream
import com.discord.app.AppViewModel
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import rx.Observable

class WidgetAuthLandingViewModel(
    storeObservable: Observable<StoreState>,
    private val fetchInvite: (StoreInviteSettings.InviteCode) -> Unit,
    requestConsentRequired: () -> Unit = {},
) : AppViewModel<WidgetAuthLandingViewModel.ViewState>(ViewState.Empty(null)) {
    private var mostRecentStoreState: StoreState? = null

    init {
        storeObservable.ui(this).appSubscribe(WidgetAuthLandingViewModel::class.java) { handleStoreState(it) }
        requestConsentRequired()
    }

    internal fun handleStoreState(storeState: StoreState) {
        val inviteCode = storeState.inviteCode
        if (inviteCode != null && inviteCode != mostRecentStoreState?.inviteCode) fetchInvite(inviteCode)

        updateViewState(
            when {
                storeState.invite != null -> ViewState.Invite(storeState.invite, storeState.ageGateError)
                storeState.guildTemplateState is StoreGuildTemplates.GuildTemplateState.Resolved ->
                    ViewState.GuildTemplate(storeState.guildTemplateState.guildTemplate, storeState.ageGateError)
                else -> ViewState.Empty(storeState.ageGateError)
            },
        )
        mostRecentStoreState = storeState
    }

    data class StoreState(
        val inviteCode: StoreInviteSettings.InviteCode?,
        val invite: ModelInvite?,
        val guildTemplateState: StoreGuildTemplates.GuildTemplateState,
        val ageGateError: String?,
    )

    sealed class ViewState(open val ageGateError: String?) {
        data class Empty(override val ageGateError: String?) : ViewState(ageGateError)
        data class GuildTemplate(
            val guildTemplate: ModelGuildTemplate,
            override val ageGateError: String?,
        ) : ViewState(ageGateError)
        data class Invite(val invite: ModelInvite, override val ageGateError: String?) : ViewState(ageGateError)
    }

    companion object {
        internal fun observeStoreState(
            inviteCode: StoreInviteSettings.InviteCode?,
            guildTemplateCode: String?,
            authentication: StoreAuthentication = StoreStream.getAuthentication(),
            instantInvites: StoreInstantInvites = StoreStream.getInstantInvites(),
            guildTemplates: StoreGuildTemplates = StoreStream.getGuildTemplates(),
        ): Observable<StoreState> {
            val invite = inviteCode?.let { code ->
                instantInvites.observeInvite(ModelInvite.getInviteStoreKey(code.inviteCode, code.eventId))
                    .map { state -> (state as? StoreInstantInvites.InviteState.Resolved)?.invite }
            } ?: Observable.just(null)
            val guildTemplate = guildTemplateCode?.let { code ->
                guildTemplates.observeGuildTemplate(code)
                    .doOnSubscribe { guildTemplates.maybeInitTemplateState(code) }
            } ?: Observable.just(StoreGuildTemplates.GuildTemplateState.None)

            return Observable.combineLatest(
                invite,
                guildTemplate,
                authentication.observeAgeGateError(),
            ) { resolvedInvite, templateState, ageGateError ->
                StoreState(inviteCode, resolvedInvite, templateState, ageGateError)
            }
        }
    }
}

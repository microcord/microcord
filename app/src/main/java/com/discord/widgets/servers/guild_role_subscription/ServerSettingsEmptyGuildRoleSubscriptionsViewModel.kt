package com.discord.widgets.servers.guild_role_subscription

import com.discord.api.application.Application
import com.discord.api.application.ApplicationType
import com.discord.app.AppViewModel
import com.discord.restapi.RestAPIInterface
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui

class ServerSettingsEmptyGuildRoleSubscriptionsViewModel(
    private val guildId: Long,
    restApi: RestAPIInterface = RestAPI.api,
) : AppViewModel<ServerSettingsEmptyGuildRoleSubscriptionsViewModel.ViewState>(ViewState()) {

    data class ViewState(
        val isLoading: Boolean = true,
        val isMonetizationEnabled: Boolean? = null,
    )

    init {
        restApi.getGuildApplications(guildId, false)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                ServerSettingsEmptyGuildRoleSubscriptionsViewModel::class.java,
                errorHandler = { updateViewState(requireViewState().copy(isLoading = false)) },
                onNext = ::handleMonetizationStatus,
            )
    }

    private fun handleMonetizationStatus(applications: List<Application>) {
        updateViewState(
            requireViewState().copy(
                isLoading = false,
                isMonetizationEnabled = applications.any {
                    it.guildId == guildId && it.type == ApplicationType.GUILD_ROLE_SUBSCRIPTIONS
                },
            ),
        )
    }
}

package com.discord.widgets.hubs

import com.discord.app.AppViewModel
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.utilities.Default
import com.discord.stores.utilities.executeRequest
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.ui

/**
 * Shared by [WidgetHubDomains] and [WidgetHubDomainSearch], which is why it is scoped to the activity
 * rather than to either fragment: picking a school inside the search screen has to advance the list
 * screen underneath it.
 *
 * Picking a hub re-runs the email lookup with that guild pinned; success is what pushes the code
 * screen, and [reset] clears the result afterwards so returning with Back does not push it again.
 */
class WidgetHubDomainsViewModel(
    private val restAPI: RestAPIInterface = RestAPI.api,
) : AppViewModel<DomainsState>(DomainsState()) {

    fun onGuildClicked(guildId: Long, email: String) {
        restAPI.verifyEmail(
            RestAPIParams.VerifyEmail(
                email = email,
                guildId = guildId,
                allowMultipleGuilds = true,
            ),
        )
            .unwrapBody()
            .ui(this)
            .executeRequest { async ->
                updateViewState(requireViewState().copy(selectedGuildId = guildId, verifyEmailAsync = async))
            }
    }

    fun reset() {
        updateViewState(requireViewState().copy(selectedGuildId = null, verifyEmailAsync = Default))
    }
}

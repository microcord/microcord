package com.discord.widgets.hubs

import com.discord.app.AppViewModel
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.utilities.executeRequest
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.ui

/**
 * Redeems the emailed hub verification code, and resends it on request.
 */
class WidgetHubAuthenticationViewModel(
    private val email: String,
    private val guildId: Long?,
    private val restAPI: RestAPIInterface = RestAPI.api,
) : AppViewModel<HubAuthenticationState>(HubAuthenticationState()) {

    fun resendEmail() {
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
                updateViewState(requireViewState().copy(resendEmailAsync = async))
            }
    }

    fun verifyCode(code: String) {
        restAPI.verifyEmailCode(RestAPIParams.VerifyEmailCode(email = email, code = code, guildId = guildId))
            .unwrapBody()
            .ui(this)
            .executeRequest { async ->
                updateViewState(requireViewState().copy(authenticationAsync = async))
            }
    }
}

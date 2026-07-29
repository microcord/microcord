package com.discord.widgets.hubs

import com.discord.app.AppViewModel
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.utilities.executeRequest
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.ui

/**
 * Redeems the emailed hub verification code, and resends it on request.
 *
 * @param multiDomainEnabled see [WidgetHubEmailViewModel]; it is forwarded on the resend so the
 *   second email is generated the same way as the first.
 */
class WidgetHubAuthenticationViewModel(
    private val email: String,
    private val guildId: Long?,
    private val restAPI: RestAPIInterface = RestAPI.api,
    private val multiDomainEnabled: Boolean = isMultiDomainEnabled(),
) : AppViewModel<HubAuthenticationState>(HubAuthenticationState()) {

    fun resendEmail() {
        restAPI.verifyEmail(
            RestAPIParams.VerifyEmail(
                email = email,
                guildId = guildId,
                allowMultipleGuilds = multiDomainEnabled,
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

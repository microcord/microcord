package com.discord.widgets.hubs

import com.discord.app.AppViewModel
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.utilities.Default
import com.discord.stores.utilities.executeRequest
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.ui

/**
 * Owns the school-email lookup behind [WidgetHubEmailFlow].
 *
 * One request drives the whole screen: submitting an address either matches a hub (the flow shows the
 * "check your email" page), matches several (the flow pushes the picker), or matches none (the flow
 * shows the waitlist page).
 *
 * @param multiDomainEnabled the `2021-08_hub_multi_domain_mobile` experiment. It decides whether the
 *   server may answer with several matching guilds instead of one.
 */
class WidgetHubEmailViewModel(
    private val restAPI: RestAPIInterface = RestAPI.api,
    private val multiDomainEnabled: Boolean = isMultiDomainEnabled(),
) : AppViewModel<HubEmailState>(HubEmailState()) {

    /** Returns to the email input page, discarding a finished lookup. */
    fun reset() {
        updateViewState(requireViewState().copy(verifyEmailAsync = Default))
    }

    /** Records the waitlist signup made on the pushed waitlist screen. */
    fun setHubWaitlistResult(waitlistResult: HubWaitlistResult) {
        updateViewState(requireViewState().copy(waitlistResult = waitlistResult))
    }

    fun submitEmail(email: String) {
        restAPI.verifyEmail(
            RestAPIParams.VerifyEmail(
                email = email,
                guildId = null,
                allowMultipleGuilds = multiDomainEnabled,
                // useVerificationCode keeps its default (true): every hub call site leaves it out.
            ),
        )
            .unwrapBody()
            .ui(this)
            .executeRequest { async ->
                updateViewState(requireViewState().copy(verifyEmailAsync = async))
            }
    }
}

package com.discord.widgets.hubs

import com.discord.app.AppViewModel
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.utilities.executeRequest
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.ui

/** Files a waitlist entry for a school that has no hub yet. */
class WidgetHubWaitlistViewModel(
    private val email: String,
    private val restAPI: RestAPIInterface = RestAPI.api,
) : AppViewModel<HubWaitlistState>(HubWaitlistState()) {

    fun joinWaitlist(school: String) {
        restAPI.joinHubWaitlist(RestAPIParams.HubWaitlist(email = email, school = school))
            .ui(this)
            .executeRequest { async ->
                viewState?.let { updateViewState(it.copy(waitlistAsync = async)) }
            }
    }
}

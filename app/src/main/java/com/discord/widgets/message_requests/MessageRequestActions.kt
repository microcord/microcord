package com.discord.widgets.message_requests

import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams

internal fun RestAPIInterface.setMessageRequestAccepted(channelId: Long, accepted: Boolean) =
    if (accepted) {
        updateMessageRequest(channelId, RestAPIParams.MessageRequestConsentStatus(2))
    } else {
        rejectMessageRequest(channelId)
    }

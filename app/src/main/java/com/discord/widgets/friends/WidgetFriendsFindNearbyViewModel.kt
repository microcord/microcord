package com.discord.widgets.friends

import com.discord.app.AppViewModel

/**
 * Remembers which user the nearby screen was trying to add when a captcha interrupted it.
 *
 * The captcha is a separate activity, so [WidgetFriendsFindNearby] can be destroyed and recreated
 * between issuing the request and receiving the solved token. Parking the target here — the only
 * thing this view model does, exactly as in the reference — is what lets the retry go to the right
 * person instead of silently doing nothing.
 *
 * It publishes no view state; the screen's state comes from `NearbyManager` plus the stores.
 */
class WidgetFriendsFindNearbyViewModel : AppViewModel<Unit>() {
    var username: String? = null
    var discriminator: Int? = null
}

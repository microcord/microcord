package com.discord.widgets.hubs

import com.discord.api.hubs.WaitlistSignup
import com.discord.stores.utilities.Default
import com.discord.stores.utilities.RestCallState

data class HubWaitlistState(val waitlistAsync: RestCallState<WaitlistSignup> = Default)

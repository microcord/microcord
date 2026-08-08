package com.discord.stores

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationRouteMatrixTest {
    @Test
    fun resolvesEveryColdStartAuthGateInPriorityOrder() {
        val cases = listOf(
            AuthNavigationState(false, false) to MainRoute.Loading,
            AuthNavigationState(true, false) to MainRoute.LoggedOut,
            AuthNavigationState(true, true) to MainRoute.LoggedIn,
            AuthNavigationState(true, true, RequiredAction.UPDATE_PASSWORD) to
                MainRoute.Required(RequiredAction.UPDATE_PASSWORD),
            AuthNavigationState(true, true, ageGate = StoreNavigation.AgeGate.REGISTER_AGE_GATE) to
                MainRoute.AgeGated(StoreNavigation.AgeGate.REGISTER_AGE_GATE),
            AuthNavigationState(
                true,
                true,
                RequiredAction.AGREEMENTS,
                StoreNavigation.AgeGate.NSFW_CHANNEL_AGE_GATE,
            ) to MainRoute.Required(RequiredAction.AGREEMENTS),
        )

        cases.forEach { (state, expected) -> assertEquals(expected, StoreNavigation.resolveMainRoute(state)) }
    }
}

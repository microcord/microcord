package com.discord.widgets.nux

import com.discord.stores.StoreNux
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewUserOnboardingTest {
    @Test fun currentFlowSkipsSatisfiedSystemStepsAndPreservesRequiredOrder() {
        val done = mutableSetOf<StoreNux.NewUserStep>()
        assertEquals(
            StoreNux.NewUserStep.CONTACT_SYNC,
            WidgetNewUserOnboarding.nextStep(done, hasAvatar = true, hasNotifications = true),
        )
        done += StoreNux.NewUserStep.CONTACT_SYNC
        assertEquals(
            StoreNux.NewUserStep.DISCOVERABILITY,
            WidgetNewUserOnboarding.nextStep(done, hasAvatar = true, hasNotifications = true),
        )
        done += StoreNux.NewUserStep.DISCOVERABILITY
        assertEquals(
            StoreNux.NewUserStep.INVITE,
            WidgetNewUserOnboarding.nextStep(done, hasAvatar = true, hasNotifications = true),
        )
        done += StoreNux.NewUserStep.INVITE
        assertNull(WidgetNewUserOnboarding.nextStep(done, hasAvatar = true, hasNotifications = true))
    }

    @Test fun missingAvatarAndNotificationAreFirst() {
        assertEquals(
            StoreNux.NewUserStep.AVATAR,
            WidgetNewUserOnboarding.nextStep(emptySet(), hasAvatar = false, hasNotifications = false),
        )
        assertEquals(
            StoreNux.NewUserStep.NOTIFICATIONS,
            WidgetNewUserOnboarding.nextStep(
                setOf(StoreNux.NewUserStep.AVATAR),
                hasAvatar = false,
                hasNotifications = false,
            ),
        )
    }
}

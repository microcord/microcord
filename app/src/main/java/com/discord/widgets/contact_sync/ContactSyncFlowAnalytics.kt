package com.discord.widgets.contact_sync

import com.discord.stores.StoreStream
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory

/**
 * Tracks a single pass through the contact-sync funnel.
 *
 * The flow is a linear sequence of steps, so every event carries the step the user came *from*, the
 * step they moved *to*, and how long the previous step took. Instances are per-flow, not per-screen:
 * the view model owns one and every screen reports through it, which is why the "from" step is
 * remembered here rather than passed in.
 *
 * PRIVACY: nothing about the user's contacts leaves this class. The only counts reported are
 * `num_contacts_found` / `num_contacts_added`, which the view model supplies.
 *
 * @param isOnboarding also emits the new-user-onboarding event for each step.
 */
class ContactSyncFlowAnalytics @JvmOverloads constructor(
    val isOnboarding: Boolean = false,
    val clock: Clock = ClockFactory.get(),
    private val analytics: ContactSyncAnalytics = ContactSyncDependencies.analytics,
) {
    private var lastStep: String = LANDING_STEP
    private var lastStepTimestamp: Long = clock.currentTimeMillis()

    @JvmOverloads
    fun trackFlowStep(
        toStep: String,
        skip: Boolean,
        back: Boolean,
        additionalProps: Map<String, Any>? = null,
    ) {
        val now = clock.currentTimeMillis()
        analytics.relationshipSyncFlow(
            CONTACT_SYNC_FLOW_KEY,
            lastStep,
            toStep,
            ((now - lastStepTimestamp) / MILLIS_PER_SECOND).toInt(),
            skip,
            back,
            additionalProps,
        )
        if (isOnboarding) {
            analytics.newUserOnboarding(CONTACT_SYNC_FLOW_KEY, lastStep, toStep, lastStepTimestamp, skip)
        }
        lastStepTimestamp = now
        lastStep = toStep
    }

    /**
     * Closes the funnel. Note that the reference does **not** advance [lastStep]/[lastStepTimestamp]
     * here — the flow is over — so calling it twice reports the same duration twice.
     */
    @JvmOverloads
    fun trackEnd(skip: Boolean, additionalProps: Map<String, Any>? = null) {
        val now = clock.currentTimeMillis()
        analytics.relationshipSyncFlow(
            CONTACT_SYNC_FLOW_KEY,
            lastStep,
            COMPLETE_STEP,
            ((now - lastStepTimestamp) / MILLIS_PER_SECOND).toInt(),
            skip,
            false,
            additionalProps,
        )
        if (isOnboarding) {
            analytics.newUserOnboarding(
                CONTACT_SYNC_FLOW_KEY,
                lastStep,
                COMPLETE_STEP,
                lastStepTimestamp,
                skip,
            )
        }
    }

    companion object {
        private const val CONTACT_SYNC_FLOW_KEY = "Contact Sync"
        private const val LANDING_STEP = "Landing"
        private const val COMPLETE_STEP = "Complete"
        private const val FLOW_INITIALIZED_STEP = "Flow Initialized"
        private const val MILLIS_PER_SECOND = 1000L

        /**
         * Opens the funnel, before any [ContactSyncFlowAnalytics] instance exists.
         *
         * `has_phone_number` is a boolean, never the number itself.
         */
        @JvmStatic
        @JvmOverloads
        fun trackStart(
            isOnboarding: Boolean = false,
            additionalProps: Map<String, Any>? = null,
            analytics: ContactSyncAnalytics = ContactSyncDependencies.analytics,
        ) {
            val properties = additionalProps.orEmpty().toMutableMap()
            properties["has_phone_number"] = StoreStream.getUsers().getMe().phoneNumber != null
            analytics.relationshipSyncFlow(
                CONTACT_SYNC_FLOW_KEY,
                FLOW_INITIALIZED_STEP,
                LANDING_STEP,
                0,
                false,
                false,
                properties,
            )
            if (isOnboarding) {
                analytics.newUserOnboarding(
                    CONTACT_SYNC_FLOW_KEY,
                    FLOW_INITIALIZED_STEP,
                    LANDING_STEP,
                    null,
                    false,
                )
            }
        }
    }
}

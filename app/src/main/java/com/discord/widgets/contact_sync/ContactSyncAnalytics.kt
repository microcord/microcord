package com.discord.widgets.contact_sync

import com.discord.utilities.analytics.AnalyticsUtils
import com.discord.utilities.time.ClockFactory

/**
 * The analytics surface the contact-sync flow uses.
 *
 * The six reference calls are kept behind this narrow interface so tests can capture the funnel
 * without enabling the process analytics sink. Their signatures were verified with `javap -p`:
 *
 * ```
 * relationshipSyncFlow(String, String, String, int, boolean, boolean, Map<String, ?>)
 * newUserOnboarding(String, String, String, Long, boolean)
 * contactSyncToggled(boolean, boolean, boolean)
 * nameSubmitted(int, int)
 * permissionsRequested(String)
 * permissionsAcked(String, boolean)
 * ```
 *
 * PRIVACY: no method here takes a phone number, an e-mail address, a contact name or a contact
 * hash, and none may ever be added. [nameSubmitted] deliberately takes the *word count* and
 * *character length* of the name the user typed rather than the name itself — that is the reference
 * behaviour and it is also the only shape that is safe to send.
 */
interface ContactSyncAnalytics {

    fun relationshipSyncFlow(
        flow: String,
        fromStep: String,
        toStep: String,
        durationSeconds: Int,
        skip: Boolean,
        back: Boolean,
        additionalProperties: Map<String, Any>?,
    )

    fun newUserOnboarding(
        flow: String,
        fromStep: String,
        toStep: String,
        stepStartedAtMs: Long?,
        skip: Boolean,
    )

    fun contactSyncToggled(enabled: Boolean, allowPhone: Boolean, allowEmail: Boolean)

    /** @param wordCount words in the submitted name; @param characterCount its length. */
    fun nameSubmitted(wordCount: Int, characterCount: Int)

    fun permissionsRequested(permission: String)

    fun permissionsAcked(permission: String, granted: Boolean)

    /** Exact 126.21 event names and properties, routed through the shared analytics pipeline. */
    object Production : ContactSyncAnalytics {
        private val tracker get() = AnalyticsUtils.Tracker.instance

        override fun relationshipSyncFlow(
            flow: String,
            fromStep: String,
            toStep: String,
            durationSeconds: Int,
            skip: Boolean,
            back: Boolean,
            additionalProperties: Map<String, Any>?,
        ) {
            tracker.track(
                "relationship_sync_flow",
                additionalProperties.orEmpty() + mapOf(
                    "flow_type" to flow,
                    "from_step" to fromStep,
                    "to_step" to toStep,
                    "skip" to skip,
                    "back" to back,
                    "seconds_on_from_step" to durationSeconds,
                ),
            )
        }

        override fun newUserOnboarding(
            flow: String,
            fromStep: String,
            toStep: String,
            stepStartedAtMs: Long?,
            skip: Boolean,
        ) {
            val properties = mutableMapOf<String, Any>(
                "flow_type" to flow,
                "from_step" to fromStep,
                "to_step" to toStep,
                "skip" to skip,
            )
            stepStartedAtMs?.let {
                properties["seconds_on_from_step"] = ClockFactory.get().currentTimeMillis() - it
            }
            tracker.track("nuo_transition", properties)
        }

        override fun contactSyncToggled(enabled: Boolean, allowPhone: Boolean, allowEmail: Boolean) {
            tracker.track(
                "contact_sync_toggled",
                mapOf(
                    "is_enabled" to enabled,
                    "am_discoverable_phone" to allowPhone,
                    "am_discoverable_email" to allowEmail,
                ),
            )
        }

        override fun nameSubmitted(wordCount: Int, characterCount: Int) {
            tracker.track("name_submitted", mapOf("num_words" to wordCount, "num_chars" to characterCount))
        }

        override fun permissionsRequested(permission: String) {
            tracker.track("permissions_requested", mapOf("type" to permission))
        }

        override fun permissionsAcked(permission: String, granted: Boolean) {
            tracker.track(
                "permissions_acked",
                mapOf("type" to permission, "action" to if (granted) "accepted" else "denied"),
            )
        }
    }

    /** Discards every event; used by focused tests. */
    object None : ContactSyncAnalytics {
        override fun relationshipSyncFlow(
            flow: String,
            fromStep: String,
            toStep: String,
            durationSeconds: Int,
            skip: Boolean,
            back: Boolean,
            additionalProperties: Map<String, Any>?,
        ) = Unit

        override fun newUserOnboarding(
            flow: String,
            fromStep: String,
            toStep: String,
            stepStartedAtMs: Long?,
            skip: Boolean,
        ) = Unit

        override fun contactSyncToggled(enabled: Boolean, allowPhone: Boolean, allowEmail: Boolean) = Unit

        override fun nameSubmitted(wordCount: Int, characterCount: Int) = Unit

        override fun permissionsRequested(permission: String) = Unit

        override fun permissionsAcked(permission: String, granted: Boolean) = Unit
    }
}

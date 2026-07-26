package com.discord.widgets.contact_sync

/**
 * The analytics surface the contact-sync flow uses.
 *
 * The reference calls `com.discord.utilities.analytics.AnalyticsTracker` directly. That object does
 * not exist in this reconstruction (there is no `utilities/analytics` package at all), so rather
 * than delete the instrumentation — which would silently lose the funnel this whole flow exists to
 * measure — the six events it needs are declared here with the reference signatures, verified with
 * `javap -p`:
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
 * When `AnalyticsTracker` is reconstructed, install an implementation that forwards to it via
 * [ContactSyncDependencies.analytics] and delete this interface.
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

    /** Discards every event. The installed default until `AnalyticsTracker` exists. */
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

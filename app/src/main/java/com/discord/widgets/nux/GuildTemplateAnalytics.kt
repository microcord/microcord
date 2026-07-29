package com.discord.widgets.nux

import com.discord.utilities.analytics.AnalyticsTracker

/**
 * The named steps of the post-registration onboarding funnel.
 *
 * The strings are wire values sent to the analytics backend, so they are transcribed exactly —
 * changing one silently breaks the funnel rather than failing a build.
 */
object GuildTemplateAnalytics {

    private const val FLOW_TYPE_POST_REG = "Mobile NUX Post Reg"
    private const val STEP_FRIENDS_LIST = "Friends List"

    internal const val IN_APP_LOCATION_TEMPLATE = "Create or Join Guild Modal"
    internal const val STEP_CREATION_INTENT = "Server Intent Discovery"
    internal const val STEP_GUILD_CREATE = "Guild Create"
    internal const val STEP_GUILD_JOIN = "Guild Join"
    internal const val STEP_GUILD_TEMPLATE = "Guild Template"
    internal const val STEP_REGISTRATION = "Registration"

    internal fun postRegistrationTransition(fromStep: String, toStep: String) =
        track(fromStep, toStep, null, false)

    /** Reports abandoning the flow, which always lands the user on the friends list. */
    internal fun postRegistrationSkip(fromStep: String) =
        track(fromStep, STEP_FRIENDS_LIST, null, true)

    internal fun track(fromStep: String, toStep: String, startedAtMillis: Long?, didSkip: Boolean) {
        val properties = mutableMapOf<String, Any>(
            "flow_type" to FLOW_TYPE_POST_REG,
            "from_step" to fromStep,
            "to_step" to toStep,
            "skip" to didSkip,
        )
        startedAtMillis?.let {
            properties["seconds_on_from_step"] = System.currentTimeMillis() - it
        }
        AnalyticsTracker.tracker.track("nuo_transition", properties)
    }
}

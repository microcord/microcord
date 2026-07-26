package com.discord.widgets.nux

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

    /**
     * Reports a step-to-step move within the onboarding flow.
     *
     * Stands in for `AnalyticsTracker.newUserOnboarding`, which this project has not reconstructed;
     * whoever builds it installs [onboardingTracker] and both functions below become live.
     */
    internal fun postRegistrationTransition(fromStep: String, toStep: String) {
        onboardingTracker?.invoke(FLOW_TYPE_POST_REG, fromStep, toStep, null, false)
    }

    /** Reports abandoning the flow, which always lands the user on the friends list. */
    internal fun postRegistrationSkip(fromStep: String) {
        onboardingTracker?.invoke(FLOW_TYPE_POST_REG, fromStep, STEP_FRIENDS_LIST, null, true)
    }

    /** `(flowType, fromStep, toStep, startedAtMillis, didSkip)`. */
    @JvmStatic
    var onboardingTracker: ((String, String, String, Long?, Boolean) -> Unit)? = null
}

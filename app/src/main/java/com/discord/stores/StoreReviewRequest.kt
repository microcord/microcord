package com.discord.stores

import android.content.Context
import android.content.SharedPreferences
import com.discord.models.domain.ModelPayload
import com.discord.utilities.time.Clock

/**
 * Decides whether to ask the user to rate the app, and remembers the answer forever.
 *
 * The gate is deliberately narrow, because a rating prompt shown to the wrong user costs a
 * one-star review: the account must be verified, must be in at least one guild with real activity,
 * must have had the app installed for over ten days, must not have been asked since the current
 * revision, and must not have already accepted. Only then is a low-urgency
 * [StoreNotices.PassiveNotice] queued — the notice machinery still decides *when* it surfaces.
 */
class StoreReviewRequest(
    private val clock: Clock,
    private val notices: StoreNotices,
    private val sessionDurablePreferences: SharedPreferences,
    /** `AnalyticsTracker.reviewRequestTriggered`; no analytics layer exists in this project yet. */
    private val trackReviewRequestTriggered: () -> Unit = {},
    private val trackReviewRequestAccepted: () -> Unit = {},
    private val trackReviewRequestDismissed: () -> Unit = {},
) : Store() {

    private var requestedReviewRevision: Int = NEVER_REQUESTED
    private var hasUserAcceptedReviewRequest: Boolean = false

    override fun init(context: Context) {
        super.init(context)
        requestedReviewRevision =
            sessionDurablePreferences.getInt(CACHE_KEY_REVIEW_REVISION, requestedReviewRevision)
        hasUserAcceptedReviewRequest =
            sessionDurablePreferences.getBoolean(CACHE_KEY_HAS_ACCEPTED, hasUserAcceptedReviewRequest)
    }

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        // "Real guild" is a proxy for "actually uses Discord" — five members is enough to rule out
        // the empty test server a brand new account makes for itself.
        val isInRealGuild = payload.guilds.orEmpty().any { it.memberCount >= MINIMUM_GUILD_MEMBER_COUNT }
        val isVerified = payload.me?.verified ?: false
        if (!isInRealGuild || !isVerified) return

        if (hasUserAcceptedReviewRequest) return
        if (requestedReviewRevision >= REVIEW_REQUEST_REVISION) return
        if (clock.currentTimeMillis() <= notices.firstUseTimestamp + MINIMUM_INSTALL_AGE_MS) return

        notices.requestToShow(
            StoreNotices.Dialog.Type.REQUEST_RATING_MODAL.buildPassiveNotice(
                sinceLastPeriodMs = SINCE_LAST_PERIOD_MS,
                persist = true,
            ),
        )
    }

    /**
     * Records that the prompt was actually shown. Stamping the current revision — not a boolean —
     * is what lets a later release ask again without asking twice in the same one.
     */
    fun onReviewRequestShown() {
        requestedReviewRevision = REVIEW_REQUEST_REVISION
        sessionDurablePreferences.edit()
            .putInt(CACHE_KEY_REVIEW_REVISION, REVIEW_REQUEST_REVISION)
            .apply()
        notices.markSeen(StoreNotices.Dialog.Type.REQUEST_RATING_MODAL)
        trackReviewRequestTriggered()
    }

    /** Terminal: an account that has left a review is never asked again, on any revision. */
    fun onUserAcceptedRequest() {
        hasUserAcceptedReviewRequest = true
        sessionDurablePreferences.edit().putBoolean(CACHE_KEY_HAS_ACCEPTED, true).apply()
        trackReviewRequestAccepted()
    }

    fun onUserDismissedRequest() = trackReviewRequestDismissed()

    companion object {
        private const val MINIMUM_GUILD_MEMBER_COUNT = 5
        private const val MINIMUM_INSTALL_AGE_MS = 864_000_000L
        private const val SINCE_LAST_PERIOD_MS = 31_536_000_000L

        /** The app revision this prompt belongs to; bumping it re-arms the prompt. */
        private const val REVIEW_REQUEST_REVISION = 693

        private const val NEVER_REQUESTED = -1
        private const val CACHE_KEY_REVIEW_REVISION = "CACHE_KEY_VIEWED_REVIEW_REQUEST_REVISION"
        private const val CACHE_KEY_HAS_ACCEPTED = "CACHE_KEY_HAS_ACCEPTED_REVIEW_REQUEST"
    }
}

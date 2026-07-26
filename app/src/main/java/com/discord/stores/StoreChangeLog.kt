package com.discord.stores

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.discord.R
import com.discord.utilities.string.getStringByLocale
import com.discord.utilities.time.Clock

/**
 * Shows the "what's new" sheet once per release, to users who have been around long enough to care.
 *
 * The changelog has no server component at all — it is a set of string resources baked into the
 * build, and `change_log_md_date` doubles as its version key. This store's whole job is deciding
 * whether the current build's changelog has already been seen, and queuing a
 * [StoreNotices.Notice] if not.
 *
 * Three ways it declines to show:
 *  - The changelog has not been translated into the user's language (the localised body is
 *    byte-identical to the English one), because an English wall of text in a Japanese client is
 *    worse than nothing.
 *  - The account, or this install, is less than five days old — a brand new user does not want a
 *    list of fixes to a product they have not used.
 *  - There is no previously-seen version at all. That case marks the current version seen and
 *    shows nothing, so a fresh install never opens onto a changelog.
 */
class StoreChangeLog(
    private val clock: Clock,
    private val notices: StoreNotices,
    private val users: StoreUser,
    private val userSettingsSystem: StoreUserSettingsSystem,
    /**
     * Reference parity only. The reference threads an experiment bucket through
     * [shouldShowChangelog] and [openChangeLog] to pick "experiment" string variants, but R8 folded
     * the bucket to a constant `null` in 126.21 — every experiment branch is dead in the shipped
     * build, and the experiment's name is not recoverable from the binary. Kept so the dependency
     * is visible; see [getChangelogExperimentString] for the surviving half of the mechanism.
     */
    private val experiments: StoreExperiments,
    private val dispatcher: Dispatcher,
    private val preferences: SharedPreferences,
    /**
     * Launches `WidgetChangeLog`, which has not been reconstructed. Until the UI layer installs a
     * launcher the notice still queues and marks itself seen; nothing is presented.
     */
    private val launchChangeLog: (
        context: Context,
        date: String,
        revision: String,
        video: String,
        body: String,
    ) -> Unit = { _, _, _, _, _ -> },
) : Store() {

    private lateinit var app: Application

    private var lastSeenChangeLogVersion: String?
        get() = preferences.getString(CACHE_KEY_VIEWED_VERSION, "")
        set(value) {
            preferences.edit().putString(CACHE_KEY_VIEWED_VERSION, value).apply()
        }

    @StoreThread
    fun init(app: Application) {
        super.init(app)
        this.app = app
    }

    /**
     * Hops off the store thread and straight back on.
     *
     * The reference launches this on `Dispatchers.Default` purely to get off the dispatch that is
     * handling `READY`, then re-schedules onto the store thread; the net effect is deferring the
     * changelog decision to a later dispatch, which is exactly what this does without pulling in
     * coroutines.
     */
    @StoreThread
    fun handleConnectionOpen() = dispatcher.schedule { handlePostConnectionOpen() }

    @StoreThread
    fun handlePostConnectionOpen() {
        if (!::app.isInitialized) return
        // "en-US" -> "en": the changelog is translated per language, not per region.
        val targetLanguage = userSettingsSystem.getLocale().split("-")[0]
        if (shouldShowChangelog(app, users.getMe().id, targetLanguage, experimentBucket = null)) {
            notices.requestToShow(createChangeLogNotice())
        }
    }

    fun markSeen(currentVersion: String) {
        lastSeenChangeLogVersion = currentVersion
        notices.markSeen(NOTICE_NAME)
    }

    @JvmOverloads
    fun openChangeLog(context: Context, fromSettings: Boolean = false) {
        // `fromSettings` selects the sheet's exit affordance (Back vs Close) in the reference's
        // WidgetChangeLogSpecial. Both of its branches are unreachable in the shipped build — the
        // experiment bucket they test is a folded `null` — so the plain changelog is always what
        // opens, and the parameter is carried for call-site parity.
        launchChangeLog(
            context,
            context.getString(R.string.change_log_md_date),
            context.getString(R.string.change_log_md_revision),
            context.getString(R.string.change_log_md_video),
            context.getString(R.string.change_log_md_body),
        )
    }

    internal fun shouldShowChangelog(
        context: Context,
        userId: Long,
        targetLanguage: String,
        experimentBucket: Int?,
    ): Boolean {
        val bodyResId = if (experimentBucket == 1) {
            context.resources.getIdentifier(EXPERIMENT_BODY_RESOURCE, "string", context.packageName)
        } else {
            R.string.change_log_md_body
        }
        // Untranslated: the localised body resolves to the same text as English.
        if (targetLanguage != "en" &&
            context.getStringByLocale(bodyResId, "en") == context.getStringByLocale(bodyResId, targetLanguage)
        ) {
            return false
        }

        val changeLogDate = context.getString(R.string.change_log_md_date)
        val version = if (experimentBucket == 1) {
            getChangelogExperimentString(context, EXPERIMENT_DATE_RESOURCE, changeLogDate)
        } else {
            changeLogDate
        }

        if (lastSeenChangeLogVersion.isNullOrBlank() || isTooYoung(userId)) {
            markSeen(version)
            return false
        }
        return version != lastSeenChangeLogVersion
    }

    /**
     * Not persisted, not [StoreNotices.Notice.autoMarkSeen]: `WidgetChangeLog` calls [markSeen]
     * itself when it opens, which is what writes the version. The notice's own `persist` flag is
     * set so a version that *was* shown is not re-queued in the same install.
     */
    private fun createChangeLogNotice(): StoreNotices.Notice = StoreNotices.Notice(
        name = NOTICE_NAME,
        // Fixed and absurdly small, so the changelog always sorts ahead of anything requested
        // during this session; it is the one notice that is about the app rather than the account.
        requestedShowTimestamp = FIXED_REQUEST_TIMESTAMP,
        priority = StoreNotices.PRIORITY_HIGH,
        persist = true,
        sinceLastPeriodMs = SINCE_LAST_PERIOD_MS,
        show = { activity ->
            openChangeLog(activity)
            true
        },
    )

    /**
     * @return the string resource named [name] if this build ships one, else [fallback].
     *
     * Resolved by name because the experiment variants are optional resources that only exist in
     * builds enrolled in the changelog experiment.
     */
    private fun getChangelogExperimentString(context: Context, name: String, fallback: String): String {
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        return if (id > 0) context.getString(id) else fallback
    }

    /** True while *either* the account or this install is younger than five days. */
    private fun isTooYoung(userId: Long): Boolean {
        val accountCreatedAt = (userId ushr 22) + DISCORD_EPOCH
        val now = clock.currentTimeMillis()
        return now < accountCreatedAt + MIN_VIEW_AGE_MS ||
            now < notices.firstUseTimestamp + MIN_VIEW_AGE_MS
    }

    companion object {
        const val NOTICE_NAME = "CHANGE_LOG"

        private const val CACHE_KEY_VIEWED_VERSION = "CACHE_KEY_VIEWED_CHANGE_LOG_VERSION"
        private const val MIN_VIEW_AGE_MS = 432_000_000L
        private const val SINCE_LAST_PERIOD_MS = 604_800_000L
        private const val FIXED_REQUEST_TIMESTAMP = 1337L
        private const val DISCORD_EPOCH = 1_420_070_400_000L
        private const val EXPERIMENT_BODY_RESOURCE = "change_log_md_experiment_body"
        private const val EXPERIMENT_DATE_RESOURCE = "change_log_md_experiment_date"
    }
}

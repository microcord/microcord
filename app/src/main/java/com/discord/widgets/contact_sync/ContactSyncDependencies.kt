package com.discord.widgets.contact_sync

import com.discord.stores.StoreContactSync
import com.discord.stores.StoreUserConnections

/**
 * Everything the contact-sync flow needs that `StoreStream` does not publish yet.
 *
 * The reference reaches all of this through `StoreStream.Companion` and two singletons. In this
 * reconstruction each of the following is either absent or unreachable, so the flow takes it from
 * here instead of pretending it exists:
 *
 * | Reference call                                   | Field here                    | Why |
 * | ------------------------------------------------ | ----------------------------- | --- |
 * | `StoreStream.getContactSync()`                    | [contactSync]                 | `StoreContactSync` exists but `StoreStream` never constructs it — the class has **no reference anywhere else in the repo** |
 * | `StoreStream.getUserConnections()`                | [userConnections]             | The store is constructed (`StoreStream.kt:403`) but private, with no `@JvmStatic` accessor |
 * | `StoreStream.getNux().setContactSyncCompleted(…)` | [onOnboardingCompleted]       | `StoreNux` does not exist |
 * | `AnalyticsTracker.INSTANCE`                       | [analytics]                   | `utilities/analytics` does not exist |
 * | `ContactsProviderUtils.INSTANCE`                  | [contactsProvider]            | `utilities/contacts` does not exist; the port lives in [AndroidContactsProvider] |
 * | `GrowthTeamFeatures.isPhoneVerifyCaptchaEnabled()`| [isPhoneVerifyCaptchaEnabled] | `utilities/features` does not exist, and `StoreExperiments` is private on `StoreStream` |
 *
 * None of these is a silent no-op. Each has a *visible* consequence while unset, listed on the
 * field, and the screen degrades rather than lying:
 *
 *  - no [contactSync] → the upsell's "don't show this again" is not remembered across launches;
 *  - no [userConnections] → the flow always creates a fresh `contacts` connection instead of
 *    updating an existing one, and the country code falls back to the default;
 *  - no [onOnboardingCompleted] → finishing during onboarding does not tick the onboarding step;
 *  - default [isPhoneVerifyCaptchaEnabled] → a captcha-gated phone submission surfaces the API
 *    error instead of opening a challenge.
 *
 * Install the real values from `StoreStream.init` (or `App.onCreate`) and delete this object.
 */
object ContactSyncDependencies {

    /** Persists the two upsell dismissals and the daily background upload timestamp. */
    @Volatile
    @JvmStatic
    var contactSync: StoreContactSync? = null

    /** Supplies the existing `contacts` connected account, if the user already linked one. */
    @Volatile
    @JvmStatic
    var userConnections: StoreUserConnections? = null

    /** `StoreNux.setContactSyncCompleted(true)`, invoked when an onboarding flow finishes. */
    @Volatile
    @JvmStatic
    var onOnboardingCompleted: () -> Unit = {}

    @Volatile
    @JvmStatic
    var analytics: ContactSyncAnalytics = ContactSyncAnalytics.None

    @Volatile
    @JvmStatic
    var contactsProvider: ContactsProvider = AndroidContactsProvider

    /** Reference: user experiment `2021-11_phone_verify_captcha`, bucket 1. */
    @Volatile
    @JvmStatic
    var isPhoneVerifyCaptchaEnabled: () -> Boolean = { false }
}

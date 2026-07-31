package com.discord.widgets.contact_sync

import com.discord.stores.StoreStream

/**
 * Replaceable process dependencies for device contact access and analytics capture.
 * Store dependencies use `StoreStream` directly, as in 126.21.
 */
object ContactSyncDependencies {

    @Volatile
    @JvmStatic
    var analytics: ContactSyncAnalytics = ContactSyncAnalytics.Production

    @Volatile
    @JvmStatic
    var contactsProvider: ContactsProvider = AndroidContactsProvider

    /** Shared with the phone-management flow, which applies the same experiment gate. */
    @Volatile
    @JvmStatic
    var isPhoneVerifyCaptchaEnabled: () -> Boolean = {
        StoreStream.getExperiments()
            .getUserExperiment("2021-11_phone_verify_captcha", trackExposure = true)
            ?.bucket == 1
    }
}

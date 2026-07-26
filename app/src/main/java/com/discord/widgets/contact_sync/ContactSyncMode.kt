package com.discord.widgets.contact_sync

/**
 * Which entry point opened the contact-sync flow.
 *
 * [ONBOARDING] is reached from the new-user flow: the toolbar shows "Skip" instead of a back arrow
 * and finishing the flow marks the onboarding step complete. [DEFAULT] is everything else — the
 * friends-list menu item and the upsell sheet — where the toolbar shows a back arrow instead.
 *
 * Ordinal order is load-bearing (`getLandingToolbarConfig` switches on it) and is
 * `ONBOARDING(0), DEFAULT(1)`, confirmed with `javap -c`: the reference's alphabetical *source*
 * order is misleading, exactly as it was for `CallStatus`.
 */
enum class ContactSyncMode {
    ONBOARDING,
    DEFAULT,
}

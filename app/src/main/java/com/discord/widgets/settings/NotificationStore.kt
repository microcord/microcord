package com.discord.widgets.settings

import com.discord.stores.StoreNotifications

/**
 * Resolves the process-wide [StoreNotifications].
 *
 * The reference reads `StoreStream.getNotifications()` directly from
 * `WidgetSettingsNotifications` and `WidgetSettingsNotificationsOs`. This project's
 * `StoreNotifications` is complete — it exposes `observeSettings()` and every `set…` used by those
 * two screens — but **`StoreStream` neither constructs nor exposes it**, and it is never reset on
 * logout (`docs/parity-audit/README.md`, "`StoreNotifications` is dead code"). `stores/` is owned by
 * another wave, so the notification screens resolve the store through this single seam instead of
 * carrying a nullable callback each.
 *
 * When `StoreStream.getNotifications()` lands: replace every `NotificationStore.get()` call site
 * with it and delete this file. Until then the notification screens render their toggles disabled
 * rather than pretending to persist anything.
 */
internal object NotificationStore {

    /** Assigned by the store graph once it constructs `StoreNotifications`. */
    @JvmStatic
    var resolver: (() -> StoreNotifications)? = null

    fun get(): StoreNotifications? = resolver?.invoke()
}

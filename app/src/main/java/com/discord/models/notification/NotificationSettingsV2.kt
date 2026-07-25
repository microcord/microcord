package com.discord.models.notification

/** User-controlled notification settings. Authentication data is deliberately not part of this model. */
data class NotificationSettingsV2(
    val isEnabled: Boolean = true,
    val isEnabledInApp: Boolean = true,
    val isWake: Boolean = false,
    val isDisableBlink: Boolean = false,
    val isDisableSound: Boolean = false,
    val isDisableVibrate: Boolean = false,
)

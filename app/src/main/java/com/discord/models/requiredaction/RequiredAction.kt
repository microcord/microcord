package com.discord.models.requiredaction

import java.util.Locale

/**
 * A gate the user must clear before the app becomes usable, as told by `READY.required_action`
 * and `USER_REQUIRED_ACTION_UPDATE`.
 *
 * Unknown or absent values resolve to [NONE] so a server-side action this build predates never
 * locks the client out.
 */
enum class RequiredAction {
    AGREEMENTS,
    TOS_UPDATE_ACKNOWLEDGMENT,
    REQUIRE_VERIFIED_EMAIL,
    REQUIRE_VERIFIED_PHONE,
    REQUIRE_CAPTCHA,
    NONE;

    companion object {
        fun fromApiString(value: String?): RequiredAction = try {
            value?.let { valueOf(it.uppercase(Locale.ROOT)) } ?: NONE
        } catch (e: IllegalArgumentException) {
            NONE
        }
    }
}

package com.discord.models.requiredaction

import java.util.Locale

/**
 * A gate the user must clear before the app becomes usable, as told by `READY.required_action`
 * and `USER_REQUIRED_ACTION_UPDATE`.
 *
 * Distinct from `com.discord.api.auth.RequiredAction`, which is the list a login response attaches
 * to a token; this is the single action gating the session.
 *
 * Unknown or absent values resolve to [NONE] so a server-side action this build predates never
 * locks the client out.
 */
enum class RequiredAction {
    AGREEMENTS,
    TOS_UPDATE_ACKNOWLEDGMENT,
    REQUIRE_VERIFIED_EMAIL,
    REQUIRE_REVERIFIED_EMAIL,
    REQUIRE_VERIFIED_PHONE,
    REQUIRE_REVERIFIED_PHONE,
    REQUIRE_VERIFIED_EMAIL_OR_VERIFIED_PHONE,
    REQUIRE_REVERIFIED_EMAIL_OR_VERIFIED_PHONE,
    REQUIRE_VERIFIED_EMAIL_OR_REVERIFIED_PHONE,
    REQUIRE_REVERIFIED_EMAIL_OR_REVERIFIED_PHONE,
    REQUIRE_CAPTCHA,
    NONE;

    /** Whether adding or reverifying a phone number clears this gate. */
    val acceptsPhone: Boolean
        get() = this in PHONE_ACTIONS

    /** Whether adding or reverifying an email address clears this gate. */
    val acceptsEmail: Boolean
        get() = this in EMAIL_ACTIONS

    val isVerificationGate: Boolean
        get() = acceptsPhone || acceptsEmail

    companion object {
        private val PHONE_ACTIONS = setOf(
            REQUIRE_VERIFIED_PHONE,
            REQUIRE_REVERIFIED_PHONE,
            REQUIRE_VERIFIED_EMAIL_OR_VERIFIED_PHONE,
            REQUIRE_REVERIFIED_EMAIL_OR_VERIFIED_PHONE,
            REQUIRE_VERIFIED_EMAIL_OR_REVERIFIED_PHONE,
            REQUIRE_REVERIFIED_EMAIL_OR_REVERIFIED_PHONE,
        )

        private val EMAIL_ACTIONS = setOf(
            REQUIRE_VERIFIED_EMAIL,
            REQUIRE_REVERIFIED_EMAIL,
            REQUIRE_VERIFIED_EMAIL_OR_VERIFIED_PHONE,
            REQUIRE_REVERIFIED_EMAIL_OR_VERIFIED_PHONE,
            REQUIRE_VERIFIED_EMAIL_OR_REVERIFIED_PHONE,
            REQUIRE_REVERIFIED_EMAIL_OR_REVERIFIED_PHONE,
        )

        fun fromApiString(value: String?): RequiredAction = try {
            value?.let { valueOf(it.uppercase(Locale.ROOT)) } ?: NONE
        } catch (e: IllegalArgumentException) {
            NONE
        }
    }
}

package com.discord.api.auth

/** Response to `POST auth/password/validate`. [passwordStrength] is 0-4. */
data class PasswordStrength(
    val valid: Boolean = false,
    val passwordStrength: Int = 0,
)

data class UsernameSuggestion(
    val username: String?,
)

/** `taken` is null when the server declined to answer rather than when the name is free. */
data class UsernameEligibility(
    val taken: Boolean?,
)

/**
 * Response to `POST auth/forgot`.
 *
 * `one_time_login` means the mail carries a ticket the app can redeem itself; `password_reset`
 * means it carries a `/reset#token=` link only a browser can complete.
 */
data class ForgotPasswordResponse(
    val method: String?,
) {
    val isOneTimeLogin: Boolean
        get() = method == METHOD_ONE_TIME_LOGIN

    companion object {
        const val METHOD_ONE_TIME_LOGIN = "one_time_login"
    }
}

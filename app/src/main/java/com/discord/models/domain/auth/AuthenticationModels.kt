package com.discord.models.domain.auth

import com.discord.api.auth.RequiredAction
import com.discord.api.auth.mfa.MfaChallenge
import com.discord.api.auth.mfa.MfaMethod
import com.discord.api.auth.mfa.MfaMethodType

data class ModelUserSettingsBootstrap(
    val theme: String?,
    val locale: String?,
)

/**
 * Response to `auth/login`, `auth/mfa/{type}` and `auth/reset`.
 *
 * Three outcomes share one shape: a completed login carries [token]; a challenged one carries
 * [ticket] plus the method flags; a suspended account carries only [suspendedUserToken].
 */
data class ModelLoginResult(
    val mfa: Boolean = false,
    val ticket: String?,
    val token: String?,
    val userSettings: ModelUserSettingsBootstrap?,
    val requiredActions: List<RequiredAction>?,
    val userId: Long? = null,
    val loginInstanceId: String? = null,
    val totp: Boolean = false,
    val sms: Boolean = false,
    val backup: Boolean = false,
    /** Stringified WebAuthn credential request options, present when a security key is enrolled. */
    val webauthn: String? = null,
    val suspendedUserToken: String? = null,
) {

    /**
     * The challenge this response represents, or null if it is not one.
     *
     * Method order matches the reference: WebAuthn, TOTP, backup, SMS. `backup` doubles as
     * `backup_codes_allowed` on the TOTP prompt, since that endpoint takes either code.
     */
    fun mfaChallenge(): MfaChallenge? {
        val ticket = ticket?.takeUnless(String::isBlank) ?: return null
        val methods = buildList {
            webauthn?.takeUnless(String::isBlank)?.let {
                add(MfaMethod(MfaMethodType.WEBAUTHN, challenge = it))
            }
            if (totp) add(MfaMethod(MfaMethodType.TOTP, backupCodesAllowed = backup))
            if (backup) add(MfaMethod(MfaMethodType.BACKUP))
            if (sms) add(MfaMethod(MfaMethodType.SMS))
        }
        return if (methods.isEmpty()) null else MfaChallenge(ticket, methods)
    }

    override fun toString(): String =
        "ModelLoginResult(mfa=$mfa, ticket=<redacted>, token=<redacted>, userSettings=$userSettings, " +
            "requiredActions=$requiredActions, userId=$userId, loginInstanceId=$loginInstanceId, " +
            "totp=$totp, sms=$sms, backup=$backup, webauthn=${webauthn != null}, " +
            "suspendedUserToken=<redacted>)"
}

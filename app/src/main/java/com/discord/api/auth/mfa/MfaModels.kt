package com.discord.api.auth.mfa

/** One two-factor backup code, and whether it has already been spent. */
data class BackupCode(
    val code: String,
    val consumed: Boolean,
)

/** `mfa_type` on `mfa/finish`, and the trailing path segment of `auth/mfa/{type}`. */
enum class MfaMethodType(val wireName: String) {
    WEBAUTHN("webauthn"),
    TOTP("totp"),
    BACKUP("backup"),
    SMS("sms"),
    PASSWORD("password"),
    ;

    companion object {
        fun from(wireName: String?): MfaMethodType? = values().firstOrNull { it.wireName == wireName }
    }
}

/**
 * [challenge] carries WebAuthn credential request options; [backupCodesAllowed] means the TOTP
 * prompt also accepts a backup code.
 */
data class MfaMethod(
    val type: MfaMethodType,
    val challenge: String? = null,
    val backupCodesAllowed: Boolean = false,
)

/** The `mfa` object of a 401 `MFA_REQUIRED`, and the normalised method flags of a login response. */
data class MfaChallenge(
    val ticket: String,
    val methods: List<MfaMethod>,
) {
    override fun toString(): String =
        "MfaChallenge(ticket=<redacted>, methods=${methods.map(MfaMethod::type)})"
}

/** Body of `POST mfa/finish`. */
class FinishMfaRequestBody(
    private val ticket: String,
    private val mfaType: String,
    private val data: String,
) {
    constructor(ticket: String, type: MfaMethodType, data: String) : this(ticket, type.wireName, data)

    override fun toString(): String = "FinishMfaRequestBody(ticket=<redacted>, mfaType=$mfaType, data=<redacted>)"
}

/** Response to `POST mfa/finish`: the elevation JWT, good for five minutes. */
data class FinishMfaResponse(
    val token: String,
) {
    override fun toString(): String = "FinishMfaResponse(token=<redacted>)"
}

/** Response to `POST auth/mfa/sms/send`: the redacted number the code went to. */
data class SendMfaSmsResponse(
    val phone: String?,
)

/** An enrolled security key or passkey. [type] is always 1 (`WEBAUTHN`). */
data class Authenticator(
    val id: String,
    val name: String?,
    val type: Int = WEBAUTHN_TYPE,
) {
    companion object {
        const val WEBAUTHN_TYPE = 1
    }
}

/** Response to `POST users/@me/mfa/totp/disable`: a freshly minted auth token. */
data class DisableMfaResponse(
    val token: String,
) {
    override fun toString(): String = "DisableMfaResponse(token=<redacted>)"
}

/** Response to `POST users/@me/mfa/totp/enable`: a new auth token plus the initial backup codes. */
data class EnableMfaResponse(
    val token: String,
    val backupCodes: List<BackupCode>,
) {
    override fun toString(): String = "EnableMfaResponse(token=<redacted>, backupCodes=<redacted>)"
}

/** Body of `POST users/@me/mfa/codes`: password-authenticated backup-code retrieval. */
class GetBackupCodesRequestBody(
    private val password: String,
    private val regenerate: Boolean,
) {
    override fun toString(): String =
        "GetBackupCodesRequestBody(password=<redacted>, regenerate=$regenerate)"
}

/** Response carrying the account's backup codes. */
data class GetBackupCodesResponse(
    val backupCodes: List<BackupCode>,
) {
    override fun toString(): String = "GetBackupCodesResponse(backupCodes=<redacted>)"
}

/** Body of the request that emails the backup-code verification key. */
class GetBackupCodesSendVerificationKeyRequestBody(
    private val password: String,
) {
    override fun toString(): String = "GetBackupCodesSendVerificationKeyRequestBody(password=<redacted>)"
}

/**
 * Response to the send-verification-key request.
 *
 * The two nonces scope the follow-up call: [nonce] only views the existing codes,
 * [regenerateNonce] replaces them.
 */
data class GetBackupCodesSendVerificationKeyResponse(
    val nonce: String,
    val regenerateNonce: String,
) {
    override fun toString(): String =
        "GetBackupCodesSendVerificationKeyResponse(nonce=<redacted>, regenerateNonce=<redacted>)"
}

/** Body of `POST users/@me/mfa/codes-verification`, redeeming an emailed verification key. */
class GetBackupCodesVerificationRequestBody(
    private val key: String,
    private val nonce: String,
    private val regenerate: Boolean,
) {
    override fun toString(): String =
        "GetBackupCodesVerificationRequestBody(key=<redacted>, nonce=<redacted>, regenerate=$regenerate)"
}

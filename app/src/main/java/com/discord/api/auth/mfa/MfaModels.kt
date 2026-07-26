package com.discord.api.auth.mfa

/** One two-factor backup code, and whether it has already been spent. */
data class BackupCode(
    val code: String,
    val consumed: Boolean,
)

/** Body of `POST users/@me/mfa/totp/disable`. */
data class DisableMfaRequestBody(
    private val code: String,
) {
    override fun toString(): String = "DisableMfaRequestBody(code=<redacted>)"
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

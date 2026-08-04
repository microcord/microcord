package com.discord.utilities.mfa

import com.discord.api.auth.mfa.MfaChallenge
import com.discord.api.auth.mfa.MfaMethod
import com.discord.api.auth.mfa.MfaMethodType
import com.discord.app.AppLog
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * The body of a 401 that demanded multi-factor verification.
 *
 * Nullable throughout: Gson assigns fields reflectively and runs no Kotlin default or initialiser,
 * so anything the server omits arrives null whatever the declared type says.
 */
internal data class MfaErrorBody(
    val code: Int? = null,
    val message: String? = null,
    val mfa: Challenge? = null,
) {

    internal data class Challenge(
        val ticket: String? = null,
        val methods: List<Method>? = null,
    )

    internal data class Method(
        val type: String? = null,
        val challenge: String? = null,
        val backupCodesAllowed: Boolean? = null,
    )

    /** Null unless this really is a challenge: the right code, a ticket, and a method to offer. */
    fun toChallenge(): MfaChallenge? {
        if (code != MFA_REQUIRED) return null
        val ticket = mfa?.ticket?.takeUnless(String::isBlank) ?: return null
        val methods = mfa.methods.orEmpty().mapNotNull { method ->
            MfaMethodType.from(method.type)?.let {
                MfaMethod(it, method.challenge, method.backupCodesAllowed == true)
            }
        }
        return if (methods.isEmpty()) null else MfaChallenge(ticket, methods)
    }

    override fun toString(): String = "MfaErrorBody(code=$code, mfa=${mfa?.methods?.map(Method::type)})"

    companion object {
        const val MFA_REQUIRED = 60003

        private val gson: Gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create()

        fun createFromString(errorString: String): MfaErrorBody? {
            if (!errorString.contains(MFA_REQUIRED.toString())) return null
            return runCatching { gson.fromJson(errorString, MfaErrorBody::class.java) }
                .onFailure { AppLog.e("failed to parse mfa error body", it) }
                .getOrNull()
        }
    }
}

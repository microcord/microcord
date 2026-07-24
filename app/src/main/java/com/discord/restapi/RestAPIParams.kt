package com.discord.restapi

import com.discord.models.domain.ModelGuildFolder
import com.google.gson.annotations.SerializedName

object RestAPIParams {
    data class GuildFoldersUpdate(@SerializedName("guild_folders") val guildFolders: List<ModelGuildFolder>)

    class EmptyBody {
        override fun toString(): String = "EmptyBody"
    }

    data class AuthLogin(
        val login: String,
        val password: String,
        val captchaKey: String?,
        val captchaRqtoken: String?,
        val undelete: Boolean?,
        val loginSource: String?,
    ) {
        override fun toString(): String = "AuthLogin(<redacted>)"

        object LoginSource {
            const val LOGIN_SOURCE_KEYCHAIN_AUTO = "keychain_auto"
        }
    }

    data class AuthRegister(
        val fingerprint: String,
        val username: String,
        val email: String?,
        val phoneToken: String?,
        val password: String,
        val captchaKey: String?,
        val captchaRqtoken: String?,
        val invite: String?,
        val guildTemplateCode: String?,
        val consent: Boolean,
        val dateOfBirth: String?,
    ) {
        override fun toString(): String = "AuthRegister(<redacted>)"
    }

    data class MFALogin(val ticket: String, val code: String) {
        override fun toString(): String = "MFALogin(<redacted>)"
    }

    data class AuthorizeIP(val token: String) {
        override fun toString(): String = "AuthorizeIP(token=<redacted>)"
    }

    data class ForgotPassword(
        val login: String,
        val captchaKey: String?,
        val captchaRqtoken: String?,
    ) {
        override fun toString(): String = "ForgotPassword(<redacted>)"
    }

    data class UserDevices(val token: String?) {
        val provider: String = "gcm"
        override fun toString(): String = "UserDevices(provider=$provider, token=<redacted>)"
    }

    data class VerificationCode(val phone: String, val code: String) {
        override fun toString(): String = "VerificationCode(<redacted>)"
    }

    data class VerificationCodeResend(val phone: String) {
        override fun toString(): String = "VerificationCodeResend(phone=<redacted>)"
    }

    data class VerificationPhoneCode(
        val phoneToken: String,
        val password: String,
        val source: String,
    ) {
        override fun toString(): String = "VerificationPhoneCode(source=$source, credentials=<redacted>)"
    }
}

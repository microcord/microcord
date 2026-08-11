package com.discord.models.domain

import com.discord.api.guild.Guild

/**
 * The membership-screening form a guild asks new members to complete.
 *
 * Every field carries a default so Gson runs the generated no-arg constructor: without one it
 * allocates the instance unsafely and a payload that omits `form_fields` leaves a non-null `List`
 * holding JVM null.
 *
 * [guild] is only sent when the request asked for it, which is the case exactly when the user is
 * not a member — for a manual-approval guild it is the client's *only* source of the name and icon
 * to render the screen with.
 */
data class ModelMemberVerificationForm(
    val version: String = "",
    val description: String? = null,
    val formFields: List<FormField> = emptyList(),
    val guild: Guild? = null,
) {
    data class FormField(
        val fieldType: String = "",
        val label: String = "",
        val description: String? = null,
        val placeholder: String? = null,
        val required: Boolean = false,
        val values: List<String> = emptyList(),
        val choices: List<String> = emptyList(),
        var response: Any = false,
    ) {
        val memberVerificationFieldType: MemberVerificationFieldType
            get() = MemberVerificationFieldType.entries.firstOrNull { it.name == fieldType }
                ?: MemberVerificationFieldType.UNKNOWN
    }

    /**
     * [VERIFICATION] is a field the reference *synthesises* from the guild's verification level
     * rather than one the server sends, but it is a member of the wire enum and is accepted back on
     * submit. Leaving it out would classify it as [UNKNOWN] and send the user to "update your app".
     */
    enum class MemberVerificationFieldType {
        UNKNOWN, TERMS, TEXT_INPUT, PARAGRAPH, MULTIPLE_CHOICE, VERIFICATION
    }

    /**
     * A field type this build has never heard of means the guild is asking something this client
     * cannot render, so the only honest outcome is to send the user to the store rather than submit
     * a form with a silently dropped question.
     */
    fun isFormOutdated() = formFields.any { it.memberVerificationFieldType == MemberVerificationFieldType.UNKNOWN }
}

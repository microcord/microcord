package com.discord.models.domain

data class ModelMemberVerificationForm(
    val version: String,
    val formFields: List<FormField>,
) {
    data class FormField(
        val fieldType: String,
        val label: String,
        val required: Boolean,
        val values: List<String>,
        val choices: List<String>,
        var response: Any,
    ) {
        val memberVerificationFieldType: MemberVerificationFieldType
            get() = MemberVerificationFieldType.entries.firstOrNull { it.name == fieldType }
                ?: MemberVerificationFieldType.UNKNOWN
    }

    enum class MemberVerificationFieldType { UNKNOWN, TERMS, TEXT_INPUT, PARAGRAPH, MULTIPLE_CHOICE }

    fun isFormOutdated() = formFields.any { it.memberVerificationFieldType == MemberVerificationFieldType.UNKNOWN }
}

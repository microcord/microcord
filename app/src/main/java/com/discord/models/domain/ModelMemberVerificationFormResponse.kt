package com.discord.models.domain

import com.discord.api.guildjoinrequest.ApplicationStatus

/** The current user's answers to a guild's membership screening form, plus its review state. */
data class ModelMemberVerificationFormResponse(
    val lastSeen: String?,
    val applicationStatus: ApplicationStatus,
    val formResponses: List<ModelMemberVerificationForm.FormField>?,
    val guildId: Long?,
)

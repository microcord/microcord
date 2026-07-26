package com.discord.api.requiredaction

/**
 * Payload of `USER_REQUIRED_ACTION_UPDATE`.
 *
 * The wire value stays a raw string here exactly as in the reference; consumers map it through
 * `com.discord.models.requiredaction.RequiredAction.fromApiString` so an unrecognised action
 * degrades to `NONE` instead of failing to parse.
 */
data class UserRequiredActionUpdate(
    val requiredAction: String?,
)

package com.discord.models.domain

import com.google.gson.annotations.SerializedName

/** @param type a `UserSettingsProtoType`; 1 is the preloaded settings, 2 the frecency ones. */
data class UserSettingsProtoEnvelope(val proto: String, val type: Int)

data class UserSettingsProtoUpdate(
    val settings: UserSettingsProtoEnvelope,
    val partial: Boolean,
)

data class UserSettingsProtoResponse(
    val settings: String,
    /** The write was composed against an older proto and was not applied; [settings] is the truth. */
    @SerializedName("out_of_date") val outOfDate: Boolean = false,
)

data class UserSettingsProtoPatch(
    val settings: String,
    @SerializedName("required_data_version") val requiredDataVersion: Int? = null,
)

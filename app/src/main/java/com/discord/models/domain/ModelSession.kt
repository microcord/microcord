package com.discord.models.domain

import com.discord.api.activity.Activity
import com.google.gson.annotations.SerializedName

data class ModelSession(
    @SerializedName("session_id") val sessionId: String? = null,
    @SerializedName("last_modified") val lastModified: Long = 0,
    val status: String? = null,
    val activities: List<Activity>? = null,
    @SerializedName("client_info") val clientInfo: ClientInfo? = null,
    val active: Boolean = false,
) {
    override fun toString() =
        "ModelSession(sessionId=<redacted>, lastModified=$lastModified, status=$status, activities=$activities, " +
            "clientInfo=$clientInfo, active=$active)"

    data class ClientInfo(
        val os: String? = null,
        val client: String? = null,
        val number: Int = 0,
    )
}

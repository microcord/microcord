package com.discord.api.user

import com.google.gson.annotations.SerializedName

/** One avatar retained by Discord's recent-avatar history. */
data class RecentAvatar(
    val id: String,
    @SerializedName("storage_hash") val storageHash: String,
    val description: String? = null,
)

/** Response from `GET users/@me/avatars`; Discord currently retains at most six entries. */
data class RecentAvatars(
    val avatars: List<RecentAvatar>,
)

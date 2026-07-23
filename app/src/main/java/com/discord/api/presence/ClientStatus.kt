package com.discord.api.presence

import com.google.gson.annotations.SerializedName

enum class ClientStatus {
    @SerializedName("online") ONLINE,
    @SerializedName("idle") IDLE,
    @SerializedName("dnd") DND,
    @SerializedName("invisible") INVISIBLE,
    @SerializedName("offline") OFFLINE,
}

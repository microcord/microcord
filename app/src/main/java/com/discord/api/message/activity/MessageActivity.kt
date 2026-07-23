package com.discord.api.message.activity

data class MessageActivity(val type: MessageActivityType, val partyId: String)

enum class MessageActivityType(val apiInt: Int) {
    JOIN(1),
    SPECTATE(2),
    LISTEN(3),
    WATCH(4),
    REQUEST(5),
    ;

    companion object {
        fun fromApiInt(value: Int?) = entries.firstOrNull { it.apiInt == value } ?: JOIN
    }
}

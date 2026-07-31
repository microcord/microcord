package com.discord.utilities.textprocessing

data class Tags(
    val users: Set<Long>,
    val channels: Set<Long>,
    val roles: Set<Long>,
) {
    val isEmpty: Boolean = users.isEmpty() && channels.isEmpty() && roles.isEmpty()
}

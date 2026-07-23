package com.discord.api.presence

data class ClientStatuses(
    private val desktop: ClientStatus?,
    private val web: ClientStatus?,
    private val mobile: ClientStatus?,
) {
    fun desktopStatus() = desktop ?: ClientStatus.OFFLINE
    fun webStatus() = web ?: ClientStatus.OFFLINE
    fun mobileStatus() = mobile ?: ClientStatus.OFFLINE
}

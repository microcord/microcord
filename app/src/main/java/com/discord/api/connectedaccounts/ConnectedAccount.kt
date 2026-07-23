package com.discord.api.connectedaccounts

data class ConnectedIntegrationAccount(val id: String, val name: String)

data class ConnectedIntegrationGuild(val id: Long, val icon: String?, val name: String?)

data class ConnectedAccountIntegration(
    val id: String?,
    val type: String?,
    val account: ConnectedIntegrationAccount?,
    val guild: ConnectedIntegrationGuild?,
)

data class ConnectedAccount(
    val id: String = "",
    val name: String = "",
    val visibility: Int = HIDDEN,
    val friendSync: Boolean = false,
    val showActivity: Boolean = false,
    val revoked: Boolean = false,
    val verified: Boolean = false,
    val integrations: List<ConnectedAccountIntegration>? = null,
    val type: String = "",
) {
    companion object {
        const val HIDDEN = 0
        const val VISIBLE = 1
    }
}

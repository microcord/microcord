package com.discord.rpc

/**
 * Pseudo-scopes the server grants itself alongside the real OAuth2 scopes a token carries.
 * [PRIVATE_LIMITED] covers commands reachable over an HTTP redirect and is not a security boundary.
 */
object RpcScopes {
    const val PRIVATE = "RPC_PRIVATE_SCOPE"
    const val PRIVATE_LIMITED = "RPC_PRIVATE_LIMITED_SCOPE"
    const val LOCAL = "RPC_LOCAL_SCOPE"
    const val AUTHENTICATED = "RPC_AUTHENTICATED_SCOPE"
    const val EMBEDDED_APP = "RPC_EMBEDDED_APP_SCOPE"
}

sealed interface RpcScopeConfig {
    data class One(val scope: String) : RpcScopeConfig

    data class Any(val scopes: List<String>) : RpcScopeConfig

    data class All(val scopes: List<String>) : RpcScopeConfig
}

fun validateScope(userScopes: List<String>, scopeConfig: RpcScopeConfig?): Boolean = when (scopeConfig) {
    null -> true
    is RpcScopeConfig.One -> scopeConfig.scope in userScopes
    is RpcScopeConfig.Any -> scopeConfig.scopes.any { it in userScopes }
    is RpcScopeConfig.All -> scopeConfig.scopes.all { it in userScopes }
}

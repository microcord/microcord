package com.discord.api.application

class ApplicationInstallParams(
    val permissions: String?,
    val scopes: Array<String>,
) {
    override fun equals(other: Any?) = other is ApplicationInstallParams &&
        permissions == other.permissions && scopes.contentEquals(other.scopes)

    override fun hashCode() = 31 * (permissions?.hashCode() ?: 0) + scopes.contentHashCode()
    override fun toString() = "ApplicationInstallParams(permissions=$permissions, scopes=${scopes.contentToString()})"
}

data class ProfileApplication(
    val id: String,
    val customInstallUrl: String?,
    val installParams: ApplicationInstallParams?,
)

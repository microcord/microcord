package com.discord.api.applicationdirectory

import com.discord.api.user.User

data class ApplicationDirectoryCategory(
    val id: Int,
    val name: String,
)

data class ApplicationInstallParams(
    val scopes: List<String>,
    val permissions: String? = null,
)

data class ApplicationIntegrationTypeConfig(
    val oauth2InstallParams: ApplicationInstallParams? = null,
)

data class ApplicationDirectoryEntry(
    val guildCount: Int = 0,
    val carouselItems: List<ApplicationDirectoryCarouselItem>? = null,
    val supportedLocales: List<String> = emptyList(),
    val externalUrls: List<ApplicationDirectoryExternalUrl>? = null,
    val popularApplicationCommandIds: List<Long>? = null,
    val detailedDescription: String? = null,
    val shortDescription: String = "",
    val shortDescriptionLocalizations: Map<String, String>? = null,
    val detailedDescriptionLocalizations: Map<String, String>? = null,
)

data class ApplicationDirectoryCarouselItem(
    val type: Int,
    val url: String,
    val proxyUrl: String,
)

data class ApplicationDirectoryExternalUrl(
    val name: String,
    val url: String,
)

data class ApplicationDirectoryApplication(
    val id: Long,
    val name: String,
    val description: String = "",
    val icon: String? = null,
    val coverImage: String? = null,
    val splash: String? = null,
    val type: Int? = null,
    val flags: Long? = null,
    val flagsNew: String? = null,
    val bot: User? = null,
    val guildId: Long? = null,
    val tags: List<String>? = null,
    val installParams: ApplicationInstallParams? = null,
    val customInstallUrl: String? = null,
    val integrationTypesConfig: Map<Int, ApplicationIntegrationTypeConfig>? = null,
    val integrationPublic: Boolean? = null,
    val integrationRequireCodeGrant: Boolean? = null,
    val botPublic: Boolean? = null,
    val botRequireCodeGrant: Boolean? = null,
    val botDisabled: Boolean? = null,
    val botQuarantined: Boolean? = null,
    val botApproximateGuildCount: Int? = null,
    val approximateGuildCount: Int? = null,
    val approximateUserInstallCount: Int? = null,
    val approximateUserAuthorizationCount: Int? = null,
    val termsOfServiceUrl: String? = null,
    val privacyPolicyUrl: String? = null,
    val isVerified: Boolean = false,
    val isDiscoverable: Boolean = false,
    val discoverabilityState: Int? = null,
    val discoveryEligibilityFlags: Int? = null,
    val isMonetized: Boolean = false,
    val storefrontAvailable: Boolean = false,
    val categories: List<ApplicationDirectoryCategory>? = null,
    val directoryEntry: ApplicationDirectoryEntry? = null,
    val position: Int? = null,
) {
    fun installParams(integrationType: Int): ApplicationInstallParams? =
        integrationTypesConfig?.get(integrationType)?.oauth2InstallParams
            ?: installParams.takeIf { integrationTypesConfig == null && integrationType == GUILD_INSTALL }

    val supportedIntegrationTypes: Set<Int>
        get() = integrationTypesConfig?.filter { it.value.oauth2InstallParams != null }?.keys
            ?.filterTo(linkedSetOf()) { it == GUILD_INSTALL || it == USER_INSTALL }
            ?: if (installParams != null) setOf(GUILD_INSTALL) else emptySet()

    companion object {
        const val GUILD_INSTALL = 0
        const val USER_INSTALL = 1
    }
}

data class ApplicationDirectorySearchResult(
    val type: Int,
    val data: ApplicationDirectoryApplication,
)

data class ApplicationDirectorySearchResponse(
    val results: List<ApplicationDirectorySearchResult>,
    val numPages: Int,
    val countsByCategory: Map<Int, Int> = emptyMap(),
    val type: Int,
    val loadId: String,
    val resultCount: Int = results.size,
)

package com.discord.api.guild

data class GuildOnboarding(
    val guildId: Long = 0,
    val prompts: List<GuildOnboardingPrompt> = emptyList(),
    val defaultChannelIds: List<Long> = emptyList(),
    val responses: List<Long>? = null,
    val mode: Int = MODE_DEFAULT,
    val enabled: Boolean = false,
    val onboardingPromptsSeen: Map<String, Long>? = null,
    val onboardingResponsesSeen: Map<String, Long>? = null,
    val belowRequirements: Boolean? = null,
    val connections: List<GuildOnboardingConnection>? = null,
) {
    companion object {
        const val MODE_DEFAULT = 0
        const val MODE_ADVANCED = 1
    }
}

data class GuildOnboardingPrompt(
    val id: Long,
    val options: List<GuildOnboardingPromptOption> = emptyList(),
    val title: String,
    val singleSelect: Boolean = false,
    val disabled: Boolean = false,
    val required: Boolean = false,
    val inOnboarding: Boolean = true,
    val type: Int = TYPE_MULTIPLE_CHOICE,
) {
    companion object {
        const val TYPE_MULTIPLE_CHOICE = 0
        const val TYPE_DROPDOWN = 1
    }
}

data class GuildOnboardingPromptOption(
    val id: Long,
    val channelIds: List<Long> = emptyList(),
    val roleIds: List<Long> = emptyList(),
    val emoji: GuildOnboardingEmoji? = null,
    val title: String,
    val description: String? = null,
)

data class GuildOnboardingEmoji(
    val id: Long? = null,
    val name: String? = null,
    val animated: Boolean = false,
)

data class GuildOnboardingConnection(
    val connectionType: Int,
    val applicationId: Long? = null,
    val providerId: String? = null,
    val description: String? = null,
) {
    companion object {
        const val APPLICATION = 0
        const val PROVIDER_CONNECTED_ACCOUNT = 1
    }
}

data class GuildOnboardingResponses(
    val onboardingResponses: List<Long>,
    val onboardingPromptsSeen: Map<String, Long>,
    val onboardingResponsesSeen: Map<String, Long>,
)

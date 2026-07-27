package com.discord.utilities.channel

/**
 * Per-screen wording overrides for the channel-permission editor.
 *
 * The same permission list is shown for a category, a text channel, a voice channel and a stage
 * channel, but the explanatory subtext differs — and differs again for the `@everyone` row, which
 * has to say "everyone in the server" rather than "this role". Rather than fork the adapter, the
 * hosting screen supplies the strings it wants overridden and leaves the rest null.
 *
 * `CharSequence` where the reference used it: those three carry spans (bold role names and
 * clickable learn-more links) and must not be flattened to `String`.
 *
 * Reference: `com.discord.utilities.channel.PermissionLabelOverrides`.
 */
data class PermissionLabelOverrides(
    val categorySubtext: CharSequence,
    val categoryLabel: String? = null,
    val voiceChannelSubtext: String? = null,
    val stageChannelSubtext: CharSequence? = null,
    val textChannelEveryoneSubtext: String? = null,
    val voiceChannelEveryoneSubtext: String? = null,
    val stageChannelEveryoneSubtext: String? = null,
    val categoryEveryoneSubtext: CharSequence? = null,
    val announcementChannelSubtext: CharSequence? = null,
)

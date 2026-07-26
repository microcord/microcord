package com.discord.widgets.chat.list

/**
 * The experiment gates the chat list consults.
 *
 * The reference resolves each of these against `StoreExperiments`, which this project does not have
 * yet. Rather than hard-code the gates away, every flag exposes the reference experiment key it is
 * supposed to read plus a settable resolver, so wiring them up later is a single assignment per
 * flag in `StoreStream`.
 *
 * The defaults answer "on". In 126.21 all four experiments were fully rolled out on the release
 * channel, and defaulting to off would silently hide thread embeds, thread creation and the
 * text-in-voice surface — exactly the rows this reconstruction exists to restore.
 */

/** `2021-02_view_threads` (user experiment, bucket 1). Gates thread embeds and the thread browser. */
object ViewThreadsFeatureFlag {
    var resolver: () -> Boolean = { true }
    fun isEnabled(): Boolean = resolver()
}

/**
 * `2020-09_threads` / `2021-06_threads_rollout` (guild experiments). Gates the create-thread
 * affordances in the message action sheet and the chat input.
 */
object CreateThreadsFeatureFlag {
    var resolver: (guildId: Long) -> Boolean = { true }
    fun isEnabled(guildId: Long): Boolean = resolver(guildId)
}

/**
 * `2021-07_new_thread_permissions` (guild experiment). Selects which permission bit governs
 * creating a public thread; see `PermissionUtils.newThreadsPermissionsEnabled`, which holds the
 * matching stand-in on the permissions side.
 */
object NewThreadsPermissionsFeatureFlag {
    var resolver: (guildId: Long) -> Boolean = { true }
    fun isEnabled(guildId: Long): Boolean = resolver(guildId)
}

/** `2021-11_text_in_voice` (guild experiment). Gates the chat surface inside a voice channel. */
object TextInVoiceFeatureFlag {
    var resolver: (guildId: Long) -> Boolean = { true }
    fun isEnabled(guildId: Long): Boolean = resolver(guildId)
}

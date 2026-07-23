package com.discord.api.thread

import com.discord.api.utcdatetime.UtcDateTime

data class ThreadMetadata(
    val archived: Boolean,
    val archiveTimestamp: String?,
    val autoArchiveDuration: Int,
    val locked: Boolean,
)

data class MuteConfig(val endTime: String?)

data class ThreadMember(
    val id: Long,
    val userId: Long,
    val flags: Int,
    val joinTimestamp: UtcDateTime,
    val muted: Boolean,
    val muteConfig: MuteConfig?,
)

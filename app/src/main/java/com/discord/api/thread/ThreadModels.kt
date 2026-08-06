package com.discord.api.thread

import com.discord.api.utcdatetime.UtcDateTime

data class ThreadMetadata(
    val archived: Boolean,
    val archiveTimestamp: String?,
    val autoArchiveDuration: Int,
    val locked: Boolean,
    /** Private threads only; absent means the default, which is that anyone may invite. */
    val invitable: Boolean? = null,
)

data class MuteConfig(val endTime: String?)

open class ThreadMember(
    open val id: Long,
    open val userId: Long,
    open val flags: Int,
    open val joinTimestamp: UtcDateTime,
    val muted: Boolean,
    val muteConfig: MuteConfig?,
)

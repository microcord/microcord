package com.discord.workers

object MessageWorkerActions {
    @Volatile var sendPendingMessages: (() -> Boolean)? = null
    @Volatile var acknowledgeMessage: ((channelId: Long, messageId: Long) -> Boolean)? = null
    @Volatile var isCurrentAuthGeneration: ((generation: Int) -> Boolean)? = null
}

package com.discord.widgets.stage

@JvmInline
value class StageRoles(val rolesFlags: Int) {
    val isSpeaker: Boolean get() = rolesFlags and IS_SPEAKER != 0
    val isModerator: Boolean get() = rolesFlags and IS_MODERATOR != 0
    val isInvitedToSpeak: Boolean get() = rolesFlags and IS_INVITED_TO_SPEAK != 0
    val isAudience: Boolean get() = !isSpeaker && !isModerator

    companion object {
        val AUDIENCE = StageRoles(0)

        operator fun invoke(
            isSpeaker: Boolean = false,
            isModerator: Boolean = false,
            isInvitedToSpeak: Boolean = false,
        ) = StageRoles(
            (if (isSpeaker) IS_SPEAKER else 0) or
                (if (isModerator) IS_MODERATOR else 0) or
                (if (isInvitedToSpeak) IS_INVITED_TO_SPEAK else 0),
        )

        private const val IS_SPEAKER = 1
        private const val IS_MODERATOR = 2
        private const val IS_INVITED_TO_SPEAK = 4
    }
}

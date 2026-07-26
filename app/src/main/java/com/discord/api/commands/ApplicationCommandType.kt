package com.discord.api.commands

/** The `type` of an [ApplicationCommandOption]. */
enum class ApplicationCommandType(val type: Int) {
    SUBCOMMAND(1),
    SUBCOMMAND_GROUP(2),
    STRING(3),
    INTEGER(4),
    BOOLEAN(5),
    USER(6),
    CHANNEL(7),
    ROLE(8),
    MENTIONABLE(9),
    NUMBER(10),
    ATTACHMENT(11),
    UNKNOWN(0),
    ;

    companion object {
        /** Resolves a wire value, falling back to [UNKNOWN] for anything this build predates. */
        fun from(type: Int): ApplicationCommandType = when (type) {
            SUBCOMMAND.type -> SUBCOMMAND
            SUBCOMMAND_GROUP.type -> SUBCOMMAND_GROUP
            STRING.type -> STRING
            INTEGER.type -> INTEGER
            BOOLEAN.type -> BOOLEAN
            USER.type -> USER
            CHANNEL.type -> CHANNEL
            ROLE.type -> ROLE
            MENTIONABLE.type -> MENTIONABLE
            NUMBER.type -> NUMBER
            ATTACHMENT.type -> ATTACHMENT
            else -> UNKNOWN
        }
    }
}

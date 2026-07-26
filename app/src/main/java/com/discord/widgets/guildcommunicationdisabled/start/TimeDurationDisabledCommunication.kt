package com.discord.widgets.guildcommunicationdisabled.start

/**
 * The six timeout lengths the moderator may pick from in [WidgetDisableGuildCommunication].
 *
 * Declaration order is load-bearing: it is the order the radio group renders in, and the reference
 * `getFriendlyDurationString` switches on the ordinal. The Vineflower decompile lists the constants
 * alphabetically, which is misleading — the real ordinals come from the synthetic `$VALUES` array
 * (`SECONDS_60, MINUTES_5, MINUTES_10, HOUR_1, DAY_1, WEEK_1`) and are reproduced here.
 */
enum class TimeDurationDisabledCommunication(val durationMs: Int) {
    SECONDS_60(60_000),
    MINUTES_5(300_000),
    MINUTES_10(600_000),
    HOUR_1(3_600_000),
    DAY_1(86_400_000),
    WEEK_1(604_800_000),
}

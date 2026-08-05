package com.discord.widgets.chat.polls

import com.discord.R

enum class PollDuration(val hours: Int, val labelRes: Int) {
    ONE_HOUR(1, R.string.poll_duration_one_hour),
    FOUR_HOURS(4, R.string.poll_duration_four_hours),
    EIGHT_HOURS(8, R.string.poll_duration_eight_hours),
    ONE_DAY(24, R.string.poll_duration_one_day),
    THREE_DAYS(72, R.string.poll_duration_three_days),
    ONE_WEEK(168, R.string.poll_duration_one_week),
    TWO_WEEKS(336, R.string.poll_duration_two_weeks),
}

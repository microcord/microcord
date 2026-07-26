package com.discord.widgets.stage.model

/**
 * How a stage's "request to speak" setting is reported to analytics.
 *
 * [integerValue] is the wire value and is deliberately 1-based; it is not the ordinal. Confirmed
 * against `javap -c`: `REQUEST_TO_SPEAK_EVERYONE` is ordinal 0 with value 1,
 * `REQUEST_TO_SPEAK_NO_ONE` is ordinal 1 with value 2.
 */
enum class StageAnalyticsRequestToSpeakState(val integerValue: Int) {
    REQUEST_TO_SPEAK_EVERYONE(1),
    REQUEST_TO_SPEAK_NO_ONE(2),
}

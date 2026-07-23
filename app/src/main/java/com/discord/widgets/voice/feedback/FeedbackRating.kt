package com.discord.widgets.voice.feedback

enum class FeedbackRating(val analyticsValue: String) {
    GOOD("good"),
    NEUTRAL("neutral"),
    BAD("bad"),
    NO_RESPONSE("no response"),
}

package com.discord.rtcconnection.mediaengine

interface MediaEngineConnection {
    enum class InputMode(val numeral: Int) {
        VOICE_ACTIVITY(1),
        PUSH_TO_TALK(2),
    }
}

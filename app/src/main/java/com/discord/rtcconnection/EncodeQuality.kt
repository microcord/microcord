package com.discord.rtcconnection

/**
 * Discrete encoder quality steps, expressed as a percentage of the sender's configured maximum.
 *
 * The voice server (and, for a stream, the watching clients) express what they want as a plain
 * integer percentage over opcode 15. The sender does not honour arbitrary percentages: it snaps the
 * request down to one of these eleven rungs so that a room full of viewers with slightly different
 * wants does not make the encoder reconfigure on every stats tick.
 *
 * Reference: `com.discord.rtcconnection.EncodeQuality`, whose declaration order is recovered from
 * the `$VALUES` array rather than the decompiler's alphabetised field listing.
 */
enum class EncodeQuality(val value: Int) {
    Zero(0),
    Ten(10),
    Twenty(20),
    Thirty(30),
    Forty(40),
    Fifty(50),
    Sixty(60),
    Seventy(70),
    Eighty(80),
    Ninety(90),
    Hundred(100),
    ;

    companion object {
        /**
         * The highest rung that does not exceed [want].
         *
         * Snapping *down* is deliberate: overshooting a receiver's want wastes uplink on frames the
         * receiver has already said it will not render.
         */
        fun forWant(want: Int): EncodeQuality = values().last { it.value <= want }
    }
}

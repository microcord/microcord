package com.discord.api.premium

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

class PremiumTierTypeAdapter : TypeAdapter<PremiumTier>() {
    override fun read(reader: JsonReader): PremiumTier {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return PremiumTier.NONE
        }
        return when (reader.nextInt()) {
            0 -> PremiumTier.PREMIUM_GUILD_SUBSCRIPTION_ONLY
            1 -> PremiumTier.TIER_1
            2 -> PremiumTier.TIER_2
            3 -> PremiumTier.TIER_0
            else -> PremiumTier.NONE
        }
    }

    override fun write(writer: JsonWriter, value: PremiumTier?) {
        if (value == null) writer.nullValue() else writer.value(
            when (value) {
                PremiumTier.PREMIUM_GUILD_SUBSCRIPTION_ONLY -> 0
                PremiumTier.TIER_1 -> 1
                PremiumTier.TIER_2 -> 2
                PremiumTier.TIER_0 -> 3
                PremiumTier.NONE -> -1
            },
        )
    }
}

package com.discord.api.guildrolesubscription

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

/** Reads [GuildRoleSubscriptionBenefitType] from its integer wire value. */
class GuildRoleSubscriptionBenefitTypeAdapter : TypeAdapter<GuildRoleSubscriptionBenefitType>() {
    override fun read(reader: JsonReader): GuildRoleSubscriptionBenefitType =
        GuildRoleSubscriptionBenefitType.from(reader.nextInt())

    override fun write(writer: JsonWriter, value: GuildRoleSubscriptionBenefitType?) {
        if (value == null) writer.nullValue() else writer.value(value.apiValue)
    }
}

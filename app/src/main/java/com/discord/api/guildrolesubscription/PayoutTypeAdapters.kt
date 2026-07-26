package com.discord.api.guildrolesubscription

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

/** Reads [PayoutStatus] from its integer wire value. */
class PayoutStatusAdapter : TypeAdapter<PayoutStatus>() {
    override fun read(reader: JsonReader): PayoutStatus = PayoutStatus.from(reader.nextInt())

    override fun write(writer: JsonWriter, value: PayoutStatus?) {
        if (value == null) writer.nullValue() else writer.value(value.apiValue)
    }
}

/** Reads [PayoutGroupStatus] from its integer wire value. */
class PayoutGroupStatusTypeAdapter : TypeAdapter<PayoutGroupStatus>() {
    override fun read(reader: JsonReader): PayoutGroupStatus = PayoutGroupStatus.from(reader.nextInt())

    override fun write(writer: JsonWriter, value: PayoutGroupStatus?) {
        if (value == null) writer.nullValue() else writer.value(value.apiValue)
    }
}

/** Reads [PayoutGroupType] from its string wire value. */
class PayoutGroupTypeAdapter : TypeAdapter<PayoutGroupType>() {
    override fun read(reader: JsonReader): PayoutGroupType = PayoutGroupType.from(reader.nextString())

    override fun write(writer: JsonWriter, value: PayoutGroupType?) {
        if (value == null) writer.nullValue() else writer.value(value.apiValue)
    }
}

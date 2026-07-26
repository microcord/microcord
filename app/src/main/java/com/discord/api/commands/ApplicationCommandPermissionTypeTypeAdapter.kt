package com.discord.api.commands

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

/**
 * Reads [ApplicationCommandPermissionType] from its integer wire value.
 *
 * The wire values line up with the ordinals (`1` role, `2` user); anything else — including a
 * missing value — resolves to [ApplicationCommandPermissionType.UNKNOWN], which is written back
 * out as `0`.
 */
class ApplicationCommandPermissionTypeTypeAdapter : TypeAdapter<ApplicationCommandPermissionType>() {
    override fun read(reader: JsonReader): ApplicationCommandPermissionType =
        when (reader.nextInt()) {
            1 -> ApplicationCommandPermissionType.ROLE
            2 -> ApplicationCommandPermissionType.USER
            else -> ApplicationCommandPermissionType.UNKNOWN
        }

    override fun write(writer: JsonWriter, value: ApplicationCommandPermissionType?) {
        writer.value(
            when (value) {
                ApplicationCommandPermissionType.ROLE -> 1
                ApplicationCommandPermissionType.USER -> 2
                else -> 0
            },
        )
    }
}

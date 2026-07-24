package com.discord.api.auth

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.util.Locale

enum class RequiredAction {
    UPDATE_PASSWORD,
    UNKNOWN,
    ;

    companion object {
        fun from(value: String?): RequiredAction =
            if (value == "update_password") UPDATE_PASSWORD else UNKNOWN
    }
}

class RequiredActionTypeAdapter : TypeAdapter<RequiredAction>() {
    override fun read(input: JsonReader): RequiredAction = RequiredAction.from(input.nextString())
    override fun write(output: JsonWriter, value: RequiredAction?) {
        output.value(value?.name?.lowercase(Locale.ROOT))
    }
}

data class RegisterResponse(val token: String) {
    override fun toString(): String = "RegisterResponse(token=<redacted>)"
}

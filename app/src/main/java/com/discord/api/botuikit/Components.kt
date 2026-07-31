package com.discord.api.botuikit

import com.discord.api.botuikit.gson.ComponentTypeTypeAdapter
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.Serializable

interface Component {
    val type: ComponentType
}

abstract class ActionComponent : Component, Serializable

abstract class LayoutComponent : Component, Serializable {
    abstract val components: List<Component>
}

data class ActionRowComponent(
    override val type: ComponentType,
    override val components: List<Component>,
) : LayoutComponent()

data class ButtonComponent(
    override val type: ComponentType,
    val customId: String?,
    val label: String?,
    val style: ButtonStyle,
    val disabled: Boolean,
    val emoji: ComponentEmoji?,
    val url: String?,
) : ActionComponent()

data class SelectComponent(
    override val type: ComponentType,
    val customId: String,
    val disabled: Boolean,
    val placeholder: String?,
    val minValues: Int,
    val maxValues: Int,
    val options: List<SelectItem>,
) : ActionComponent()

data class TextComponent(
    override val type: ComponentType,
    val style: TextStyle,
    val customId: String,
    val label: String,
    val value: String?,
    val placeholder: String?,
    val minLength: Int,
    val maxLength: Int,
    val required: Boolean?,
) : ActionComponent()

class UnknownComponent : Component, Serializable {
    override val type = ComponentType.UNKNOWN
}

data class ComponentEmoji(
    val id: String?,
    val name: String,
    val animated: Boolean?,
) : Serializable

data class SelectItem(
    val label: String,
    val value: String,
    val emoji: ComponentEmoji?,
    val description: String?,
    @SerializedName("default") val `default`: Boolean,
) : Serializable

@JsonAdapter(ComponentTypeTypeAdapter::class)
enum class ComponentType(val type: Int, val clazz: Class<out Component>) {
    UNKNOWN(0, UnknownComponent::class.java),
    ACTION_ROW(1, ActionRowComponent::class.java),
    BUTTON(2, ButtonComponent::class.java),
    SELECT(3, SelectComponent::class.java),
    TEXT(4, TextComponent::class.java),
}

@JsonAdapter(ButtonStyleTypeAdapter::class)
enum class ButtonStyle {
    UNKNOWN, PRIMARY, SECONDARY, SUCCESS, DANGER, LINK,
}

@JsonAdapter(TextStyleTypeAdapter::class)
enum class TextStyle {
    SMALL, PARAGRAPH, UNKNOWN,
}

class ButtonStyleTypeAdapter : IntEnumTypeAdapter<ButtonStyle>(ButtonStyle.UNKNOWN, ButtonStyle.entries)
class TextStyleTypeAdapter : IntEnumTypeAdapter<TextStyle>(TextStyle.UNKNOWN, TextStyle.entries)

abstract class IntEnumTypeAdapter<T : Enum<T>>(
    private val unknown: T,
    values: List<T>,
) : TypeAdapter<T>() {
    private val byValue = values.associateBy {
        when (it) {
            is ComponentType -> it.type
            is ButtonStyle -> it.ordinal
            is TextStyle -> if (it == TextStyle.UNKNOWN) 0 else it.ordinal + 1
            else -> error("Unsupported enum")
        }
    }

    override fun read(reader: JsonReader): T {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return unknown
        }
        return byValue[reader.nextInt()] ?: unknown
    }

    override fun write(writer: JsonWriter, value: T?) {
        if (value == null || value == unknown) {
            writer.nullValue()
            return
        }
        writer.value(
            when (value) {
                is ComponentType -> value.type
                is ButtonStyle -> value.ordinal
                is TextStyle -> value.ordinal + 1
                else -> error("Unsupported enum")
            },
        )
    }
}

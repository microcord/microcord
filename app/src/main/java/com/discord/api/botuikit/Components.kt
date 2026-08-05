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
    val id: Int? get() = null
}

abstract class ActionComponent : Component, Serializable

abstract class ContentComponent : LayoutComponent() {
    final override val components: List<Component> get() = emptyList()
}

abstract class LayoutComponent : Component, Serializable {
    abstract val components: List<Component>
}

data class ActionRowComponent(
    override val type: ComponentType,
    override val components: List<Component>,
    override val id: Int? = null,
) : LayoutComponent()

data class ButtonComponent(
    override val type: ComponentType,
    val customId: String?,
    val label: String?,
    val style: ButtonStyle,
    val disabled: Boolean,
    val emoji: ComponentEmoji?,
    val url: String?,
    val skuId: Long? = null,
    override val id: Int? = null,
) : ActionComponent()

/**
 * A select of any kind: the string select plus the four snowflake selects.
 *
 * [minValues] and [maxValues] are nullable because absent and `0` mean different things — absent
 * defaults to one, and only [minValues] may legitimately be zero.
 */
data class SelectComponent(
    override val type: ComponentType,
    val customId: String,
    val disabled: Boolean,
    val placeholder: String?,
    val minValues: Int?,
    val maxValues: Int?,
    val options: List<SelectItem>?,
    val required: Boolean? = null,
    val defaultValues: List<SelectDefaultValue>? = null,
    val channelTypes: List<Int>? = null,
    override val id: Int? = null,
) : ActionComponent()

data class SelectDefaultValue(val id: Long, val type: String) : Serializable

data class TextComponent(
    override val type: ComponentType,
    val style: TextStyle,
    val customId: String,
    val label: String,
    val value: String?,
    val placeholder: String?,
    val minLength: Int?,
    val maxLength: Int?,
    val required: Boolean?,
    override val id: Int? = null,
) : ActionComponent()

data class SectionComponent(
    override val type: ComponentType,
    override val id: Int?,
    override val components: List<Component>,
    val accessory: Component,
) : LayoutComponent()

data class TextDisplayComponent(
    override val type: ComponentType,
    override val id: Int?,
    val content: String,
) : ContentComponent()

data class ThumbnailComponent(
    override val type: ComponentType,
    override val id: Int?,
    val media: UnfurledMediaItem,
    val description: String?,
    val spoiler: Boolean?,
) : ContentComponent()

data class MediaGalleryComponent(
    override val type: ComponentType,
    override val id: Int?,
    val items: List<MediaGalleryItem>,
) : ContentComponent()

data class FileComponent(
    override val type: ComponentType,
    override val id: Int?,
    val file: UnfurledMediaItem,
    val spoiler: Boolean?,
    val name: String,
    val size: Long,
) : ContentComponent()

data class SeparatorComponent(
    override val type: ComponentType,
    override val id: Int?,
    val divider: Boolean?,
    val spacing: Int?,
) : ContentComponent()

data class ContainerComponent(
    override val type: ComponentType,
    override val id: Int?,
    override val components: List<Component>,
    val accentColor: Int?,
    val spoiler: Boolean?,
) : LayoutComponent()

data class LabelComponent(
    override val type: ComponentType,
    override val id: Int?,
    val label: String,
    val description: String?,
    val component: Component,
) : LayoutComponent() {
    override val components: List<Component> get() = listOf(component)
}

data class FileUploadComponent(
    override val type: ComponentType,
    override val id: Int?,
    val customId: String,
    val minValues: Int?,
    val maxValues: Int?,
    val required: Boolean?,
    val fileTypes: List<String>? = null,
) : ActionComponent()

data class RadioGroupComponent(
    override val type: ComponentType,
    override val id: Int?,
    val customId: String,
    val options: List<ComponentOption>,
    val required: Boolean?,
) : ActionComponent()

data class CheckboxGroupComponent(
    override val type: ComponentType,
    override val id: Int?,
    val customId: String,
    val options: List<ComponentOption>,
    val minValues: Int?,
    val maxValues: Int?,
    val required: Boolean?,
) : ActionComponent()

data class CheckboxComponent(
    override val type: ComponentType,
    override val id: Int?,
    val customId: String,
    @SerializedName("default") val default: Boolean?,
) : ActionComponent()

data class ComponentOption(
    val value: String,
    val label: String,
    val description: String?,
    @SerializedName("default") val default: Boolean?,
) : Serializable

data class MediaGalleryItem(
    val media: UnfurledMediaItem,
    val description: String?,
    val spoiler: Boolean?,
) : Serializable

data class UnfurledMediaItem(
    val url: String,
    val proxyUrl: String?,
    val height: Int?,
    val width: Int?,
    val contentType: String?,
    val attachmentId: Long?,
) : Serializable

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
    USER_SELECT(5, SelectComponent::class.java),
    ROLE_SELECT(6, SelectComponent::class.java),
    MENTIONABLE_SELECT(7, SelectComponent::class.java),
    CHANNEL_SELECT(8, SelectComponent::class.java),
    SECTION(9, SectionComponent::class.java),
    TEXT_DISPLAY(10, TextDisplayComponent::class.java),
    THUMBNAIL(11, ThumbnailComponent::class.java),
    MEDIA_GALLERY(12, MediaGalleryComponent::class.java),
    FILE(13, FileComponent::class.java),
    SEPARATOR(14, SeparatorComponent::class.java),
    CONTAINER(17, ContainerComponent::class.java),
    LABEL(18, LabelComponent::class.java),
    FILE_UPLOAD(19, FileUploadComponent::class.java),
    RADIO_GROUP(21, RadioGroupComponent::class.java),
    CHECKBOX_GROUP(22, CheckboxGroupComponent::class.java),
    CHECKBOX(23, CheckboxComponent::class.java),
    ;

    /** Every select shares one view and one sheet; only where the options come from differs. */
    val isSelect: Boolean
        get() = this == SELECT || this == USER_SELECT || this == ROLE_SELECT ||
            this == MENTIONABLE_SELECT || this == CHANNEL_SELECT
}

@JsonAdapter(ButtonStyleTypeAdapter::class)
enum class ButtonStyle {
    UNKNOWN, PRIMARY, SECONDARY, SUCCESS, DANGER, LINK, PREMIUM,
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

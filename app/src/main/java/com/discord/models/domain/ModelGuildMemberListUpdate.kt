package com.discord.models.domain

import com.discord.api.guildmember.GuildMember
import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.annotations.JsonAdapter
import com.google.gson.internal.Streams
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

@JsonAdapter(ModelGuildMemberListUpdate.AdapterFactory::class)
data class ModelGuildMemberListUpdate(
    val guildId: Long,
    val id: String = EVERYONE_ID,
    val ops: List<Operation>,
    val groups: List<Group>,
) {
    val memberListId: String get() = id

    data class Group(val id: String, val count: Int) {
        val type: Type = when (id) {
            "offline" -> Type.OFFLINE
            "online" -> Type.ONLINE
            else -> Type.ROLE
        }

        enum class Type { ROLE, OFFLINE, ONLINE }
    }

    sealed class Item {
        data class GroupItem(val group: Group) : Item()
        data class MemberItem(val member: GuildMember) : Item()
    }

    sealed class Operation {
        data class Sync(val range: IntRange, val items: List<Item>) : Operation()
        data class Update(val index: Int, val item: Item) : Operation()
        data class Insert(val index: Int, val item: Item) : Operation()
        data class Delete(val index: Int) : Operation()
        data class Invalidate(val range: IntRange) : Operation()
    }

    class AdapterFactory : TypeAdapterFactory {
        override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
            if (type.rawType != ModelGuildMemberListUpdate::class.java) return null
            @Suppress("UNCHECKED_CAST")
            return Adapter(gson) as TypeAdapter<T>
        }
    }

    private class Adapter(private val gson: Gson) : TypeAdapter<ModelGuildMemberListUpdate>() {
        override fun read(reader: JsonReader): ModelGuildMemberListUpdate {
            val root = Streams.parse(reader).asJsonObject
            fun range(name: String, objectRoot: com.google.gson.JsonObject = root): IntRange {
                val values = objectRoot.getAsJsonArray(name)
                require(values.size() == 2) { "$name must contain two indices" }
                return values[0].asInt..values[1].asInt
            }
            fun item(element: com.google.gson.JsonElement): Item {
                val value = element.asJsonObject
                value.get("member")?.let {
                    return Item.MemberItem(gson.fromJson(it, GuildMember::class.java))
                }
                value.getAsJsonObject("group")?.let {
                    return Item.GroupItem(Group(it["id"].asString, it["count"].asInt))
                }
                throw IllegalArgumentException("member-list item requires member or group")
            }

            val groups = root.getAsJsonArray("groups").map {
                it.asJsonObject.let { group -> Group(group["id"].asString, group["count"].asInt) }
            }
            val operations = root.getAsJsonArray("ops").map { element ->
                val op = element.asJsonObject
                when (op["op"].asString) {
                    "SYNC" -> Operation.Sync(range("range", op), op.getAsJsonArray("items").map(::item))
                    "UPDATE" -> Operation.Update(op["index"].asInt, item(op["item"]))
                    "INSERT" -> Operation.Insert(op["index"].asInt, item(op["item"]))
                    "DELETE" -> Operation.Delete(op["index"].asInt)
                    "INVALIDATE" -> Operation.Invalidate(range("range", op))
                    else -> throw IllegalArgumentException("Invalid member-list operation: ${op["op"]}")
                }
            }
            return ModelGuildMemberListUpdate(
                guildId = root["guild_id"].asLong,
                id = root.get("id")?.asString ?: EVERYONE_ID,
                ops = operations,
                groups = groups,
            )
        }

        override fun write(writer: JsonWriter, value: ModelGuildMemberListUpdate?) {
            if (value == null) {
                writer.nullValue()
                return
            }
            val root = com.google.gson.JsonObject().apply {
                addProperty("guild_id", value.guildId)
                addProperty("id", value.id)
                add("groups", gson.toJsonTree(value.groups.map { mapOf("id" to it.id, "count" to it.count) }))
                add("ops", gson.toJsonTree(value.ops.map(::serializeOperation)))
            }
            Streams.write(root, writer)
        }

        private fun serializeOperation(operation: Operation): Map<String, Any> = when (operation) {
            is Operation.Sync -> mapOf("op" to "SYNC", "range" to operation.range.toWire(), "items" to operation.items.map(::serializeItem))
            is Operation.Update -> mapOf("op" to "UPDATE", "index" to operation.index, "item" to serializeItem(operation.item))
            is Operation.Insert -> mapOf("op" to "INSERT", "index" to operation.index, "item" to serializeItem(operation.item))
            is Operation.Delete -> mapOf("op" to "DELETE", "index" to operation.index)
            is Operation.Invalidate -> mapOf("op" to "INVALIDATE", "range" to operation.range.toWire())
        }

        private fun serializeItem(item: Item): Map<String, Any> = when (item) {
            is Item.GroupItem -> mapOf("group" to mapOf("id" to item.group.id, "count" to item.group.count))
            is Item.MemberItem -> mapOf("member" to item.member)
        }

        private fun IntRange.toWire() = listOf(first, last)
    }

    companion object {
        const val EVERYONE_ID = "everyone"
    }
}

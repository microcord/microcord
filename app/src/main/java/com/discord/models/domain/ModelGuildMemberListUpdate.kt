package com.discord.models.domain

import com.discord.api.guildmember.GuildMember
import com.discord.models.deserialization.gson.InboundGatewayGsonParser
import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.annotations.JsonAdapter
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

        object Parser : Model.Parser<Group> {
            override fun parse(reader: Model.JsonReader): Group {
                var id = ""
                var count = 0
                reader.nextObject { name ->
                    when (name) {
                        "id" -> id = reader.nextString("").orEmpty()
                        "count" -> count = reader.nextIntOrNull() ?: 0
                        else -> reader.skipValue()
                    }
                }
                return Group(id, count)
            }
        }
    }

    sealed class Item {
        data class GroupItem(val group: Group) : Item()
        data class MemberItem(val member: GuildMember) : Item()

        object Parser : Model.Parser<Item> {
            override fun parse(reader: Model.JsonReader): Item {
                var group: Group? = null
                var member: GuildMember? = null
                reader.nextObject { name ->
                    when (name) {
                        "group" -> group = Group.Parser.parse(reader)
                        "member" -> member =
                            InboundGatewayGsonParser.fromJson(reader, GuildMember::class.java)
                        else -> reader.skipValue()
                    }
                }
                member?.let { return MemberItem(it) }
                group?.let { return GroupItem(it) }
                throw IllegalArgumentException("either member or group must be present.")
            }
        }
    }

    sealed class Operation(
        open val item: Item? = null,
        open val items: List<Item>? = null,
    ) {
        data class Sync(val range: IntRange, override val items: List<Item>) : Operation(items = items)
        data class Update(val index: Int, override val item: Item) : Operation(item = item)
        data class Insert(val index: Int, override val item: Item) : Operation(item = item)
        data class Delete(val index: Int) : Operation()
        data class Invalidate(val range: IntRange) : Operation()

        object Parser : Model.Parser<Operation> {
            override fun parse(reader: Model.JsonReader): Operation {
                var op: String? = null
                var index: Int? = null
                var item: Item? = null
                var range: IntRange? = null
                var items: List<Item>? = null
                reader.nextObject { name ->
                    when (name) {
                        "op" -> op = reader.nextStringOrNull()
                        "index" -> index = reader.nextIntOrNull()
                        "item" -> item = Item.Parser.parse(reader)
                        "items" -> items = reader.nextList { Item.Parser.parse(reader) }
                        "range" -> range = reader.nextList { reader.nextIntOrNull() }
                            .let { it[0]..it[1] }
                        else -> reader.skipValue()
                    }
                }
                return when (op) {
                    "SYNC" -> Sync(checkNotNull(range), checkNotNull(items))
                    "UPDATE" -> Update(checkNotNull(index), checkNotNull(item))
                    "INSERT" -> Insert(checkNotNull(index), checkNotNull(item))
                    "DELETE" -> Delete(checkNotNull(index))
                    "INVALIDATE" -> Invalidate(checkNotNull(range))
                    else -> throw IllegalArgumentException("Invalid operation type: $op")
                }
            }
        }
    }

    /** Streaming parser used when the payload arrives straight off the gateway socket. */
    object Parser : Model.Parser<ModelGuildMemberListUpdate> {
        override fun parse(reader: Model.JsonReader): ModelGuildMemberListUpdate {
            var guildId: Long? = null
            var id: String? = null
            var ops: List<Operation>? = null
            var groups: List<Group>? = null
            reader.nextObject { name ->
                when (name) {
                    "guild_id" -> guildId = reader.nextLongOrNull()
                    "id" -> id = reader.nextStringOrNull()
                    "ops" -> ops = reader.nextList { Operation.Parser.parse(reader) }
                    "groups" -> groups = reader.nextList { Group.Parser.parse(reader) }
                    else -> reader.skipValue()
                }
            }
            return ModelGuildMemberListUpdate(
                guildId = checkNotNull(guildId),
                id = id ?: EVERYONE_ID,
                ops = checkNotNull(ops),
                groups = checkNotNull(groups),
            )
        }
    }

    class AdapterFactory : TypeAdapterFactory {
        override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
            if (type.rawType != ModelGuildMemberListUpdate::class.java) return null
            @Suppress("UNCHECKED_CAST")
            return Adapter() as TypeAdapter<T>
        }
    }

    /**
     * Bridges Gson callers onto [Parser]; the payload is consumed straight off the token stream,
     * never buffered into a tree. There is no write direction: member list updates are inbound only.
     */
    private class Adapter : TypeAdapter<ModelGuildMemberListUpdate>() {
        override fun read(reader: JsonReader): ModelGuildMemberListUpdate =
            Parser.parse(Model.JsonReader(reader))

        override fun write(writer: JsonWriter, value: ModelGuildMemberListUpdate?) {
            throw UnsupportedOperationException("ModelGuildMemberListUpdate is inbound only.")
        }
    }

    companion object {
        const val EVERYONE_ID = "everyone"
    }
}

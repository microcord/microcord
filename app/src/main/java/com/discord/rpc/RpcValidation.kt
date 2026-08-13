package com.discord.rpc

import org.json.JSONArray
import org.json.JSONObject

/**
 * The subset of joi the RPC schemas use: unknown keys always pass, and `convert: false` means `"5"`
 * never satisfies a number. Failure messages are joi's own text, which goes out on the wire.
 */
sealed class RpcRule(val optional: Boolean, val nullable: Boolean) {
    protected abstract fun typeName(): String

    protected open fun checkValue(name: String, value: Any): String? = null

    fun validate(name: String, value: Any?): String? {
        if (value == null || value === JSONObject.NULL) {
            return when {
                nullable -> null
                optional -> "\"$name\" must be a ${typeName()}"
                else -> "\"$name\" is required"
            }
        }
        return checkValue(name, value)
    }
}

class RpcString(
    optional: Boolean = true,
    nullable: Boolean = false,
    private val min: Int? = null,
    private val max: Int? = null,
    private val uri: Boolean = false,
    private val valid: List<String>? = null,
) : RpcRule(optional, nullable) {
    override fun typeName() = "string"

    override fun checkValue(name: String, value: Any): String? {
        if (value !is String) return "\"$name\" must be a string"
        if (valid != null && value !in valid) return "\"$name\" must be one of [${valid.joinToString(", ")}]"
        if (min != null && value.length < min) {
            return "\"$name\" length must be at least $min characters long"
        }
        if (max != null && value.length > max) {
            return "\"$name\" length must be less than or equal to $max characters long"
        }
        if (uri && !URI_PATTERN.matches(value)) return "\"$name\" must be a valid uri"
        return null
    }

    private companion object {
        val URI_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:.+")
    }
}

class RpcNumber(
    optional: Boolean = true,
    nullable: Boolean = false,
    private val min: Number? = null,
    private val max: Number? = null,
    private val valid: List<Number>? = null,
) : RpcRule(optional, nullable) {
    override fun typeName() = "number"

    override fun checkValue(name: String, value: Any): String? {
        if (value !is Number || value is Char) return "\"$name\" must be a number"
        if (valid != null && valid.none { it.toDouble() == value.toDouble() }) {
            return "\"$name\" must be one of [${valid.joinToString(", ")}]"
        }
        if (min != null && value.toDouble() < min.toDouble()) {
            return "\"$name\" must be larger than or equal to $min"
        }
        if (max != null && value.toDouble() > max.toDouble()) {
            return "\"$name\" must be less than or equal to $max"
        }
        return null
    }
}

class RpcBoolean(optional: Boolean = true, nullable: Boolean = false) : RpcRule(optional, nullable) {
    override fun typeName() = "boolean"

    override fun checkValue(name: String, value: Any): String? =
        if (value is Boolean) null else "\"$name\" must be a boolean"
}

class RpcArray(
    optional: Boolean = true,
    nullable: Boolean = false,
    private val items: RpcRule? = null,
    private val min: Int? = null,
    private val max: Int? = null,
    private val length: Int? = null,
) : RpcRule(optional, nullable) {
    override fun typeName() = "array"

    override fun checkValue(name: String, value: Any): String? {
        if (value !is JSONArray) return "\"$name\" must be an array"
        if (length != null && value.length() != length) return "\"$name\" must contain $length items"
        if (min != null && value.length() < min) return "\"$name\" must contain at least $min items"
        if (max != null && value.length() > max) return "\"$name\" must contain less than or equal to $max items"
        if (items != null) {
            for (index in 0 until value.length()) {
                items.validate(name, value.opt(index))?.let { return it }
            }
        }
        return null
    }
}

class RpcObject(
    optional: Boolean = true,
    nullable: Boolean = false,
    private val keys: Map<String, RpcRule> = emptyMap(),
) : RpcRule(optional, nullable) {
    override fun typeName() = "object"

    override fun checkValue(name: String, value: Any): String? {
        if (value !is JSONObject) return "\"$name\" must be an object"
        return childFailure(value, keys)
    }

    /** Applies this schema to a command's `args`, throwing `INVALID_PAYLOAD` on the first failure. */
    fun assert(args: JSONObject?) {
        if (args == null) {
            if (!optional) throw RpcError.error(RpcErrors.INVALID_PAYLOAD, "\"value\" is required")
            return
        }
        childFailure(args, keys)?.let { throw RpcError.error(RpcErrors.INVALID_PAYLOAD, it) }
    }

    private companion object {
        fun childFailure(value: JSONObject, keys: Map<String, RpcRule>): String? {
            keys.forEach { (key, rule) ->
                val present = value.has(key)
                if (!present && rule.optional) return@forEach
                rule.validate(key, value.opt(key))?.let { return "child \"$key\" fails because [$it]" }
            }
            return null
        }
    }
}

fun rpcSchema(required: Boolean = true, block: MutableMap<String, RpcRule>.() -> Unit): RpcObject =
    RpcObject(optional = !required, keys = linkedMapOf<String, RpcRule>().apply(block))

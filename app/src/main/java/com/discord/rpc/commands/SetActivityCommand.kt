package com.discord.rpc.commands

import com.discord.api.activity.ActivityFlags
import com.discord.api.application.canLaunchFrame
import com.discord.rpc.OAuth2Scopes
import com.discord.rpc.RpcActivities
import com.discord.rpc.RpcActivityAssets
import com.discord.stores.StoreStream
import com.discord.stores.UserSettings
import com.discord.rpc.RpcArray
import com.discord.rpc.RpcBoolean
import com.discord.rpc.RpcCommand
import com.discord.rpc.RpcCommands
import com.discord.rpc.RpcError
import com.discord.rpc.RpcErrors
import com.discord.rpc.RpcNumber
import com.discord.rpc.RpcObject
import com.discord.rpc.RpcScopeConfig
import com.discord.rpc.RpcScopes
import com.discord.rpc.RpcString
import com.discord.rpc.RpcTransportTypes
import com.discord.rpc.rpcSchema
import org.json.JSONArray
import org.json.JSONObject
import rx.Observable

private val activitySchema = RpcObject(
    nullable = true,
    keys = linkedMapOf(
        "name" to RpcString(min = 1, max = 128),
        "state" to RpcString(min = 2, max = 128),
        "state_url" to RpcString(min = 1, max = 256, uri = true),
        "details" to RpcString(min = 2, max = 128),
        "details_url" to RpcString(min = 1, max = 256, uri = true),
        "timestamps" to RpcObject(
            keys = linkedMapOf(
                "start" to RpcNumber(min = 1),
                "end" to RpcNumber(min = 1),
            ),
        ),
        "assets" to RpcObject(
            keys = linkedMapOf(
                "large_image" to RpcString(min = 1, max = 300),
                "large_text" to RpcString(min = 2, max = 128),
                "large_url" to RpcString(min = 1, max = 256, uri = true),
                "small_image" to RpcString(min = 1, max = 300),
                "small_text" to RpcString(min = 2, max = 128),
                "small_url" to RpcString(min = 1, max = 256, uri = true),
                "invite_cover_image" to RpcString(min = 1, max = 300),
            ),
        ),
        "party" to RpcObject(
            keys = linkedMapOf(
                "id" to RpcString(min = 2, max = 128),
                "size" to RpcArray(items = RpcNumber(min = 0), length = 2),
                "privacy" to RpcNumber(valid = listOf(0, 1)),
            ),
        ),
        "secrets" to RpcObject(
            keys = linkedMapOf(
                "match" to RpcString(min = 2, max = 128),
                "join" to RpcString(min = 2, max = 128),
                "spectate" to RpcString(min = 2, max = 128),
            ),
        ),
        "buttons" to RpcArray(
            items = RpcObject(
                keys = linkedMapOf(
                    "label" to RpcString(optional = false, min = 1, max = 32),
                    "url" to RpcString(optional = false, min = 1, max = 512, uri = true),
                ),
            ),
            min = 1,
            max = 2,
        ),
        "instance" to RpcBoolean(),
        "supported_platforms" to RpcArray(items = RpcString(min = 1, max = 32), min = 1, max = 10),
        "type" to RpcNumber(valid = listOf(0, 2, 3, 5)),
        "status_display_type" to RpcNumber(valid = listOf(0, 1, 2)),
    ),
)

val setActivityCommand: Map<String, RpcCommand> = mapOf(
    RpcCommands.SET_ACTIVITY to rpcCommand(
        scope = RpcScopeConfig.Any(
            listOf(OAuth2Scopes.RPC, OAuth2Scopes.RPC_ACTIVITIES_WRITE, RpcScopes.LOCAL),
        ),
        validation = rpcSchema {
            put("pid", RpcNumber(min = 0))
            put("activity", activitySchema)
        },
    ) { context ->
        val transport = context.socket.transport
        if (transport !in ALLOWED_TRANSPORTS) {
            throw RpcError.error(
                RpcErrors.INVALID_COMMAND,
                "command not available from \"$transport\" transport",
            )
        }
        if (!context.args.has("pid") && transport == RpcTransportTypes.IPC) {
            throw RpcError.error(RpcErrors.INVALID_COMMAND, "nonzero pid required")
        }
        val applicationId = context.socket.application.id
        val activity = context.args.optJSONObject("activity")
        if (activity == null) {
            // Absent and null both mean "retire mine", not "do nothing".
            RpcActivities.set(context.socket.id, null)
            Observable.just(null)
        } else {
            validateSecrets(activity, applicationId)
            normalizeActivity(activity, context.socket.application.name, applicationId, transport)
            RpcActivityAssets.resolve(activity, applicationId).map { resolved ->
                // Resolution is a round trip; the socket may have left before it lands.
                if (context.isSocketConnected()) {
                    RpcActivities.parse(resolved)?.let { RpcActivities.set(context.socket.id, it) }
                }
                resolved
            }
        }
    },
)

/** Rewrites the caller's own object in place; the reply echoes that same object. */
private fun normalizeActivity(
    activity: JSONObject,
    applicationName: String?,
    applicationId: Long?,
    transport: String,
): JSONObject {
    if (activity.optString("name").isEmpty() && applicationName != null) {
        activity.put("name", applicationName)
    }
    activity.put("application_id", applicationId?.toString() ?: JSONObject.NULL)
    activity.put("platform", if (transport == RpcTransportTypes.POST_MESSAGE) "embedded" else "desktop")
    val flags = computeFlags(activity, applicationId, transport)
    if (flags > 0) activity.put("flags", flags)
    activity.remove("instance")
    activity.optJSONObject("party")?.remove("privacy")
    if (activity.opt("type") !is Number) activity.put("type", 0)

    activity.optJSONObject("timestamps")?.let { timestamps ->
        listOf("start", "end").forEach { key ->
            val value = timestamps.opt(key)
            if (value is Number && looksLikeSeconds(value.toLong())) {
                timestamps.put(key, value.toLong() * 1000)
            }
        }
    }

    // The labels stay on `buttons`; their urls move into metadata, which is always present.
    val buttons = activity.optJSONArray("buttons")
    val metadata = JSONObject()
    if (buttons != null) {
        val labels = JSONArray()
        val urls = JSONArray()
        for (index in 0 until buttons.length()) {
            val button = buttons.optJSONObject(index) ?: continue
            labels.put(button.optString("label"))
            urls.put(button.optString("url"))
        }
        activity.put("buttons", labels)
        metadata.put("button_urls", urls)
    }
    activity.put("metadata", metadata)
    return activity
}

/** `instance` and `party.privacy` survive only as flags, which is why they are dropped afterwards. */
private fun computeFlags(activity: JSONObject, applicationId: Long?, transport: String): Int {
    val embedded = transport == RpcTransportTypes.POST_MESSAGE
    var flags = 0
    if (activity.optBoolean("instance")) flags = flags or ActivityFlags.INSTANCE
    if (activity.optJSONObject("secrets")?.opt("join") is String) flags = flags or ActivityFlags.JOIN
    if (embedded && StoreStream.getApplication().getApplication(applicationId ?: 0L).canLaunchFrame()) {
        flags = flags or ActivityFlags.CONTEXTLESS
    }
    if (embedded) return withPartyPrivacy(flags or ActivityFlags.EMBEDDED)
    val isPublicParty = (activity.optJSONObject("party")?.opt("privacy") as? Number)?.toInt() == PARTY_PRIVACY_PUBLIC
    return if (isPublicParty) withPartyPrivacy(flags) else flags
}

private fun withPartyPrivacy(flags: Int): Int {
    var result = flags
    result = if (UserSettings.allowActivityPartyPrivacyFriends.get()) {
        result or ActivityFlags.PARTY_PRIVACY_FRIENDS
    } else {
        result and ActivityFlags.PARTY_PRIVACY_FRIENDS.inv()
    }
    result = if (UserSettings.allowActivityPartyPrivacyVoiceChannel.get()) {
        result or ActivityFlags.PARTY_PRIVACY_VOICE_CHANNEL
    } else {
        result and ActivityFlags.PARTY_PRIVACY_VOICE_CHANNEL.inv()
    }
    return result
}

private const val PARTY_PRIVACY_PUBLIC = 1

private fun looksLikeSeconds(value: Long) =
    System.currentTimeMillis().toString().length - value.toString().length > 2

private fun validateSecrets(activity: JSONObject, applicationId: Long?) {
    val secrets = activity.optJSONObject("secrets") ?: return
    val partyId = activity.optJSONObject("party")?.opt("id") as? String
    val values = secrets.keys().asSequence().mapNotNull { secrets.opt(it) as? String }.toList()

    if (partyId != null && applicationId?.toString() !in UNIQUE_SECRETS_ALLOWLIST && partyId in values) {
        throw RpcError.error(RpcErrors.INVALID_ACTIVITY_SECRET, "secrets cannot match the party id")
    }
    if (values.size != values.distinct().size) {
        throw RpcError.error(RpcErrors.INVALID_ACTIVITY_SECRET, "secrets must be unique")
    }
    if (activity.has("buttons")) {
        throw RpcError.error(
            RpcErrors.INVALID_ACTIVITY_SECRET,
            "secrets cannot currently be sent with buttons",
        )
    }
}

private val ALLOWED_TRANSPORTS =
    setOf(RpcTransportTypes.IPC, RpcTransportTypes.WEBSOCKET, RpcTransportTypes.POST_MESSAGE)

private val UNIQUE_SECRETS_ALLOWLIST = setOf("1402418171662569542")

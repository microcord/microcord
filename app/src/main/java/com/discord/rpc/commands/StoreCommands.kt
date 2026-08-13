package com.discord.rpc.commands

import com.discord.models.domain.ModelEntitlement
import com.discord.models.domain.ModelSku
import com.discord.restapi.RestAPIParams
import com.discord.rpc.RpcCommand
import com.discord.rpc.RpcCommands
import com.discord.rpc.RpcError
import com.discord.rpc.RpcErrors
import com.discord.rpc.RpcScopeConfig
import com.discord.rpc.RpcScopes
import com.discord.utilities.rest.RestAPI
import org.json.JSONArray
import org.json.JSONObject
import rx.Observable

private fun storeApplicationId(transport: String, applicationId: Long?): Long {
    validateTransportType(transport)
    return applicationId ?: throw RpcError.error(RpcErrors.INVALID_COMMAND, "No application.")
}

/** The SDK's schema rejects a null price, so unpriced SKUs are filtered out along with groups. */
private fun skusFor(applicationId: Long): Observable<JSONArray> =
    RestAPI.api.getPublishedListings(applicationId)
        .map { listings ->
            JSONArray().apply {
                listings.map { it.sku }
                    .filter { it.type != SKU_TYPE_SUBSCRIPTION_GROUP && it.price != null }
                    .forEach { put(transformSku(it)) }
            }
        }

private fun entitlementsFor(applicationId: Long): Observable<JSONArray> =
    RestAPI.api.getMyEntitlements(applicationId)
        .map { entitlements -> JSONArray().apply { entitlements.forEach { put(transformEntitlement(it)) } } }

private const val SKU_TYPE_SUBSCRIPTION_GROUP = 6

private fun transformSku(sku: ModelSku): JSONObject = JSONObject()
    .put("id", sku.id.toString())
    .put("name", sku.name)
    .put("type", sku.type)
    .put(
        "price",
        sku.price?.let { JSONObject().put("amount", it.amount).put("currency", it.currency) }
            ?: JSONObject.NULL,
    )
    .put("application_id", sku.applicationId.toString())
    .put("flags", sku.flags)
    .put("release_date", JSONObject.NULL)

private fun transformEntitlement(entitlement: ModelEntitlement): JSONObject = JSONObject()
    .put("id", entitlement.id.toString())
    .put("sku_id", entitlement.skuId.toString())
    .put("application_id", entitlement.applicationId.toString())
    .put("user_id", entitlement.userId.toString())
    .put("type", entitlement.type)
    .put("consumed", entitlement.consumed ?: false)
    .put("parent_id", entitlement.parentId?.toString() ?: JSONObject.NULL)

/** Unscoped because the reference misplaces its `$any` key, leaving `scope` undefined. */
val storeCommands: Map<String, RpcCommand> = mapOf(
    RpcCommands.GET_SKUS to rpcCommand { context ->
        skusFor(storeApplicationId(context.socket.transport, context.socket.application.id))
            .map { it as Any? }
    },

    RpcCommands.GET_ENTITLEMENTS to rpcCommand { context ->
        entitlementsFor(storeApplicationId(context.socket.transport, context.socket.application.id))
            .map { it as Any? }
    },

    RpcCommands.GET_SKUS_EMBEDDED to rpcCommand { context ->
        skusFor(storeApplicationId(context.socket.transport, context.socket.application.id))
            .map { JSONObject().put("skus", it) as Any? }
    },

    RpcCommands.GET_ENTITLEMENTS_EMBEDDED to rpcCommand { context ->
        entitlementsFor(storeApplicationId(context.socket.transport, context.socket.application.id))
            .map { JSONObject().put("entitlements", it) as Any? }
    },

    RpcCommands.GET_APPLICATION_TICKET to rpcCommand(
        scope = RpcScopeConfig.One(RpcScopes.LOCAL),
    ) { context ->
        val applicationId = context.socket.application.id
            ?: throw RpcError.error(RpcErrors.INVALID_COMMAND, "No application.")
        RestAPI.api.getApplicationTicket(applicationId, RestAPIParams.ApplicationTicket())
            .map { JSONObject().put("ticket", it.ticket) as Any? }
    },
)

package com.discord.models.domain

import com.discord.api.application.Application
import com.discord.models.deserialization.gson.InboundGatewayGsonParser

/** A SKU as it is offered in the store, i.e. the thing a gift code resolves to. */
data class ModelStoreListing(
    val sku: ModelSku,
    val id: Long,
) {
    object Parser : Model.Parser<ModelStoreListing> {
        override fun parse(reader: Model.JsonReader): ModelStoreListing {
            var sku: ModelSku? = null
            var id: Long? = null
            reader.nextObject { name ->
                when (name) {
                    "sku" -> sku = ModelSku.Parser.parse(reader)
                    "id" -> id = reader.nextLongOrNull()
                    else -> reader.skipValue()
                }
            }
            return ModelStoreListing(checkNotNull(sku), id ?: 0)
        }
    }
}

/** An entitlement the user owns, as shown in the library. */
data class ModelLibraryApplication(
    val application: Application,
    val createdAt: String,
    val skuId: Long,
    val flags: Int,
    val branchId: Long,
) {
    object Parser : Model.Parser<ModelLibraryApplication> {
        override fun parse(reader: Model.JsonReader): ModelLibraryApplication {
            var application: Application? = null
            var createdAt: String? = null
            var skuId: Long? = null
            var flags: Int? = null
            var branchId: Long? = null
            reader.nextObject { name ->
                when (name) {
                    "application" -> application =
                        InboundGatewayGsonParser.fromJson(reader, Application::class.java)
                    "created_at" -> createdAt = reader.nextStringOrNull()
                    "sku_id" -> skuId = reader.nextLongOrNull()
                    "flags" -> flags = reader.nextIntOrNull()
                    "branch_id" -> branchId = reader.nextLongOrNull()
                    else -> reader.skipValue()
                }
            }
            return ModelLibraryApplication(
                application = checkNotNull(application),
                createdAt = createdAt ?: "",
                skuId = skuId ?: 0,
                flags = flags ?: 0,
                branchId = branchId ?: 0,
            )
        }
    }
}

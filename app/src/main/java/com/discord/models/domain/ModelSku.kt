package com.discord.models.domain

import com.discord.api.application.Application
import com.discord.api.premium.PremiumTier
import com.discord.models.deserialization.gson.InboundGatewayGsonParser

/** Nitro Classic. */
const val PREMIUM_TIER_1_SKU_ID = 521846918637420545L

/** Nitro. */
const val PREMIUM_TIER_2_SKU_ID = 521847234246082599L

const val SKU_FLAG_AVAILABLE = 4
const val SKU_FLAG_STICKER = 16

/** A purchasable item: a game, a Nitro tier or a sticker pack. */
data class ModelSku(
    val applicationId: Long,
    val name: String,
    val id: Long,
    val application: Application?,
    val premium: Boolean,
    val type: Int,
    val price: Price?,
    val flags: Int,
    val externalSkuStrategies: Map<Int, ExternalSkuStrategy>?,
) {
    val skuCategory: SkuCategory
        get() = when (id) {
            PREMIUM_TIER_1_SKU_ID -> SkuCategory.NITRO_CLASSIC
            PREMIUM_TIER_2_SKU_ID -> SkuCategory.NITRO
            else -> SkuCategory.GAME
        }

    fun isAvailable(): Boolean = flags and SKU_FLAG_AVAILABLE > 0

    fun isStickerPack(): Boolean = flags and SKU_FLAG_STICKER > 0

    enum class SkuCategory { GAME, NITRO, NITRO_CLASSIC }

    /** Base price plus the per-Nitro-tier discounts that apply to it. */
    data class Price(
        val amount: Int,
        val currency: String,
        val premium: Map<PremiumTier, ModelPremiumSkuPrice>? = null,
    ) {
        object Parser : Model.Parser<Price> {
            override fun parse(reader: Model.JsonReader): Price {
                var amount: Int? = null
                var currency: String? = null
                reader.nextObject { name ->
                    when (name) {
                        "amount" -> amount = reader.nextIntOrNull()
                        "currency" -> currency = reader.nextStringOrNull()
                        else -> reader.skipValue()
                    }
                }
                return Price(amount ?: -1, currency ?: "")
            }
        }
    }

    /** Discounted price for holders of one Nitro tier. */
    data class ModelPremiumSkuPrice(
        val amount: Int,
        val percentage: Int,
    )

    /** How a SKU is billed outside Discord's own payment flow. */
    data class ExternalSkuStrategy(val type: Int) {
        val strategyType: ExternalStrategyTypes get() = ExternalStrategyTypes.from(type)
    }

    enum class ExternalStrategyTypes(val intRepresentation: Int?) {
        CONSTANT(1),
        APPLE(2),
        GOOGLE(3),
        UNKNOWN(null);

        companion object {
            fun from(value: Int): ExternalStrategyTypes =
                entries.firstOrNull { it.intRepresentation == value } ?: UNKNOWN
        }
    }

    object Parser : Model.Parser<ModelSku> {
        override fun parse(reader: Model.JsonReader): ModelSku {
            var applicationId: Long? = null
            var name: String? = null
            var id: Long? = null
            var application: Application? = null
            var premium: Boolean? = null
            var type: Int? = null
            var price: Price? = null
            var flags: Int? = null
            reader.nextObject { field ->
                when (field) {
                    "application_id" -> applicationId = reader.nextLongOrNull()
                    "name" -> name = reader.nextStringOrNull()
                    "id" -> id = reader.nextLongOrNull()
                    "application" -> application =
                        InboundGatewayGsonParser.fromJson(reader, Application::class.java)
                    "premium" -> premium = reader.nextBooleanOrNull()
                    "type" -> type = reader.nextIntOrNull()
                    "price" -> price = Price.Parser.parse(reader)
                    "flags" -> flags = reader.nextIntOrNull()
                    else -> reader.skipValue()
                }
            }
            return ModelSku(
                applicationId = applicationId ?: 0,
                name = name ?: "",
                id = id ?: 0,
                application = application,
                premium = checkNotNull(premium),
                type = checkNotNull(type),
                price = price,
                flags = checkNotNull(flags),
                externalSkuStrategies = null,
            )
        }
    }
}

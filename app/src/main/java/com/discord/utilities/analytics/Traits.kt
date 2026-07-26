package com.discord.utilities.analytics

import com.discord.models.domain.premium.SubscriptionPlanType
import java.io.Serializable

/**
 * Reusable property groups shared by map-backed analytics events.
 *
 * Each nested type knows how to write itself into an event's flat property map, so a call site
 * passes a typed [Location] / [Source] / … rather than remembering the `location_page`,
 * `source_section`, `sku_id` … key spellings. The schema-backed equivalents live in
 * `com.discord.analytics.generated.traits`.
 */
object Traits {

    /** Where the event happened. Serialized under `location_*`. */
    data class Location(
        val page: String? = null,
        val section: String? = null,
        val _object: String? = null,
        val objectType: String? = null,
        val promotionId: Int? = null,
    ) : Serializable {

        fun serializeTo(properties: MutableMap<String, Any>) {
            page?.let { properties["location_page"] = it }
            section?.let { properties["location_section"] = it }
            _object?.let { properties["location_object"] = it }
            objectType?.let { properties["location_object_type"] = it }
            promotionId?.let { properties["location_promotion_id"] = it }
        }

        object Obj {
            const val ACTIVITY_ACTION = "Activity Action"
            const val BOOST_GEM_ICON = "Boost Gem Icon"
            const val BUTTON_CTA = "Button CTA"
            const val BUTTON_UPSELL = "Button Upsell"
            const val EDIT_PROFILE_BANNER = "Edit Profile Banner"
            const val LIST_ITEM = "List Item"
            const val PRIVATE_THREAD_CHECKBOX = "Private Thread Checkbox"
        }

        object ObjType {
            const val BUY = "buy"
            const val SEARCH = "Search"
            const val TIER_1 = "tier_1"
            const val TIER_2 = "tier_2"
            const val TIER_3 = "tier_3"
        }

        object Page {
            const val BOOST_MARKETING = "User-Facing Premium Guild Subscription Fullscreen Modal"
            const val CHANNEL_SETTINGS = "Channel Settings"
            const val GUILD_CHANNEL = "Guild Channel"
            const val USER_SETTINGS = "User Settings"
        }

        object Section {
            const val ANDROID_CANNOT_MANAGE_SUBSCRIPTION = "Android Cannot Manage Subscription"
            const val CHANNEL_TEXT_AREA = "Channel Text Area"
            const val EMOJI_PICKER_POPOUT = "Emoji Picker Popout"
            const val EMOJI_SHEET_UPSELL = "Emoji Upsell Popout"
            const val EMPTY_STICKER_PICKER_UPSELL = "Empty Sticker Picker Upsell"
            const val EXPRESSION_PICKER = "Expression Picker"
            const val FOOTER = "Footer"
            const val GUILD_POPOUT = "Guild Popout"
            const val HEADER = "Header"
            const val PLAYSTATION_UPSELL = "Playstation Upsell"
            const val PREMIUM_GUILD_SUBSCRIPTION_CONFIRMATION = "Premium Guild Subscription Confirmation"
            const val PREMIUM_GUILD_UPSELL_MODAL = "Premium Guild Upsell Modal"
            const val PREMIUM_GUILD_USER_MODAL = "Premium Guild User Modal"
            const val SETTINGS_TEXT_AND_IMAGES = "Text & Images"
            const val STICKER_PICKER_UPSELL = "Sticker Picker Upsell"
            const val STICKER_POPOUT = "Sticker Popout"
            const val STICKER_PREMIUM_TIER_2_UPSELL_MODAL = "Stickers Nitro Upsell Modal"
            const val THREAD_ARCHIVAL_DURATION_SHEET = "Thread Archival Duration Sheet"
            const val THREAD_CREATION_OPTIONS = "Thread Creation Options"
            const val USER_PROFILE = "User Profile"
        }
    }

    /** Where the user came *from*. Serialized under `source_*`. */
    data class Source(
        val page: String? = null,
        val section: String? = null,
        val _object: String? = null,
        val objectType: String? = null,
        val promotionId: Int? = null,
    ) {

        fun serializeTo(properties: MutableMap<String, Any>) {
            page?.let { properties["source_page"] = it }
            section?.let { properties["source_section"] = it }
            _object?.let { properties["source_object"] = it }
            objectType?.let { properties["source_object_type"] = it }
            promotionId?.let { properties["source_promotion_id"] = it }
        }

        object Obj {
            const val GIF_PICKER = "GIF Picker"
        }
    }

    /** A payment attempt. Prices are minor units, as the billing API reports them. */
    data class Payment(
        val type: String,
        val price: Int,
        val regularPrice: Int,
        val currency: String,
        val isGift: Boolean,
    ) {

        fun serializeTo(properties: MutableMap<String, Any>) {
            properties["payment_type"] = type
            properties["price"] = price
            properties["regular_price"] = regularPrice
            properties["currency"] = currency
            properties["is_gift"] = isGift
        }

        object Type {
            const val STICKER = "sticker"
            const val SUBSCRIPTION = "subscription"
        }
    }

    /** The store listing a payment refers to. */
    data class StoreSku(
        val skuId: Long,
        val skuType: Int,
        val applicationId: Long,
        val storeTitle: String,
    ) {
        fun serializeTo(properties: MutableMap<String, Any>) {
            properties["sku_id"] = skuId
            properties["sku_type"] = skuType
            properties["application_id"] = applicationId
            properties["store_title"] = storeTitle
        }
    }

    /** The subscription plan a payment refers to. */
    data class Subscription(
        val gatewayPlanId: String?,
        val type: Int?,
        val planId: Long? = null,
    ) {

        fun serializeTo(properties: MutableMap<String, Any>) {
            gatewayPlanId?.let { properties["subscription_plan_gateway_plan_id"] = it }
            type?.let { properties["subscription_type"] = it }
            planId?.let { properties["subscription_plan_id"] = it }
        }

        companion object {
            fun from(subscriptionPlanType: SubscriptionPlanType): Subscription =
                Subscription(subscriptionPlanType.planTypeString, TYPE_PREMIUM, subscriptionPlanType.planId)

            fun withGatewayPlanId(gatewayPlanId: String): Subscription =
                Subscription(gatewayPlanId, TYPE_PREMIUM, null)

            /** The only `subscription_type` the reference ever emits from the mobile client. */
            private const val TYPE_PREMIUM = 1
        }
    }
}

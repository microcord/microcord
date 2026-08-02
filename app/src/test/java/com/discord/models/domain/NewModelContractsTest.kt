package com.discord.models.domain

import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.billing.ModelInvoicePreview
import com.discord.models.domain.emoji.EmojiCategory
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.domain.emoji.ModelEmojiGuild
import com.discord.models.domain.spotify.ModelSpotifyTrack
import com.discord.models.gifpicker.dto.GifDto
import com.discord.models.gifpicker.dto.ModelGif
import com.discord.models.gifpicker.dto.TrendingGifsResponseDto
import com.discord.models.sticker.dto.ModelStickerPack
import com.discord.models.store.dto.ModelStoreAsset
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Field-parity and wire-shape coverage for the models restored in this pass. */
class NewModelContractsTest {
    @Test
    fun customEmojiUsesDiscordCdnWithoutStartupMutation() {
        assertEquals(
            "https://cdn.discordapp.com/emojis/42.webp?size=64&quality=lossless",
            ModelEmojiCustom.getImageUri(42L, false, 64),
        )
    }

    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerDiscordApiTypeAdapters()
        .create()

    private fun fieldCount(type: Class<*>) =
        type.declaredFields.count { !Modifier.isStatic(it.modifiers) }

    @Test
    fun `gif picker dtos round trip`() {
        val json = """
            {
              "categories": [{"name": "Reactions", "src": "https://example.invalid/c.gif"}],
              "gifs": [{"src": "https://example.invalid/g.gif"}]
            }
        """.trimIndent()

        val response = gson.fromJson(json, TrendingGifsResponseDto::class.java)

        assertEquals("Reactions", response.categories.single().name)
        assertEquals("https://example.invalid/g.gif", response.gifs.single().src)
        assertEquals(response, gson.fromJson(gson.toJson(response), TrendingGifsResponseDto::class.java))

        val dto = gson.fromJson(
            """{"src":"https://example.invalid/a.gif","url":"https://tenor.invalid/a","width":4,"height":3}""",
            GifDto::class.java,
        )
        assertEquals(
            ModelGif("https://example.invalid/a.gif", "https://tenor.invalid/a", 4, 3),
            ModelGif.createFromGifDto(dto),
        )
        assertEquals(4, fieldCount(GifDto::class.java))
        assertEquals(4, fieldCount(ModelGif::class.java))
    }

    @Test
    fun `store asset keeps optional dimensions`() {
        val asset = gson.fromJson(
            """{"id":"1","size":1024,"mime_type":"image/png","filename":"a.png","width":null,"height":8}""",
            ModelStoreAsset::class.java,
        )

        assertEquals(1L, asset.id)
        assertEquals("image/png", asset.mimeType)
        assertNull(asset.width)
        assertEquals(8, asset.height)
        assertEquals(6, fieldCount(ModelStoreAsset::class.java))
    }

    @Test
    fun `sticker pack derives its cover and premium flags from the listing`() {
        val json = """
            {
              "id": "1",
              "name": "Test Pack",
              "sku_id": "2",
              "cover_sticker_id": "11",
              "description": "sanitised fixture",
              "banner_asset_id": "3",
              "stickers": [
                {"id": "10", "name": "one", "description": "", "format_type": 1, "tags": "", "type": 1},
                {"id": "11", "name": "two", "description": "", "format_type": 2, "tags": "", "type": 1}
              ],
              "store_listing": {
                "id": "4",
                "description": "listing",
                "unpublished_at": null,
                "sku": {"application_id": "5", "name": "Test Pack", "id": "2", "premium": true, "type": 1, "flags": 20}
              }
            }
        """.trimIndent()

        val pack = gson.fromJson(json, ModelStickerPack::class.java)

        assertEquals(11L, pack.coverSticker.id)
        assertTrue(pack.isAnimatedPack())
        assertTrue(pack.isPremiumPack())
        assertTrue(pack.canBePurchased())
        assertFalse(pack.isLimitedPack())
        assertEquals(8, fieldCount(ModelStickerPack::class.java))
    }

    @Test
    fun `sku flags decode availability and sticker packs`() {
        val sku = ModelSku(
            applicationId = 1,
            name = "Nitro",
            id = PREMIUM_TIER_2_SKU_ID,
            application = null,
            premium = true,
            type = 1,
            price = ModelSku.Price(499, "usd"),
            flags = SKU_FLAG_AVAILABLE or SKU_FLAG_STICKER,
            externalSkuStrategies = null,
        )

        assertTrue(sku.isAvailable())
        assertTrue(sku.isStickerPack())
        assertEquals(ModelSku.SkuCategory.NITRO, sku.skuCategory)
        assertEquals(
            ModelSku.SkuCategory.NITRO_CLASSIC,
            sku.copy(id = PREMIUM_TIER_1_SKU_ID).skuCategory,
        )
        assertEquals(ModelSku.SkuCategory.GAME, sku.copy(id = 1).skuCategory)
        assertEquals(
            ModelSku.ExternalStrategyTypes.GOOGLE,
            ModelSku.ExternalSkuStrategy(3).strategyType,
        )
        assertEquals(ModelSku.ExternalStrategyTypes.UNKNOWN, ModelSku.ExternalStrategyTypes.from(99))
        assertEquals(9, fieldCount(ModelSku::class.java))
    }

    @Test
    fun `custom emoji renders content replacements and reaction keys`() {
        val custom = ModelEmojiCustom(
            id = 12,
            name = "party",
            roles = listOf(1L),
            requireColons = true,
            managed = false,
            disambiguationIndex = 0,
            nameDisambiguated = null,
            animated = true,
            usable = true,
            available = true,
            guildId = 7,
        )

        assertEquals("<a:party:12>", custom.getMessageContentReplacement())
        assertEquals("party:12", custom.getReactionKey())
        assertEquals("12", custom.getUniqueId())
        assertEquals(":party:", custom.getChatInputText())
        assertEquals(listOf("party"), custom.names)
        assertTrue(custom.isAvailable())
        assertTrue(custom.isUsable())
        assertTrue(custom.requiresColons())

        val disambiguated = ModelEmojiCustom(custom, 2, false)
        assertEquals("party-2", disambiguated.firstName)
        assertEquals("<a:party:12>", disambiguated.getMessageContentReplacement())
        assertFalse(disambiguated.isUsable())

        val api = custom.toApiEmoji()
        assertEquals(12L, api.id)
        assertEquals(listOf(1L), api.roles)
        assertTrue(api.animated)
    }

    @Test
    fun `guild emoji keeps every wire field`() {
        val emoji = gson.fromJson(
            """
            {
              "id": "12", "name": "party", "managed": false, "roles": ["1"],
              "required_colons": true, "animated": true, "available": true,
              "user": {"id": "3", "username": "uploader", "discriminator": "0001"}
            }
            """.trimIndent(),
            ModelEmojiGuild::class.java,
        )

        assertEquals(12L, emoji.id)
        assertEquals("party", emoji.name)
        assertEquals(listOf(1L), emoji.roles)
        assertTrue(emoji.requiredColons)
        assertEquals(3L, emoji.user.id)
        assertEquals(8, fieldCount(ModelEmojiGuild::class.java))
    }

    @Test
    fun `emoji categories map to and from their wire names`() {
        assertEquals(EmojiCategory.FLAGS, EmojiCategory.getByString("flags"))
        assertEquals(EmojiCategory.ACTIVITY, EmojiCategory.getByString("activity"))
        assertNull(EmojiCategory.getByString("nope"))
        assertFalse(EmojiCategory.CUSTOM.containsOnlyUnicode)
        assertTrue(EmojiCategory.PEOPLE.containsOnlyUnicode)
        assertEquals(11, EmojiCategory.entries.size)
    }

    @Test
    fun `spotify track keeps album images and artists`() {
        val track = gson.fromJson(
            """
            {
              "id": "t1", "name": "Track", "duration_ms": 1000, "is_local": false,
              "artists": [{"id": "a1", "name": "Artist"}],
              "album": {
                "album_type": "album", "id": "al1", "name": "Album",
                "images": [{"url": "https://example.invalid/cover.png"}]
              }
            }
            """.trimIndent(),
            ModelSpotifyTrack::class.java,
        )

        assertEquals(1000L, track.durationMs)
        assertEquals("Artist", track.artists.single().name)
        assertEquals("https://example.invalid/cover.png", track.album!!.images.single().url)
        assertEquals(6, fieldCount(ModelSpotifyTrack::class.java))
    }

    @Test
    fun `invoice preview keeps every total`() {
        val preview = gson.fromJson(
            """
            {
              "currency": "usd", "id": "1", "subtotal": 999, "tax": 100,
              "tax_inclusive": false, "total": 1099,
              "subscription_period_start": "2022-01-01T00:00:00+00:00",
              "subscription_period_end": "2022-02-01T00:00:00+00:00",
              "invoice_items": [
                {
                  "amount": 999, "id": "2", "proration": true, "quantity": 1,
                  "subscription_plan_id": "3", "subscription_plan_price": 999,
                  "discount": [{"amount": 100, "type": 1}]
                }
              ]
            }
            """.trimIndent(),
            ModelInvoicePreview::class.java,
        )

        assertEquals(1099, preview.total)
        assertTrue(preview.invoiceItems.single().proration)
        assertEquals(100, preview.invoiceItems.single().discount.single().amount)
        assertEquals(9, fieldCount(ModelInvoicePreview::class.java))
    }

    @Test
    fun `search response projects the flagged hit out of each group`() {
        val response = gson.fromJson(
            """
            {
              "total_results": 2,
              "messages": [
                [
                  {"id": "1", "channel_id": "9", "hit": false},
                  {"id": "2", "channel_id": "9", "hit": true}
                ],
                [
                  {"id": "3", "channel_id": "9", "hit": false}
                ]
              ],
              "retry_after": 3
            }
            """.trimIndent(),
            ModelSearchResponse::class.java,
        )

        assertEquals(2, response.totalResults)
        assertEquals(3, response.retryAfter)
        // `hits` is a `by lazy` delegate, so it observes the fields Gson injected after construction.
        assertEquals(listOf(2L), response.hits.map { it.id })
        // Deliberate divergence: every constructor parameter is defaulted, so Kotlin emits a no-arg
        // constructor that Gson uses, and the reference's stored `retryMillis` is computed from a
        // still-null `retryAfter` before the parsed fields are injected — leaving it permanently 0,
        // so `SearchFetcher` re-issues an indexing search after 0ms. Reproducing that would mean a
        // zero-delay retry loop against the live backend, which the project's safety rules forbid.
        assertEquals(3000L, response.retryMillis)
    }

    @Test
    fun `secrets are redacted from string rendering`() {
        assertFalse(ModelConnectionAccessToken("s3cret").toString().contains("s3cret"))
        assertFalse(ModelEmailChangeConfirm("s3cret").toString().contains("s3cret"))
        assertFalse(ModelRemoteAuthHandshake("s3cret").toString().contains("s3cret"))

        val connectionState = Model.JsonReader(
            java.io.StringReader("""{"code":"s3cret","state":"st4te","provider_id":"spotify"}"""),
        ).parse(ModelConnectionState())!!
        assertEquals("s3cret", connectionState.code)
        assertFalse(connectionState.toString().contains("s3cret"))
        assertFalse(connectionState.toString().contains("st4te"))
        assertTrue(connectionState.toString().contains("spotify"))

        val paymentSource = PaymentSourceRaw(
            type = 1,
            id = "src",
            invalid = false,
            billingAddress = com.discord.models.domain.billing.ModelBillingAddress(
                "Name", "Line 1", "", "City", "State", "US", "00000",
            ),
            default = true,
            email = "user@example.invalid",
            brand = "visa",
            last_4 = "4242",
            expiresMonth = 1,
            expiresYear = 2030,
        )
        assertFalse(paymentSource.toString().contains("user@example.invalid"))
        assertFalse(paymentSource.toString().contains("4242"))
    }
}

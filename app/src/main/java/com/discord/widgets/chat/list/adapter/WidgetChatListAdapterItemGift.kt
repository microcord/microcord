package com.discord.widgets.chat.list.adapter

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.i18n.i18nFormat
import com.discord.models.domain.ModelGift
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.time.TimeUtils
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.views.ThemedColor
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.GiftEntry
import com.discord.widgets.settings.account.WidgetSettingsAccount
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton
import rx.Observable
import rx.Subscription

/**
 * A `discord.gift` link, rendered as a claimable card.
 *
 * The row has three states. It starts **loading**, with a placeholder where the product name will
 * be. Resolving the code either produces the **resolved** card — product art, name, expiry countdown
 * and exactly one action button — or, if the code is bad, revoked or already fully used up, the
 * **invalid** card.
 *
 * Only one button in the container is ever visible; which one depends, in priority order, on whether
 * the viewer's email is verified, whether the gift is still claimable, and whether they already own
 * or claimed it.
 */
class WidgetChatListAdapterItemGift(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_gift, adapter) {

    private val imageBackground: SimpleDraweeView =
        itemView.findViewById(R.id.item_gift_image_background)
    private val header: TextView = itemView.findViewById(R.id.item_gift_header)
    private val image: SimpleDraweeView = itemView.findViewById(R.id.item_gift_image)
    private val name: TextView = itemView.findViewById(R.id.item_gift_name)
    private val expires: TextView = itemView.findViewById(R.id.item_gift_expires)
    private val subtext: TextView = itemView.findViewById(R.id.item_gift_subtext)
    private val buttons: ViewGroup = itemView.findViewById(R.id.buttonsContainer)
    private val acceptButton: MaterialButton = itemView.findViewById(R.id.item_gift_accept_button)
    private val cannotClaimButton: MaterialButton =
        itemView.findViewById(R.id.item_gift_cannot_claim_button)
    private val openButton: MaterialButton = itemView.findViewById(R.id.item_gift_open_button)
    private val verifyButton: MaterialButton = itemView.findViewById(R.id.item_gift_verify_button)
    private val loadingPlaceholder: View =
        itemView.findViewById(R.id.item_gift_loading_button_placeholder)

    private var modelSubscription: Subscription? = null
    override val subscription: Subscription? get() = modelSubscription

    /** Set while a redeem request is in flight, so the button reads "Claiming" rather than idle. */
    private var redeemingCode: String? = null

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as GiftEntry

        configureLoadingUI()
        observeGift(entry).ui().appSubscribe(
            errorClass = WidgetChatListAdapterItemGift::class.java,
            subscriptionHandler = { modelSubscription = it },
            errorHandler = { configureInvalidUI(entry) },
        ) { (gift, me) -> configureResolvedUI(entry, gift, me) }
    }

    /**
     * Resolves the code and pairs it with the viewer.
     *
     * The reference reads this from `StoreGifting`, which caches resolutions across rows and tracks
     * redeem progress. That store has not been reconstructed, so the row resolves the code itself
     * through the same endpoint; a message quoting the same gift twice therefore resolves it twice.
     */
    private fun observeGift(entry: GiftEntry): Observable<Pair<ModelGift, MeUser>> {
        val api = RestAPI.apiOrNull ?: return Observable.empty()
        return Observable.combineLatest(
            api.resolveGiftCode(entry.giftCode, withApplication = true, withSubscription = true)
                .restSubscribeOn(),
            StoreStream.getUsers().observeMe(),
        ) { gift, me -> gift to me }
    }

    private fun configureLoadingUI() {
        header.setText(R.string.gift_embed_resolving)
        name.text = null
        name.setTextColor(ThemedColor.resolve(name.context, R.attr.primary_100))
        name.setBackgroundResource(R.drawable.drawable_empty_text_placeholder_dark)
        image.setActualImageResource(R.drawable.drawable_empty_text_placeholder_dark)
        // INVISIBLE rather than GONE: the art sits behind the card and its slot must keep its size.
        imageBackground.visibility = View.INVISIBLE
        subtext.visibility = View.GONE
        expires.visibility = View.GONE
        showOnly(loadingPlaceholder)
    }

    private fun configureInvalidUI(entry: GiftEntry) {
        val context = itemView.context
        val fromMe = StoreStream.getUsers().getMe().id == entry.userId
        imageBackground.visibility = View.INVISIBLE
        header.setText(
            if (fromMe) R.string.gift_embed_invalid_title_self else R.string.gift_embed_invalid_title_other,
        )
        name.setText(R.string.gift_embed_invalid)
        name.setTextColor(ContextCompat.getColor(context, R.color.status_red_500))
        name.setBackgroundResource(0)
        image.setActualImageResource(context.themedDrawable(R.attr.img_poop, R.drawable.img_poop_dark))
        subtext.visibility = View.GONE
        expires.visibility = View.GONE
        showOnly(null)
        itemView.setOnClickListener(null)
    }

    private fun configureResolvedUI(entry: GiftEntry, gift: ModelGift, me: MeUser) {
        val context = itemView.context
        val sku = gift.storeListing?.sku
        val fromMe = gift.user?.id == me.id
        val claimedByMe = gift.isClaimedByMe()
        val usedUp = gift.uses == gift.maxUses
        val redeeming = redeemingCode == gift.code
        val canAccept = !usedUp && !redeeming && me.isVerified && !claimedByMe

        header.setText(if (fromMe) R.string.gift_embed_title_self else R.string.gift_embed_title)

        name.text = sku?.name
        name.setTextColor(ThemedColor.resolve(context, R.attr.primary_100))
        name.setBackgroundResource(0)

        configureArt(gift)

        expires.visibility = if (gift.expiresAt != null) View.VISIBLE else View.GONE
        if (gift.expiresAt != null) {
            val remaining = gift.getExpiresDiff(ClockFactory.get().currentTimeMillis())
            expires.text = context.i18nFormat(
                R.string.gift_embed_expiration,
                "hours" to TimeUtils.toFriendlyStringSimple(remaining),
            )
        }

        subtext.setTextAndVisibilityBy(
            when {
                !me.isVerified -> context.getString(R.string.gift_code_auth_help_text_verification_required)
                claimedByMe -> context.getString(R.string.gift_embed_body_claimed_self_mobile)
                usedUp -> context.getString(R.string.gift_code_auth_help_text_claimed)
                else -> null
            },
        )

        when {
            canAccept -> {
                // A gift with its own presentation style is "opened" rather than "accepted"; the
                // request behind both buttons is identical.
                val claimButton = if (gift.giftStyle != null) openButton else acceptButton
                showOnly(claimButton)
                claimButton.setOnClickListener { acceptGift(entry, gift) }
            }
            !me.isVerified -> {
                showOnly(verifyButton)
                verifyButton.isEnabled = true
                verifyButton.setOnClickListener { WidgetSettingsAccount.launch(context) }
            }
            else -> {
                showOnly(cannotClaimButton)
                cannotClaimButton.setText(
                    when {
                        redeeming -> R.string.gift_embed_button_claiming
                        claimedByMe -> R.string.gift_embed_button_claimed
                        else -> R.string.gift_embed_button_owned
                    },
                )
                cannotClaimButton.setOnClickListener(null)
            }
        }

        itemView.setOnClickListener {
            UriHandler.handle(context, discordStoreUrl(gift.skuId))
        }
    }

    /** Splash behind the card plus the product icon in front of it. */
    private fun configureArt(gift: ModelGift) {
        val sku = gift.storeListing?.sku
        val splashUrl = IconUtils.getGiftSplashUrl(
            applicationId = sku?.applicationId ?: 0L,
            hash = sku?.application?.splash,
            size = itemView.width.takeIf { it > 0 },
        )
        if (splashUrl != null) {
            imageBackground.visibility = View.VISIBLE
            MGImages.setImage(imageBackground, splashUrl)
        } else {
            imageBackground.visibility = View.GONE
        }

        val iconHash = sku?.application?.icon
        val iconUrl = iconHash?.let { IconUtils.getApplicationIcon(sku.applicationId, it) }
        if (iconUrl != null) {
            MGImages.setImage(image, iconUrl)
        } else {
            image.setActualImageResource(R.drawable.ic_file_unknown)
        }
    }

    /**
     * Redeems the code.
     *
     * The button flips to "Claiming" for the duration and settles on "Claimed" or, on failure, back
     * to an offer to try again — a redeem that fails must not leave the row spinning forever.
     */
    private fun acceptGift(entry: GiftEntry, gift: ModelGift) {
        val api = RestAPI.apiOrNull ?: return
        redeemingCode = gift.code
        cannotClaimButton.setText(R.string.gift_embed_button_claiming)
        showOnly(cannotClaimButton)
        api.acceptGift(gift.code)
            .restSubscribeOn()
            .ui()
            .appSubscribe(
                errorClass = WidgetChatListAdapterItemGift::class.java,
                context = itemView.context,
                errorHandler = {
                    redeemingCode = null
                    val claimButton = if (gift.giftStyle != null) openButton else acceptButton
                    showOnly(claimButton)
                    claimButton.setOnClickListener { acceptGift(entry, gift) }
                },
            ) {
                redeemingCode = null
                cannotClaimButton.setText(R.string.gift_embed_button_claimed)
                showOnly(cannotClaimButton)
            }
    }

    /** Reveals exactly one child of the button container and hides the rest. */
    private fun showOnly(visible: View?) {
        for (index in 0 until buttons.childCount) {
            val child = buttons.getChildAt(index)
            child.visibility = if (child === visible) View.VISIBLE else View.GONE
        }
    }

    private fun Context.themedDrawable(attribute: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (theme.resolveAttribute(attribute, value, true) && value.resourceId != 0) {
            value.resourceId
        } else {
            fallback
        }
    }

    private companion object {
        private fun discordStoreUrl(skuId: Long) = "https://discord.com/store/skus/$skuId"
    }
}

package com.discord.widgets.guild_role_subscriptions.tier

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.i18n.I18nHooks
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.widgets.guild_role_subscriptions.GuildRoleSubscriptionBenefitItemView
import com.discord.widgets.guild_role_subscriptions.tier.create.GuildRoleSubscriptionMemberPreview
import com.discord.widgets.stickers.StickerPackFormatting
import com.facebook.drawee.view.SimpleDraweeView

/** Renders one [GuildRoleSubscriptionTierAdapterItem]. */
sealed class GuildRoleSubscriptionTierViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    abstract fun bind(item: GuildRoleSubscriptionTierAdapterItem)

    /**
     * Shares [GuildRoleSubscriptionBenefitItemView] with the editor, stripped of the padding and
     * ripple background it carries there — inside this list the decoration supplies the spacing and
     * the row is not tappable.
     */
    class BenefitViewHolder(
        private val view: GuildRoleSubscriptionBenefitItemView,
    ) : GuildRoleSubscriptionTierViewHolder(view) {

        init {
            view.setPadding(0, 0, 0, 0)
            view.background = null
        }

        override fun bind(item: GuildRoleSubscriptionTierAdapterItem) {
            when (item) {
                is GuildRoleSubscriptionTierAdapterItem.BenefitItem -> view.configureUI(item.benefit)
                is GuildRoleSubscriptionTierAdapterItem.AllChannelsAccessBenefitItem ->
                    view.configureUI(
                        view.context.getString(R.string.guild_role_subscription_tier_review_entire_server_access_benefit),
                        R.drawable.ic_key_24dp,
                        null,
                    )
                else -> Unit
            }
        }
    }

    class MemberPreviewViewHolder(
        private val memberPreview: GuildRoleSubscriptionMemberPreview,
    ) : GuildRoleSubscriptionTierViewHolder(memberPreview) {

        init {
            memberPreview.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            memberPreview.setBackgroundColor(
                ColorCompat.getThemedColor(memberPreview, R.attr.colorBackgroundSecondary),
            )
        }

        override fun bind(item: GuildRoleSubscriptionTierAdapterItem) {
            val memberPreviewItem = item as? GuildRoleSubscriptionTierAdapterItem.MemberPreview ?: return
            memberPreview.setMemberDesign(memberPreviewItem.memberColor, memberPreviewItem.memberIcon)
        }
    }

    class PlanDetailsViewHolder(itemView: View) : GuildRoleSubscriptionTierViewHolder(itemView) {

        private val coverImage: SimpleDraweeView =
            requireNotNull(itemView.findViewById(R.id.guild_role_subscription_plan_review_cover_image))
        private val description: TextView =
            requireNotNull(itemView.findViewById(R.id.guild_role_subscription_plan_review_description))

        override fun bind(item: GuildRoleSubscriptionTierAdapterItem) {
            val planDetails = item as? GuildRoleSubscriptionTierAdapterItem.PlanDetails ?: return
            description.text = planDetails.description
            MGImages.setImage(coverImage, planDetails.coverImage)
        }
    }

    class SectionHeaderViewHolder(itemView: View) : GuildRoleSubscriptionTierViewHolder(itemView) {

        private val header: TextView =
            requireNotNull(itemView.findViewById(R.id.guild_role_subscription_section_header))

        init {
            itemView.setPadding(0, 0, 0, 0)
            itemView.background = null
        }

        override fun bind(item: GuildRoleSubscriptionTierAdapterItem) {
            val sectionHeader = item as? GuildRoleSubscriptionTierAdapterItem.SectionHeader ?: return
            header.text = when {
                sectionHeader.titleResId != null -> itemView.context.getString(sectionHeader.titleResId)
                sectionHeader.titlePluralResId != null && sectionHeader.formatArgument != null ->
                    I18nHooks.plural(
                        itemView.context,
                        sectionHeader.titlePluralResId,
                        sectionHeader.formatArgument,
                    )
                else -> null
            }
        }
    }

    class TierHeaderViewHolder(itemView: View) : GuildRoleSubscriptionTierViewHolder(itemView) {

        private val name: TextView =
            requireNotNull(itemView.findViewById(R.id.guild_role_subscription_tier_header_name))
        private val description: TextView =
            requireNotNull(itemView.findViewById(R.id.guild_role_subscription_tier_header_description))
        private val price: TextView =
            requireNotNull(itemView.findViewById(R.id.guild_role_subscription_tier_header_price))
        private val image: SimpleDraweeView =
            requireNotNull(itemView.findViewById(R.id.guild_role_subscription_tier_header_image))

        override fun bind(item: GuildRoleSubscriptionTierAdapterItem) {
            val header = item as? GuildRoleSubscriptionTierAdapterItem.Header ?: return
            name.text = header.name
            description.text = header.description
            // A tier with no price yet hides the field rather than showing "$0.00".
            price.setTextAndVisibilityBy(
                header.price?.let { StickerPackFormatting.formattedPriceUsd(it, itemView.context) },
            )
            IconUtils.setIcon(image, header.image, R.dimen.avatar_size_xxlarge)
        }
    }
}

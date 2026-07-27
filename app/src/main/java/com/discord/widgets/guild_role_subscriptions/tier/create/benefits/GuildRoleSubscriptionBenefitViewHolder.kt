package com.discord.widgets.guild_role_subscriptions.tier.create.benefits

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.guildrolesubscription.GuildRoleSubscriptionBenefitType
import com.discord.views.CheckedSetting
import com.discord.widgets.guild_role_subscriptions.GuildRoleSubscriptionBenefitItemView
import com.discord.widgets.guild_role_subscriptions.getFormattedLabel

sealed class GuildRoleSubscriptionBenefitViewHolder(itemView: View) :
    RecyclerView.ViewHolder(itemView) {

    abstract fun configure(benefitAdapterItem: GuildRoleSubscriptionBenefitAdapterItem)

    class AddItemViewHolder(
        itemView: View,
        private val listener: GuildRoleSubscriptionBenefitAdapter.Listener,
    ) : GuildRoleSubscriptionBenefitViewHolder(itemView) {

        private val text: TextView = requireNotNull(
            itemView.findViewById(R.id.guild_role_subscription_add_benefit_text),
        )

        override fun configure(benefitAdapterItem: GuildRoleSubscriptionBenefitAdapterItem) {
            val item = benefitAdapterItem as GuildRoleSubscriptionBenefitAdapterItem.AddBenefitItem
            itemView.setOnClickListener { listener.onAddBenefitItemClick(item) }
            val label = itemView.context.getString(
                if (item.type == GuildRoleSubscriptionBenefitType.CHANNEL) {
                    R.string.guild_role_subscription_tier_benefits_add_channel_label
                } else {
                    R.string.guild_role_subscription_tier_benefits_add_benefit_label
                },
            )
            text.text = label
            text.contentDescription = label
        }
    }

    class FreeTrialViewHolder(
        itemView: View,
        private val listener: GuildRoleSubscriptionBenefitAdapter.Listener,
    ) : GuildRoleSubscriptionBenefitViewHolder(itemView) {

        private val toggle: CheckedSetting = requireNotNull(
            itemView.findViewById(R.id.guild_role_subscription_free_trial_enable_toggle),
        )
        private val subtitle: TextView = requireNotNull(
            itemView.findViewById(R.id.guild_role_subscription_free_trial_subtitle),
        )
        private val activeLimitTitle: TextView = requireNotNull(
            itemView.findViewById(R.id.guild_role_subscription_active_user_limit_title),
        )
        private val activeLimitDescription: TextView = requireNotNull(
            itemView.findViewById(R.id.guild_role_subscription_active_user_limit_description),
        )
        private val activeLimitDropdown: TextView = requireNotNull(
            itemView.findViewById(R.id.guild_role_subscription_active_user_limit_dropdown),
        )
        private val timeLimitTitle: TextView = requireNotNull(
            itemView.findViewById(R.id.guild_role_subscription_time_limit_title),
        )
        private val timeLimitDescription: TextView = requireNotNull(
            itemView.findViewById(R.id.guild_role_subscription_time_limit_description),
        )
        private val timeLimitDropdown: TextView = requireNotNull(
            itemView.findViewById(R.id.guild_role_subscription_time_limit_dropdown),
        )

        override fun configure(benefitAdapterItem: GuildRoleSubscriptionBenefitAdapterItem) {
            val item = benefitAdapterItem as GuildRoleSubscriptionBenefitAdapterItem.FreeTrialItem
            toggle.setChecked(item.isFreeTrialEnabled)
            toggle.setOnCheckedListener { listener.onFreeTrialToggle(it) }

            listOf(
                subtitle,
                activeLimitTitle,
                activeLimitDescription,
                activeLimitDropdown,
                timeLimitTitle,
                timeLimitDescription,
                timeLimitDropdown,
            ).forEach { it.isEnabled = item.isFreeTrialEnabled }

            activeLimitDropdown.isSelected = item.isFreeTrialEnabled
            activeLimitDropdown.text = item.activeTrialUserLimit?.toString()
                ?: itemView.context.getText(
                    R.string.guild_role_subscription_setup_tier_active_trial_user_limit_none_option,
                )
            activeLimitDropdown.setOnClickListener(
                if (item.isFreeTrialEnabled) View.OnClickListener {
                    listener.onActiveTrialUserLimitClick()
                } else null,
            )

            timeLimitDropdown.isSelected = item.isFreeTrialEnabled
            timeLimitDropdown.text = item.trialInterval?.getFormattedLabel(itemView.context)
                ?: itemView.context.getText(R.string.guild_role_subscription_tier_detail_price_placeholder)
            timeLimitDropdown.setOnClickListener(
                if (item.isFreeTrialEnabled) View.OnClickListener {
                    listener.onTrialIntervalClick()
                } else null,
            )
        }
    }

    class HeaderViewHolder(itemView: View) : GuildRoleSubscriptionBenefitViewHolder(itemView) {
        private val header: TextView = requireNotNull(
            itemView.findViewById(R.id.guild_role_subscription_section_header),
        )

        override fun configure(benefitAdapterItem: GuildRoleSubscriptionBenefitAdapterItem) {
            val item = benefitAdapterItem as GuildRoleSubscriptionBenefitAdapterItem.Header
            header.setText(
                if (item.type == GuildRoleSubscriptionBenefitType.CHANNEL) {
                    R.string.guild_role_subscription_setup_channel_benefits_channels_title
                } else {
                    R.string.guild_role_subscription_tier_additional_benefits_section_title
                },
            )
        }
    }

    class ItemViewHolder(
        private val view: GuildRoleSubscriptionBenefitItemView,
        private val listener: GuildRoleSubscriptionBenefitAdapter.Listener,
    ) : GuildRoleSubscriptionBenefitViewHolder(view) {

        override fun configure(benefitAdapterItem: GuildRoleSubscriptionBenefitAdapterItem) {
            val item = benefitAdapterItem as GuildRoleSubscriptionBenefitAdapterItem.BenefitItem
            itemView.setOnClickListener { listener.onBenefitItemClick(item) }
            view.configureUI(item.benefit)
        }
    }
}

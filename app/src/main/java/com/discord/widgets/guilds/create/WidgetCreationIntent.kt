package com.discord.widgets.guilds.create

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.i18n.setI18nText
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.widgets.nux.GuildTemplateAnalytics
import com.google.android.material.card.MaterialCardView

class WidgetCreationIntent : AppFragment(R.layout.widget_creation_intent) {

    private val args: CreationIntentArgs by lazy {
        requireNotNull(
            arguments?.let {
                BundleCompat.getParcelable(it, INTENT_ARGS_KEY, CreationIntentArgs::class.java)
            },
        ) { "Missing creation intent arguments" }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()

        val first = view.findViewById<MaterialCardView>(R.id.creation_intent_first_option)
        val firstText = view.findViewById<TextView>(R.id.creation_intent_first_option_text)
        val second = view.findViewById<MaterialCardView>(R.id.creation_intent_second_option)
        val secondText = view.findViewById<TextView>(R.id.creation_intent_second_option_text)
        if (IS_FRIENDS_FIRST) {
            configureFriends(first, firstText)
            configureCommunity(second, secondText)
        } else {
            configureCommunity(first, firstText)
            configureFriends(second, secondText)
        }

        view.findViewById<LinkifiedTextView>(R.id.creation_intent_skip_text).apply {
            setI18nText(R.string.creation_intent_skip)
            setOnClickListener { select(null) }
        }
    }

    private fun configureFriends(container: View, text: TextView) {
        text.setText(R.string.creation_intent_option_friends)
        text.setCompoundDrawablesWithIntrinsicBounds(
            ContextCompat.getDrawable(requireContext(), R.drawable.drawable_guild_template_community),
            null,
            ContextCompat.getDrawable(requireContext(), R.drawable.icon_carrot),
            null,
        )
        container.setOnClickListener { select(false) }
    }

    private fun configureCommunity(container: View, text: TextView) {
        text.setText(R.string.creation_intent_option_community)
        text.setCompoundDrawablesWithIntrinsicBounds(
            ContextCompat.getDrawable(requireContext(), R.drawable.drawable_guild_template_creator),
            null,
            ContextCompat.getDrawable(requireContext(), R.drawable.icon_carrot),
            null,
        )
        container.setOnClickListener { select(true) }
    }

    private fun select(isCommunity: Boolean?) {
        if (args.trigger == CreateGuildTrigger.NUF) {
            GuildTemplateAnalytics.postRegistrationTransition(
                GuildTemplateAnalytics.STEP_CREATION_INTENT,
                GuildTemplateAnalytics.STEP_GUILD_CREATE,
            )
        }
        WidgetGuildCreate.showFragment(this, args.createGuildOptions)
    }

    companion object {
        private const val INTENT_ARGS_KEY = "intent_args_key"
        private val IS_FRIENDS_FIRST = Math.random() < 0.5

        fun show(
            fragment: AppFragment,
            trigger: CreateGuildTrigger,
            createGuildOptions: WidgetGuildCreate.Options,
        ) {
            AppScreenRouter.replaceFragment(
                fragment.parentFragmentManager,
                fragment.requireContext(),
                WidgetCreationIntent::class.java,
                R.id.app_root,
                addToBackStack = true,
                arguments = Bundle().apply {
                    putParcelable(INTENT_ARGS_KEY, CreationIntentArgs(trigger, createGuildOptions))
                },
            )
        }
    }
}

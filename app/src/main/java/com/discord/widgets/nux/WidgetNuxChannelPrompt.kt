package com.discord.widgets.nux

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.app.AppScreenRouter
import com.discord.i18n.I18nSpans
import com.discord.models.guild.Guild
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.error.RestApiError
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.takeSingleUntilTimeout
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.extensions.setOnImeActionDone
import com.discord.utilities.view.validators.Input
import com.discord.utilities.view.validators.ValidationManager
import com.discord.views.LoadingButton
import com.discord.widgets.guilds.invite.WidgetGuildInviteShare
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.textfield.TextInputLayout

/**
 * "What is your server about?" — the last step of guild creation.
 *
 * The user names one topic; the app creates a text channel with that name and a pre-written topic,
 * then hands off to the invite-share flow so the empty guild gets its first members. Skipping does
 * the same hand-off without creating anything, which is why both paths end in [finishActivity].
 */
class WidgetNuxChannelPrompt : AppFragment(R.layout.widget_nux_channel_prompt) {

    private lateinit var guildIcon: SimpleDraweeView
    private lateinit var guildIconName: TextView
    private lateinit var guildName: TextView
    private lateinit var topicInput: TextInputLayout
    private lateinit var ctaButton: LoadingButton

    /**
     * The server rejects a channel name under the `name` key, which is the field this form has.
     * Built lazily because it captures a view.
     */
    private val validationManager: ValidationManager by lazy {
        ValidationManager(Input.TextInputLayoutInput(FIELD_NAME, topicInput))
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val guildId = mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, 0L)

        guildIcon = view.findViewById(R.id.nuf_channel_prompt_guild_icon)
        guildIconName = view.findViewById(R.id.nuf_channel_prompt_guild_icon_name)
        guildName = view.findViewById(R.id.nuf_channel_prompt_guild_name)
        topicInput = view.findViewById(R.id.nuf_channel_prompt_topic_wrap)
        ctaButton = view.findViewById(R.id.nuf_channel_prompt_cta_button)

        view.findViewById<TextView>(R.id.nuf_channel_prompt_skip_button).setOnClickListener {
            finishActivity(guildId)
        }
        // Back is the same as Skip, and is consumed so it does not walk back into guild creation.
        setOnBackPressed({
            finishActivity(guildId)
            true
        })

        ctaButton.setIsLoading(false)
        ctaButton.setOnClickListener { handleSubmit(guildId) }
        topicInput.setOnImeActionDone(isEventConsumed = true) { handleSubmit(guildId) }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        StoreStream.getGuilds().observeGuild(mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, 0L))
            .ui(this)
            .appSubscribe(WidgetNuxChannelPrompt::class.java, context) { handleGuild(it) }
    }

    /**
     * A guild with no icon shows its initials instead; the two views are mutually exclusive rather
     * than layered, so both are set on every emission.
     */
    private fun handleGuild(guild: Guild?) {
        if (guild == null) return
        val iconUrl = IconUtils.getForGuild(
            guildId = guild.id,
            icon = guild.icon,
            allowAnimation = true,
            size = IconUtils.getMediaProxySize(resources.getDimensionPixelSize(R.dimen.avatar_size_large)),
        )
        if (iconUrl != null) {
            guildIconName.visibility = View.GONE
            IconUtils.setIcon(guildIcon, iconUrl)
        } else {
            guildIconName.visibility = View.VISIBLE
            guildIconName.text = guild.shortName
            IconUtils.setIcon(guildIcon, IconUtils.DEFAULT_ICON)
        }
        guildName.text = guild.name
    }

    /**
     * Creates the channel, then leaves.
     *
     * The default channel is fetched first only to obtain the category the new channel should sit
     * under; a guild with no default channel yet simply stops the spinner, matching the reference.
     */
    private fun handleSubmit(guildId: Long) {
        ctaButton.setIsLoading(true)
        StoreStream.getChannels().observeDefaultChannel(guildId)
            .takeSingleUntilTimeout()
            // The reference subscribes on the emitting thread and touches views from it; this
            // reconstruction hops to the main thread first, which is the only difference.
            .ui(this)
            .appSubscribe(WidgetNuxChannelPrompt::class.java, context) { defaultChannel ->
                createChannel(guildId, defaultChannel)
            }
    }

    private fun createChannel(guildId: Long, defaultChannel: Channel?) {
        if (defaultChannel == null) {
            ctaButton.setIsLoading(false)
            return
        }
        val api = RestAPI.apiOrNull
        if (api == null) {
            AppLog.w("Cannot create a channel before the REST client is published.")
            ctaButton.setIsLoading(false)
            return
        }
        val name = topicInput.getTextOrEmpty()
        val topic = I18nSpans
            .render(getString(R.string.nuf_channel_prompt_channel_topic_template), "topic" to name)
            .toString()
        api.createGuildChannel(
            guildId,
            RestAPIParams.CreateGuildChannel(
                type = Channel.GUILD_TEXT,
                id = null,
                name = name,
                parentId = defaultChannel.parentId,
                permissionOverwrites = null,
                topic = topic,
            ),
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetNuxChannelPrompt::class.java,
                context = context,
                errorHandler = ::handleError,
            ) { finishActivity(guildId) }
    }

    /**
     * Field-level failures are rendered on the input; anything else falls back to a toast.
     *
     * `showErrorToasts` is only left on when nothing could be attached to a field, so the user never
     * sees the same message twice.
     */
    private fun handleError(error: RestApiError) {
        ctaButton.setIsLoading(false)
        if (error.messages.isEmpty()) {
            error.showErrorToasts = true
            return
        }
        error.showErrorToasts = validationManager.setErrors(error.messages).isNotEmpty()
    }

    private fun finishActivity(guildId: Long) {
        WidgetGuildInviteShare.launch(
            context = requireContext(),
            fragmentManager = parentFragmentManager,
            guildId = guildId,
            isNuxFlow = true,
            source = GUILD_CREATE_SOURCE,
        )
        requireActivity().finish()
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val FIELD_NAME = "name"
        private const val GUILD_CREATE_SOURCE = "Guild Create"

        fun launch(context: Context, guildId: Long) {
            AppScreenRouter.launch(
                context,
                WidgetNuxChannelPrompt::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }
    }
}

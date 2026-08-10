package com.discord.widgets.guilds.onboarding

import android.content.Context
import android.content.Intent
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.animation.AnimationUtils
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.drawToBitmap
import com.discord.R
import com.discord.databinding.WidgetGuildOnboardingBinding
import com.discord.databinding.WidgetGuildOnboardingOptionBinding
import com.discord.i18n.Hook
import com.discord.i18n.I18nHooks
import com.discord.api.guild.GuildOnboardingConnection
import com.discord.api.guild.GuildOnboardingPromptOption
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppTransitionActivity
import com.discord.stores.StoreGuildOnboarding
import com.discord.stores.StoreGuildMemberVerificationForm
import com.discord.stores.StoreStream
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.views.LoadingButton
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.widgets.settings.AuthorizedApps
import com.discord.widgets.servers.member_verification.WidgetMemberVerification

class WidgetGuildOnboarding : AppFragment(R.layout.widget_guild_onboarding) {
    private val guildId: Long get() = mostRecentIntent.getLongExtra(EXTRA_GUILD_ID, 0)
    private val previousGuildId: Long get() = mostRecentIntent.getLongExtra(EXTRA_PREVIOUS_GUILD_ID, 0)
    private val store get() = StoreStream.getGuildOnboarding()
    private var _binding: WidgetGuildOnboardingBinding? = null
    private val binding get() = checkNotNull(_binding)
    private lateinit var adapter: Adapter
    private var promptIndex = COVER
    private var authorizedApplicationIds = emptySet<Long>()
    private var landingScheduled = false
    private val revealPrompts = Runnable {
        if (isAdded && _binding != null && promptIndex == COVER) animateLandingOut()
    }
    private var verificationData: StoreGuildMemberVerificationForm.MemberVerificationFormData? = null
    private var pendingSubmit = false
    private var showRulesAfterSubmit = false
    private var completionHandled = false
    private var transitioning = false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetGuildOnboardingBinding.bind(view)
        binding.guildOnboardingClose.setOnClickListener { closeOnboarding() }
        binding.guildOnboardingNext.setIsLoading(false)
        bindPrivacyPolicy()
        adapter = Adapter(
            { promptId, option, selected -> store.selectOption(guildId, promptId, option.id, selected) },
            ::connect,
        )
        binding.guildOnboardingOptions.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@WidgetGuildOnboarding.adapter
        }
        binding.guildOnboardingBack.setOnClickListener { previous() }
        binding.guildOnboardingNext.setOnClickListener { advance() }
        setActionBarTitle(R.string.guild_onboarding_customize_title)
        setOnBackPressed({ closeOnboarding(); true })
        StoreStream.getGuildRoleMemberCounts().fetchGuildRoleMemberCounts(guildId)
        StoreStream.getGuildMemberVerificationForm().fetchMemberVerificationForm(guildId)
        store.begin(guildId)
        store.fetch(guildId)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        store.observe(guildId).ui(this).bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildOnboarding::class.java, onNext = ::render)
        store.observe(guildId)
            .map { state -> state.onboarding?.connections.orEmpty().mapNotNull { it.applicationId } }
            .distinctUntilChanged()
            .switchMap(StoreStream.getApplication()::observeApplications)
            .ui(this)
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildOnboarding::class.java) { render(store.get(guildId)) }
        StoreStream.getUserConnections().observeConnectedAccounts().ui(this).bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildOnboarding::class.java) { render(store.get(guildId)) }
        StoreStream.getUserSettings().observeIsAnimatedEmojisEnabled().ui(this).bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildOnboarding::class.java) { adapter.allowAnimatedEmojis = it }
        StoreStream.getGuildRoleMemberCounts().observeGuildRoleMemberCounts(guildId).ui(this)
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildOnboarding::class.java) { adapter.roleMemberCounts = it.orEmpty() }
        StoreStream.getGuildMemberVerificationForm().observeMemberVerificationFormData(guildId).ui(this)
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildOnboarding::class.java) { data ->
                verificationData = data
                if (pendingSubmit && data?.fetchState != StoreGuildMemberVerificationForm.FetchStates.FETCHING) {
                    pendingSubmit = false
                    submit(store.get(guildId))
                }
            }
        AuthorizedApps.cached()?.let(::updateAuthorizedApps)
        AuthorizedApps.fetch().restSubscribeOn(false).ui(this).bindToComponentLifecycle(this)
            .appSubscribe(
                errorClass = WidgetGuildOnboarding::class.java,
                context = context,
                errorHandler = { it.showErrorToasts = false },
                onNext = ::updateAuthorizedApps,
            )
    }

    private fun render(state: StoreGuildOnboarding.State) {
        binding.guildOnboardingLoading.visibility = if (state.loading && state.onboarding == null) View.VISIBLE else View.GONE
        binding.guildOnboardingPageHost.visibility = if (state.onboarding == null) View.GONE else View.VISIBLE
        binding.guildOnboardingContent.visibility = if (state.onboarding == null) View.GONE else View.VISIBLE
        binding.guildOnboardingNext.setIsLoading(state.submitting)
        if (state.completed) {
            if (completionHandled) return
            completionHandled = true
            navigateToOnboardedGuild(state) {
                if (showRulesAfterSubmit) {
                    requireContext().startActivity(
                        WidgetMemberVerification.createIntent(requireContext(), guildId, "Guild Onboarding"),
                    )
                }
                requireActivity().finish()
            }
            return
        }
        val onboarding = state.onboarding
        if (onboarding == null) {
            if (state.error) {
                binding.guildOnboardingLoading.visibility = View.GONE
                binding.guildOnboardingContent.visibility = View.VISIBLE
                binding.guildOnboardingStep.text = ""
                binding.guildOnboardingTitle.setText(R.string.guild_onboarding_load_failed)
                binding.guildOnboardingSubtitle.text = ""
                adapter.submitList(emptyList())
                binding.guildOnboardingError.visibility = View.GONE
                binding.guildOnboardingBack.visibility = View.GONE
                binding.guildOnboardingNext.setText(getString(R.string.retry))
                binding.guildOnboardingNext.isEnabled = true
                binding.guildOnboardingLandingAnimation.visibility = View.GONE
                binding.guildOnboardingLandingWelcome.visibility = View.GONE
                binding.guildOnboardingLandingTitle.visibility = View.GONE
                binding.guildOnboardingPrivacy.visibility = View.GONE
                binding.guildOnboardingSplash.visibility = View.GONE
                binding.guildOnboardingSplashGradient.visibility = View.GONE
                binding.guildOnboardingActions.visibility = View.VISIBLE
            }
            return
        }
        val prompts = state.prompts
        val connections = onboarding.connections.orEmpty()
        connections.mapNotNull { it.applicationId }.forEach(StoreStream.getApplication()::fetchIfNonexisting)
        binding.guildOnboardingError.visibility = if (state.error) View.VISIBLE else View.GONE
        if (state.error) binding.guildOnboardingError.setText(R.string.guild_onboarding_submit_failed)

        when {
            promptIndex == COVER -> {
                binding.guildOnboardingContent.gravity = Gravity.NO_GRAVITY
                binding.guildOnboardingOptions.visibility = View.GONE
                binding.guildOnboardingActions.visibility = View.GONE
                binding.guildOnboardingLandingAnimation.visibility = View.VISIBLE
                binding.guildOnboardingLandingWelcome.visibility = View.VISIBLE
                binding.guildOnboardingLandingTitle.visibility = View.VISIBLE
                binding.guildOnboardingPrivacy.visibility = View.VISIBLE
                val guild = StoreStream.getGuilds().getGuild(guildId)
                val splashUrl = IconUtils.getGuildSplashUrl(guild, resources.displayMetrics.widthPixels)
                binding.guildOnboardingSplash.visibility = if (splashUrl == null) View.GONE else View.VISIBLE
                binding.guildOnboardingSplashGradient.visibility = binding.guildOnboardingSplash.visibility
                binding.guildOnboardingSplash.alpha = 1f
                binding.guildOnboardingSplashGradient.alpha = 1f
                binding.guildOnboardingSplash.scaleX = SPLASH_SCALE
                binding.guildOnboardingSplash.scaleY = SPLASH_SCALE
                binding.guildOnboardingSplash.translationY = SPLASH_OFFSET_DP * resources.displayMetrics.density
                binding.guildOnboardingSplashGradient.translationY = 0f
                splashUrl?.let { MGImages.setImage(binding.guildOnboardingSplash, it, autoPlayAnimations = false) }
                if (StoreStream.getAccessibility().isReducedMotionEnabled()) {
                    binding.guildOnboardingLandingAnimation.pauseAnimation()
                    binding.guildOnboardingLandingAnimation.progress = 0f
                } else if (!binding.guildOnboardingLandingAnimation.isAnimating) {
                    binding.guildOnboardingLandingAnimation.playAnimation()
                }
                binding.guildOnboardingStep.visibility = View.GONE
                binding.guildOnboardingTitle.visibility = View.GONE
                binding.guildOnboardingSubtitle.visibility = View.GONE
                binding.guildOnboardingLandingWelcome.text = getString(
                    R.string.guild_onboarding_welcome,
                    StoreStream.getGuilds().getGuild(guildId)?.name.orEmpty(),
                )
                binding.guildOnboardingSelectionSummary.visibility = View.GONE
                adapter.submitList(emptyList())
                binding.guildOnboardingBack.visibility = View.GONE
                if (!landingScheduled) {
                    landingScheduled = true
                    binding.guildOnboardingContent.postDelayed(revealPrompts, LANDING_DURATION_MS)
                }
            }
            promptIndex in prompts.indices -> {
                showQuestionChrome()
                val prompt = prompts[promptIndex]
                binding.guildOnboardingStep.text = getString(R.string.guild_onboarding_question_count, promptIndex + 1, prompts.size)
                binding.guildOnboardingTitle.text = prompt.title
                binding.guildOnboardingSubtitle.text = if (prompt.required) getString(R.string.required) else ""
                adapter.submitList(prompt.options.map { Row.Option(prompt.id, it, it.id in state.selectedOptionIds) })
                bindSelectionSummary(prompt.options.filter { it.id in state.selectedOptionIds })
                binding.guildOnboardingNext.setText(getString(if (promptIndex == prompts.lastIndex) R.string.guild_onboarding_finish else R.string.guild_onboarding_next))
                binding.guildOnboardingBack.visibility = if (promptIndex == 0) View.GONE else View.VISIBLE
                val missing = prompt.required && prompt.options.none { it.id in state.selectedOptionIds }
                binding.guildOnboardingNext.isEnabled = !missing && !state.submitting
                binding.guildOnboardingError.visibility = View.GONE
            }
            promptIndex == CONNECTIONS && connections.isNotEmpty() -> {
                showQuestionChrome()
                binding.guildOnboardingSelectionSummary.visibility = View.GONE
                binding.guildOnboardingStep.text = ""
                binding.guildOnboardingTitle.setText(R.string.guild_onboarding_connections_title)
                binding.guildOnboardingSubtitle.setText(R.string.guild_onboarding_connections_subtitle)
                val connectedProviders = StoreStream.getUserConnections().getConnectedAccounts().mapTo(hashSetOf()) { it.type }
                adapter.submitList(connections.map { connection ->
                    Row.Connection(
                        connection,
                        connection.applicationId
                            ?.let(StoreStream.getApplication()::getApplication)
                            ?.name
                            ?: connection.providerId
                            ?: connection.applicationId?.toString().orEmpty(),
                        connection.providerId?.let(connectedProviders::contains) == true ||
                            connection.applicationId in authorizedApplicationIds,
                    )
                })
                val anyConnected = connections.any {
                    (it.connectionType == GuildOnboardingConnection.PROVIDER_CONNECTED_ACCOUNT && it.providerId in connectedProviders) ||
                        it.applicationId in authorizedApplicationIds
                }
                binding.guildOnboardingNext.setText(getString(if (anyConnected) R.string.guild_onboarding_next else R.string.skip))
                binding.guildOnboardingNext.isEnabled = true
                binding.guildOnboardingBack.visibility = View.VISIBLE
            }
            else -> if (state.error) {
                binding.guildOnboardingLoading.visibility = View.GONE
                binding.guildOnboardingContent.visibility = View.VISIBLE
                binding.guildOnboardingActions.visibility = View.VISIBLE
                binding.guildOnboardingError.visibility = View.VISIBLE
                binding.guildOnboardingError.setText(R.string.guild_onboarding_submit_failed)
                binding.guildOnboardingNext.setIsLoading(false)
                binding.guildOnboardingNext.setText(getString(R.string.retry))
                binding.guildOnboardingNext.isEnabled = true
            } else {
                submit(state)
            }
        }
    }

    private fun showQuestionChrome() {
        binding.guildOnboardingContent.gravity = Gravity.NO_GRAVITY
        binding.guildOnboardingOptions.visibility = View.VISIBLE
        binding.guildOnboardingStep.visibility = View.VISIBLE
        binding.guildOnboardingTitle.visibility = View.VISIBLE
        binding.guildOnboardingSubtitle.visibility = View.VISIBLE
        binding.guildOnboardingLandingWelcome.visibility = View.GONE
        binding.guildOnboardingLandingTitle.visibility = View.GONE
        binding.guildOnboardingPrivacy.visibility = View.GONE
        binding.guildOnboardingTitle.gravity = Gravity.START
        binding.guildOnboardingSubtitle.gravity = Gravity.START
        binding.guildOnboardingActions.visibility = View.VISIBLE
        binding.guildOnboardingLandingAnimation.visibility = View.GONE
        binding.guildOnboardingSplash.visibility = View.GONE
        binding.guildOnboardingSplashGradient.visibility = View.GONE
    }

    private fun advance() {
        if (transitioning) return
        val state = store.get(guildId)
        if (state.onboarding == null) return store.fetch(guildId, force = true)
        val prompts = state.prompts
        if (state.error && promptIndex >= prompts.size) {
            submit(state)
            return
        }
        if (promptIndex in prompts.indices && prompts[promptIndex].required &&
            prompts[promptIndex].options.none { it.id in state.selectedOptionIds }
        ) {
            binding.guildOnboardingError.setText(R.string.guild_onboarding_required)
            binding.guildOnboardingError.visibility = View.VISIBLE
            return
        }
        if (promptIndex == prompts.lastIndex) {
            promptIndex = prompts.size
            submit(state)
            return
        }
        val wasCover = promptIndex == COVER
        val targetIndex = when {
            promptIndex == COVER -> if (state.onboarding.connections.orEmpty().isNotEmpty()) CONNECTIONS else 0
            promptIndex == CONNECTIONS -> 0
            else -> promptIndex + 1
        }
        if (!wasCover) {
            transitionTo(state, targetIndex, forward = true)
            return
        }
        promptIndex = targetIndex
        render(state)
        if (wasCover) {
            binding.guildOnboardingContent.animate().cancel()
            binding.guildOnboardingContent.alpha = 0f
            binding.guildOnboardingContent.translationY = 80f * resources.displayMetrics.density
            binding.guildOnboardingContent.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(PROMPT_ENTER_DELAY_MS)
                .setDuration(PROMPT_TRANSITION_MS)
                .start()
        }
    }

    private fun previous() {
        if (transitioning) return
        if (promptIndex == COVER || promptIndex == CONNECTIONS || promptIndex == 0) {
            closeOnboarding()
            return
        }
        transitionTo(store.get(guildId), promptIndex - 1, forward = false)
    }

    private fun connect(connection: GuildOnboardingConnection) {
        when (connection.connectionType) {
            GuildOnboardingConnection.PROVIDER_CONNECTED_ACCOUNT ->
                connection.providerId?.let { StoreStream.getUserConnections().authorizeConnection(it, requireContext()) }
            GuildOnboardingConnection.APPLICATION -> connection.applicationId
                ?.let(StoreStream.getApplication()::getApplication)
                ?.connectionEntrypointUrl
                ?.let { UriHandler.handle(requireContext(), it) }
        }
    }

    private fun updateAuthorizedApps(tokens: List<com.discord.models.domain.ModelOAuth2Token>) {
        authorizedApplicationIds = tokens.mapTo(hashSetOf()) { it.application.id }
        render(store.get(guildId))
    }

    private fun animateLandingOut() {
        if (!isAdded || _binding == null) return
        val distance = -80f * resources.displayMetrics.density
        binding.guildOnboardingSplash.animate().alpha(0f).translationY(distance).setDuration(LANDING_EXIT_MS).start()
        binding.guildOnboardingSplashGradient.animate().alpha(0f).translationY(distance).setDuration(LANDING_EXIT_MS).start()
        binding.guildOnboardingContent.animate()
            .alpha(0f)
            .translationY(distance)
            .setDuration(LANDING_EXIT_MS)
            .withEndAction { if (promptIndex == COVER) advance() }
            .start()
    }

    private fun bindPrivacyPolicy() {
        binding.guildOnboardingPrivacy.text = I18nHooks.format(
            requireContext(),
            R.string.guild_onboarding_privacy,
            hooks = mapOf("privacyPolicyHook" to Hook(onClick = { UriHandler.handle(it.context, PRIVACY_URL) })),
        )
        binding.guildOnboardingPrivacy.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun transitionTo(state: StoreGuildOnboarding.State, targetIndex: Int, forward: Boolean) {
        if (transitioning) return
        transitioning = true
        val host = binding.guildOnboardingPageHost
        val oldPage = ImageView(requireContext()).apply {
            setImageBitmap(binding.guildOnboardingContent.drawToBitmap())
            scaleType = ImageView.ScaleType.FIT_XY
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        host.inAnimation = null
        host.outAnimation = null
        host.addView(oldPage)
        host.displayedChild = 1
        promptIndex = targetIndex
        render(state)
        host.inAnimation = AnimationUtils.loadAnimation(
            requireContext(),
            if (forward) R.anim.activity_slide_horizontal_open_in else R.anim.activity_slide_horizontal_close_in,
        )
        host.outAnimation = AnimationUtils.loadAnimation(
            requireContext(),
            if (forward) R.anim.activity_slide_horizontal_open_out else R.anim.activity_slide_horizontal_close_out,
        )
        host.displayedChild = 0
        host.postDelayed({
            if (_binding == null) return@postDelayed
            host.removeView(oldPage)
            oldPage.setImageDrawable(null)
            transitioning = false
        }, resources.getInteger(R.integer.animation_time_standard).toLong())
    }

    private fun bindSelectionSummary(options: List<GuildOnboardingPromptOption>) {
        val roleIds = options.flatMapTo(linkedSetOf()) { it.roleIds }
        val channelIds = options.flatMapTo(linkedSetOf()) { it.channelIds }
        val roleNames = StoreStream.getGuilds().getRoles()[guildId].orEmpty().values
            .filter { it.id in roleIds }
            .map { "@${it.name}" }
        val channelNames = channelIds.mapNotNull(StoreStream.getChannels()::getChannel).map { "#${it.name}" }
        val lines = listOfNotNull(
            formatNames(roleNames)?.let { getString(R.string.guild_onboarding_receive_roles, it) },
            formatNames(channelNames)?.let { getString(R.string.guild_onboarding_receive_channels, it) },
        )
        binding.guildOnboardingSelectionSummary.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        binding.guildOnboardingSelectionSummary.text = lines.joinToString("\n")
    }

    private fun formatNames(names: List<String>): String? = names.takeIf(List<String>::isNotEmpty)?.let {
        if (it.size <= 2) it.joinToString(" and ")
        else "${it.take(2).joinToString(", ")}, and ${it.size - 2} more"
    }

    override fun onDestroyView() {
        binding.guildOnboardingContent.removeCallbacks(revealPrompts)
        binding.guildOnboardingLandingAnimation.cancelAnimation()
        _binding = null
        super.onDestroyView()
    }

    private fun submit(state: StoreGuildOnboarding.State) {
        if (state.submitting) return
        val data = verificationData
        if (data == null || data.fetchState == StoreGuildMemberVerificationForm.FetchStates.FETCHING) {
            pendingSubmit = true
            StoreStream.getGuildMemberVerificationForm().fetchMemberVerificationForm(guildId)
            return
        }
        showRulesAfterSubmit = !data.form?.formFields.isNullOrEmpty()
        binding.guildOnboardingContent.visibility = View.GONE
        binding.guildOnboardingActions.visibility = View.GONE
        binding.guildOnboardingPrivacy.visibility = View.GONE
        binding.guildOnboardingSplash.visibility = View.GONE
        binding.guildOnboardingSplashGradient.visibility = View.GONE
        binding.guildOnboardingLoading.visibility = View.VISIBLE
        store.submit(guildId)
    }

    private fun navigateToOnboardedGuild(state: StoreGuildOnboarding.State, onReady: () -> Unit) {
        val selected = StoreStream.getChannelsSelected().getSelectedChannel()
        val channelId = selected?.takeIf { it.guildId == guildId }?.id
            ?: state.onboarding?.defaultChannelIds?.firstOrNull()
        if (channelId == null) {
            StoreStream.selectGuild(guildId)
            binding.root.post(onReady)
            return
        }
        StoreStream.getChannelsSelected().observeResolvedSelectedChannel()
            .filter { resolved -> resolved.id == channelId || resolved.channelOrParent?.id == channelId }
            .take(1)
            .ui(this)
            .appSubscribe(WidgetGuildOnboarding::class.java) { onReady() }
        StoreStream.selectChannel(guildId, channelId)
    }

    private fun closeOnboarding() {
        StoreStream.selectGuild(previousGuildId)
        requireActivity().finish()
    }

    private sealed interface Row {
        data class Option(val promptId: Long, val option: GuildOnboardingPromptOption, val selected: Boolean) : Row
        data class Connection(
            val connection: GuildOnboardingConnection,
            val displayName: String,
            val connected: Boolean,
        ) : Row
    }

    private class Adapter(
        private val onOption: (Long, GuildOnboardingPromptOption, Boolean) -> Unit,
        private val onConnection: (GuildOnboardingConnection) -> Unit,
    ) : RecyclerView.Adapter<Adapter.Holder>() {
        private var rows = emptyList<Row>()
        var allowAnimatedEmojis = false
            set(value) {
                if (field == value) return
                field = value
                notifyDataSetChanged()
            }
        var roleMemberCounts: Map<Long, Int> = emptyMap()
            set(value) {
                if (field == value) return
                field = value
                notifyDataSetChanged()
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            WidgetGuildOnboardingOptionBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )
        override fun getItemCount() = rows.size
        private fun getItem(position: Int) = rows[position]
        fun submitList(value: List<Row>) {
            rows = value
            notifyDataSetChanged()
        }
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
        override fun onViewRecycled(holder: Holder) {
            holder.recycle()
            super.onViewRecycled(holder)
        }

        inner class Holder(
            private val binding: WidgetGuildOnboardingOptionBinding,
        ) : RecyclerView.ViewHolder(binding.root) {
            private val optionBinder = GuildOnboardingOptionBinder(binding)

            fun bind(row: Row) {
                when (row) {
                    is Row.Option -> {
                        val count = row.option.roleIds.maxOfOrNull { roleMemberCounts[it] ?: 0 } ?: 0
                        optionBinder.bind(
                            option = row.option,
                            selected = row.selected,
                            memberCount = count,
                            allowAnimatedEmoji = allowAnimatedEmojis,
                            onClick = { onOption(row.promptId, row.option, !row.selected) },
                        )
                    }
                    is Row.Connection -> {
                        recycle()
                        binding.guildOnboardingOptionTitle.text = row.displayName
                        binding.guildOnboardingOptionDescription.text = row.connection.description.orEmpty()
                        binding.guildOnboardingOptionDescription.visibility = if (row.connection.description.isNullOrBlank()) View.GONE else View.VISIBLE
                        binding.guildOnboardingOptionMemberCount.visibility = View.GONE
                        itemView.setBackgroundResource(
                            if (row.connected) R.drawable.bg_guild_onboarding_option_selected
                            else R.drawable.bg_guild_onboarding_option,
                        )
                        binding.guildOnboardingOptionChecked.visibility = if (row.connected) View.VISIBLE else View.GONE
                        itemView.setOnClickListener { if (!row.connected) onConnection(row.connection) }
                    }
                }
            }

            fun recycle() {
                optionBinder.recycle()
                binding.guildOnboardingOptionEmojiImage.visibility = View.GONE
                binding.guildOnboardingOptionEmojiText.text = ""
                binding.guildOnboardingOptionEmojiText.visibility = View.VISIBLE
            }
        }
    }

    companion object {
        private const val COVER = -2
        private const val CONNECTIONS = -1
        private const val LANDING_DURATION_MS = 2_000L
        private const val LANDING_EXIT_MS = 180L
        private const val PROMPT_ENTER_DELAY_MS = 60L
        private const val PROMPT_TRANSITION_MS = 180L
        private const val PRIVACY_URL = "https://discord.com/privacy"
        private const val SPLASH_SCALE = 1.08f
        private const val SPLASH_OFFSET_DP = -28f
        private const val EXTRA_GUILD_ID = "guild_id"
        private const val EXTRA_PREVIOUS_GUILD_ID = "previous_guild_id"
        fun launch(context: Context, guildId: Long) = AppScreenRouter.launch(
            context,
            WidgetGuildOnboarding::class.java,
            Intent()
                .putExtra(EXTRA_GUILD_ID, guildId)
                .putExtra(
                    EXTRA_PREVIOUS_GUILD_ID,
                    StoreStream.getGuildSelected().getRecentlySelectedGuildIds().firstOrNull { it != guildId } ?: 0L,
                ),
            transition = AppTransitionActivity.Transition.TYPE_SLIDE_VERTICAL,
        )

    }
}

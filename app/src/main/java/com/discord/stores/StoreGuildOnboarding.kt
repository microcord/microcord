package com.discord.stores

import com.discord.api.guild.GuildOnboarding
import com.discord.api.guild.GuildOnboardingPrompt
import com.discord.api.guild.GuildOnboardingResponses
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable
import rx.subjects.BehaviorSubject

class StoreGuildOnboarding(
    private val dispatcher: Dispatcher,
) : Store() {
    data class State(
        val loading: Boolean = false,
        val submitting: Boolean = false,
        val onboarding: GuildOnboarding? = null,
        val selectedOptionIds: Set<Long> = emptySet(),
        val error: Boolean = false,
        val completed: Boolean = false,
    ) {
        val prompts: List<GuildOnboardingPrompt>
            get() = onboarding?.prompts.orEmpty().filter { it.inOnboarding && !it.disabled }.take(MAX_ONBOARDING_PROMPTS)

        val customizationPrompts: List<GuildOnboardingPrompt>
            get() = onboarding?.prompts.orEmpty().filterNot { it.disabled }

        fun missingRequiredPrompt(): GuildOnboardingPrompt? = prompts.firstOrNull { prompt ->
            prompt.required && prompt.options.none { it.id in selectedOptionIds }
        }

        private companion object {
            const val MAX_ONBOARDING_PROMPTS = 4
        }
    }

    private val states = mutableMapOf<Long, State>()
    private val subjects = mutableMapOf<Long, BehaviorSubject<State>>()

    fun observe(guildId: Long): Observable<State> = subject(guildId).distinctUntilChanged()
    fun get(guildId: Long): State = states[guildId] ?: State()

    fun begin(guildId: Long) = dispatcher.schedule {
        val current = get(guildId)
        publish(guildId, current.copy(submitting = false, error = false, completed = false))
    }

    fun fetch(guildId: Long, force: Boolean = false) = dispatcher.schedule {
        val current = get(guildId)
        if (current.loading || (!force && current.onboarding != null)) return@schedule
        publish(guildId, current.copy(loading = true, error = false))
        val service = RestAPI.apiOrNull
        if (service == null) {
            publish(guildId, current.copy(error = true))
            return@schedule
        }
        service.getGuildOnboarding(guildId).restSubscribeOn(false).appSubscribe(
            StoreGuildOnboarding::class.java,
            errorHandler = { dispatcher.schedule { publish(guildId, get(guildId).copy(loading = false, error = true)) } },
        ) { onboarding -> dispatcher.schedule { handleLoaded(guildId, onboarding) } }
    }

    fun selectOption(guildId: Long, promptId: Long, optionId: Long, selected: Boolean) = dispatcher.schedule {
        val current = get(guildId)
        val prompt = current.customizationPrompts.firstOrNull { it.id == promptId } ?: return@schedule
        val next = current.selectedOptionIds.toMutableSet()
        if (prompt.singleSelect && selected) next.removeAll(prompt.options.map { it.id }.toSet())
        if (selected) next += optionId else next -= optionId
        publish(guildId, current.copy(selectedOptionIds = next, error = false))
    }

    fun submit(guildId: Long) = dispatcher.schedule {
        val current = get(guildId)
        if (current.submitting || current.missingRequiredPrompt() != null) return@schedule
        val onboarding = current.onboarding ?: return@schedule
        val prompts = current.prompts
        val body = responsesBody(current, prompts)
        val service = RestAPI.apiOrNull
        if (service == null) {
            publish(guildId, current.copy(error = true))
            return@schedule
        }
        publish(guildId, current.copy(submitting = true, error = false))
        service.completeGuildOnboarding(guildId, body).restSubscribeOn(false).appSubscribe(
            StoreGuildOnboarding::class.java,
            errorHandler = { dispatcher.schedule { publish(guildId, get(guildId).copy(submitting = false, error = true)) } },
        ) { response ->
            dispatcher.schedule {
                response.body()?.let { handleLoaded(guildId, it) }
                publish(guildId, get(guildId).copy(submitting = false, completed = true))
            }
        }
    }

    /** Persists Channels & Roles edits without marking onboarding complete. */
    fun syncResponses(guildId: Long) {
        dispatcher.schedule {
            val current = get(guildId)
            if (current.submitting) return@schedule
            val prompts = current.customizationPrompts
            val body = responsesBody(current, prompts)
            val service = RestAPI.apiOrNull ?: return@schedule publish(guildId, current.copy(error = true))
            publish(guildId, current.copy(submitting = true, error = false))
            service.updateGuildOnboardingResponses(guildId, body).restSubscribeOn(false).appSubscribe(
                StoreGuildOnboarding::class.java,
                errorHandler = { dispatcher.schedule { publish(guildId, get(guildId).copy(submitting = false, error = true)) } },
            ) { response ->
                dispatcher.schedule {
                    val latest = get(guildId)
                    val responseBody = response.body()?.let { body ->
                        val existing = latest.onboarding
                        if (existing != null && body.prompts.isEmpty()) {
                            existing.copy(
                                responses = body.responses ?: latest.selectedOptionIds.toList(),
                                onboardingPromptsSeen = body.onboardingPromptsSeen ?: existing.onboardingPromptsSeen,
                                onboardingResponsesSeen = body.onboardingResponsesSeen ?: existing.onboardingResponsesSeen,
                            )
                        } else body
                    }
                    if (responseBody != null) {
                        handleLoaded(guildId, responseBody)
                    } else {
                        publish(guildId, latest.copy(submitting = false))
                    }
                }
            }
        }
    }

    fun reset() = dispatcher.schedule {
        states.clear()
        subjects.values.forEach { it.onNext(State()) }
    }

    @StoreThread
    internal fun handleLoaded(guildId: Long, onboarding: GuildOnboarding) {
        publish(
            guildId,
            State(
                onboarding = onboarding,
                selectedOptionIds = onboarding.responses.orEmpty().toSet(),
            ),
        )
    }

    private fun responsesBody(
        state: State,
        prompts: List<GuildOnboardingPrompt>,
    ): GuildOnboardingResponses {
        val validOptionIds = prompts.flatMap { it.options }.mapTo(hashSetOf()) { it.id }
        val now = System.currentTimeMillis()
        return GuildOnboardingResponses(
            onboardingResponses = state.selectedOptionIds.filter(validOptionIds::contains),
            onboardingPromptsSeen = prompts.associate { it.id.toString() to now },
            onboardingResponsesSeen = prompts.flatMap { it.options }.associate { it.id.toString() to now },
        )
    }

    @StoreThread
    private fun publish(guildId: Long, state: State) {
        states[guildId] = state
        subject(guildId).onNext(state)
    }

    private fun subject(guildId: Long) = subjects.getOrPut(guildId) { BehaviorSubject.create(get(guildId)) }
}

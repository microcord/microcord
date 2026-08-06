package com.discord.stores

import com.discord.api.guild.GuildOnboarding
import com.discord.api.guild.GuildOnboardingPrompt
import com.discord.api.guild.GuildOnboardingPromptOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreGuildOnboardingTest {
    private val scheduler = TestScheduler()
    private val store = StoreGuildOnboarding(Dispatcher(scheduler))

    @Test fun requiredAndSingleSelectPromptSemanticsArePreserved() {
        val prompt = GuildOnboardingPrompt(
            id = 1,
            title = "Choose one",
            required = true,
            singleSelect = true,
            options = listOf(option(10), option(11)),
        )
        store.handleLoaded(GUILD_ID, GuildOnboarding(prompts = listOf(prompt)))
        assertEquals(prompt, store.get(GUILD_ID).missingRequiredPrompt())

        store.selectOption(GUILD_ID, prompt.id, 10, true)
        scheduler.triggerActions()
        assertNull(store.get(GUILD_ID).missingRequiredPrompt())

        store.selectOption(GUILD_ID, prompt.id, 11, true)
        scheduler.triggerActions()
        assertEquals(setOf(11L), store.get(GUILD_ID).selectedOptionIds)
    }

    @Test fun onboardingUsesOnlyFourEnabledInFlowPrompts() {
        val prompts = (1L..7L).map { id ->
            GuildOnboardingPrompt(
                id = id,
                title = id.toString(),
                disabled = id == 2L,
                inOnboarding = id != 3L,
            )
        }
        store.handleLoaded(GUILD_ID, GuildOnboarding(prompts = prompts))
        assertEquals(listOf(1L, 4L, 5L, 6L), store.get(GUILD_ID).prompts.map { it.id })
    }

    private fun option(id: Long) = GuildOnboardingPromptOption(id = id, title = id.toString())

    private companion object {
        const val GUILD_ID = 42L
    }
}

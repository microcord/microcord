package com.discord.widgets.user.search

import com.discord.widgets.user.search.WidgetGlobalSearchModel.MatchedResult
import com.discord.widgets.user.search.WidgetGlobalSearchScoreStrategy.scoreFrecency
import com.discord.widgets.user.search.WidgetGlobalSearchScoreStrategy.scoreMatchedResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the scoring weights recovered from the reference DEX.
 *
 * These numbers are not arbitrary — their *relative magnitudes* are the priority order of the quick
 * switcher — so each case below states the ordering it protects.
 */
class WidgetGlobalSearchScoreStrategyTest {

    private val frecency = (1L..60L).toList()

    @Test
    fun frecencyIsFrontLoadedAndFallsOffACliffAfterTheTopFive() {
        assertEquals(60, frecency.scoreFrecency(1L))
        assertEquals(56, frecency.scoreFrecency(5L))
        // Position 5 continues from 50, not 55: the first five entries are deliberately privileged.
        assertEquals(50, frecency.scoreFrecency(6L))
        assertEquals(6, frecency.scoreFrecency(50L))
        assertEquals(2, frecency.scoreFrecency(51L))
        assertEquals(0, frecency.scoreFrecency(999L))
    }

    @Test
    fun matchPositionScoresHighestAtTheStartAndDiesOutByIndexTen() {
        // 15 for index 0, plus 2 for starting the name.
        assertEquals(17, MatchedResult("general", 0, 3).scoreMatchedResult())
        assertEquals(5, MatchedResult("aaaaaaaaaaageneral", 10, 3).scoreMatchedResult())
        assertEquals(0, MatchedResult("aaaaaaaaaaaageneral", 11, 3).scoreMatchedResult())
    }

    @Test
    fun matchingTheStartOfAWordScoresAboveMatchingMidWord() {
        val wordStart = MatchedResult("voice-chat", 6, 4).scoreMatchedResult()
        val midWord = MatchedResult("voicexchat", 6, 4).scoreMatchedResult()
        assertEquals(1, wordStart - midWord)
    }

    @Test
    fun fuzzyMatchesRankBelowEveryLiteralMatch() {
        val fuzzy = MatchedResult("voice-chat", -1, 2).scoreMatchedResult()
        val worstLiteral = MatchedResult("aaaaaaaaaaaageneral", 11, 3).scoreMatchedResult()
        assertTrue(fuzzy < worstLiteral)
        // -100 base, minus the name length, capped at a 20-character deduction.
        assertEquals(-110, fuzzy)
        assertEquals(-120, MatchedResult("a".repeat(40), -1, 2).scoreMatchedResult())
    }
}

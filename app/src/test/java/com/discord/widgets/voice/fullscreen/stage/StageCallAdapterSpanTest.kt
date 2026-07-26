package com.discord.widgets.voice.fullscreen.stage

import org.junit.Assert.assertEquals
import org.junit.Test

class StageCallAdapterSpanTest {

    @Test
    fun `a lone speaker takes the whole row, a pair halves it, three or more thirds it`() {
        assertEquals(
            StageCallAdapter.SPEAKER_FULL_SPAN_COUNT,
            StageCallAdapter.spanSizeFor(StageCallItem.TYPE_SPEAKER, numSpeakers = 1),
        )
        assertEquals(
            StageCallAdapter.SPEAKER_HALF_SPAN_COUNT,
            StageCallAdapter.spanSizeFor(StageCallItem.TYPE_SPEAKER, numSpeakers = 2),
        )
        assertEquals(
            StageCallAdapter.SPEAKER_THIRD_SPAN_COUNT,
            StageCallAdapter.spanSizeFor(StageCallItem.TYPE_SPEAKER, numSpeakers = 3),
        )
        assertEquals(
            StageCallAdapter.SPEAKER_THIRD_SPAN_COUNT,
            StageCallAdapter.spanSizeFor(StageCallItem.TYPE_SPEAKER, numSpeakers = 12),
        )
    }

    @Test
    fun `audience rows sit three-up and every other row spans the full width`() {
        assertEquals(
            StageCallAdapter.AUDIENCE_SPAN_COUNT,
            StageCallAdapter.spanSizeFor(StageCallItem.TYPE_AUDIENCE, numSpeakers = 5),
        )
        listOf(
            StageCallItem.TYPE_DETAILS,
            StageCallItem.TYPE_PRE_START_DETAILS,
            StageCallItem.TYPE_AUDIENCE_HEADER,
            StageCallItem.TYPE_DIVIDER,
            StageCallItem.TYPE_MEDIA,
        ).forEach { type ->
            assertEquals(
                "type $type should span the full grid",
                StageCallAdapter.SPAN_COUNT,
                StageCallAdapter.spanSizeFor(type, numSpeakers = 5),
            )
        }
    }

    @Test
    fun `the grid is twelve columns so speakers divide evenly one, two or three up`() {
        assertEquals(0, StageCallAdapter.SPAN_COUNT % StageCallAdapter.SPEAKER_FULL_SPAN_COUNT)
        assertEquals(0, StageCallAdapter.SPAN_COUNT % StageCallAdapter.SPEAKER_HALF_SPAN_COUNT)
        assertEquals(0, StageCallAdapter.SPAN_COUNT % StageCallAdapter.SPEAKER_THIRD_SPAN_COUNT)
    }
}

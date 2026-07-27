package com.discord.widgets.chat.input.expression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two derived flags on the tray's view state.
 *
 * They decide whether the tray shows its tabs or a detail page, and whether the search field is
 * visible — both of which are wrong-but-plausible-looking when they break, so they are pinned.
 */
class ExpressionTrayViewStateTest {

    private fun viewState(
        tab: ExpressionTrayTab,
        detailPage: ExpressionDetailPage? = null,
        showStickersSearchBar: Boolean = true,
    ) = ExpressionTrayViewModel.ViewState(
        selectedExpressionTab = tab,
        expressionDetailPage = detailPage,
        showStickersSearchBar = showStickersSearchBar,
        showGifsAndStickers = true,
    )

    @Test
    fun aDetailPageReplacesTheLandingPage() {
        assertTrue(viewState(ExpressionTrayTab.GIF).showLandingPage)

        val drilledIn = viewState(
            ExpressionTrayTab.GIF,
            detailPage = ExpressionDetailPage.GifCategoryPage(GifCategoryItemFixture.trending),
        )
        assertFalse(drilledIn.showLandingPage)
    }

    @Test
    fun onlyTheStickerTabCanHideTheSearchField() {
        assertTrue(viewState(ExpressionTrayTab.EMOJI, showStickersSearchBar = false).showSearchBar)
        assertTrue(viewState(ExpressionTrayTab.GIF, showStickersSearchBar = false).showSearchBar)
        assertFalse(viewState(ExpressionTrayTab.STICKER, showStickersSearchBar = false).showSearchBar)
        assertTrue(viewState(ExpressionTrayTab.STICKER, showStickersSearchBar = true).showSearchBar)
    }

    /**
     * The segmented control indexes into this order and the persisted tab is stored by ordinal, so
     * reordering the enum would silently reopen the tray on the wrong tab.
     */
    @Test
    fun tabOrdinalsAreEmojiGifSticker() {
        assertEquals(0, ExpressionTrayTab.EMOJI.ordinal)
        assertEquals(1, ExpressionTrayTab.GIF.ordinal)
        assertEquals(2, ExpressionTrayTab.STICKER.ordinal)
    }
}

private object GifCategoryItemFixture {
    val trending = com.discord.widgets.chat.input.gifpicker.GifCategoryItem.Trending("https://p")
}

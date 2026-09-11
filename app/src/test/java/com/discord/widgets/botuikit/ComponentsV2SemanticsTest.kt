package com.discord.widgets.botuikit

import com.discord.api.botuikit.ActionRowComponent
import com.discord.api.botuikit.ButtonComponent
import com.discord.api.botuikit.ButtonStyle
import com.discord.api.botuikit.ComponentType
import com.discord.api.botuikit.ContainerComponent
import com.discord.api.botuikit.MediaGalleryComponent
import com.discord.api.botuikit.MediaGalleryItem
import com.discord.api.botuikit.MediaItemType
import com.discord.api.botuikit.SectionComponent
import com.discord.api.botuikit.SeparatorComponent
import com.discord.api.botuikit.TextDisplayComponent
import com.discord.api.botuikit.UnfurledMediaItem
import com.discord.models.botuikit.ContainerMessageComponent
import com.discord.models.botuikit.SectionMessageComponent
import com.discord.models.botuikit.SeparatorMessageComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules that decide whether a components v2 tree renders at all, and as what. */
class ComponentsV2SemanticsTest {
    private val allEnabled = object : ComponentExperiments {
        override fun isEnabled(componentType: ComponentType) = componentType != ComponentType.UNKNOWN
    }

    private fun map(vararg components: com.discord.api.botuikit.Component) =
        ComponentStateMapper.processComponentsToMessageComponents(
            components.toList(),
            componentStoreState = null,
            componentExperiments = allEnabled,
            animateEmojis = false,
        )

    private fun media(
        width: Int? = 100,
        height: Int? = 100,
        contentType: String? = "image/png",
    ) = UnfurledMediaItem("https://cdn.example/a.png", null, height, width, contentType, null)

    private fun text(id: Int) = TextDisplayComponent(ComponentType.TEXT_DISPLAY, id, "body")

    private fun button() = ButtonComponent(
        ComponentType.BUTTON,
        "custom",
        "Label",
        ButtonStyle.PRIMARY,
        false,
        null,
        null,
    )

    @Test
    fun mediaTypeNeedsBothAContentTypeAndRealDimensions() {
        assertEquals(MediaItemType.IMAGE, media().mediaType)
        assertEquals(MediaItemType.VIDEO, media(contentType = "video/mp4").mediaType)
        // Still unfurling: the proxy has not reported a size yet.
        assertEquals(MediaItemType.PLACEHOLDER, media(width = null, height = null).mediaType)
        assertEquals(MediaItemType.PLACEHOLDER, media(width = 0).mediaType)
        // Unfurled, but not something that can be drawn.
        assertEquals(MediaItemType.PLACEHOLDER, media(contentType = "application/pdf").mediaType)
        assertEquals(MediaItemType.PLACEHOLDER, media(contentType = null).mediaType)
    }

    @Test
    fun anEmptyGalleryIsDroppedButAnUnresolvedItemIsKept() {
        assertTrue(map(MediaGalleryComponent(ComponentType.MEDIA_GALLERY, 1, emptyList())).isEmpty())

        val unresolved = MediaGalleryItem(media(width = null, height = null), null, null)
        val kept = map(MediaGalleryComponent(ComponentType.MEDIA_GALLERY, 1, listOf(unresolved)))
        assertEquals(1, kept.size)
    }

    @Test
    fun aSectionNeedsBothTextAndAnAccessory() {
        // No text beside the accessory.
        assertTrue(map(SectionComponent(ComponentType.SECTION, 1, emptyList(), button())).isEmpty())

        val whole = map(SectionComponent(ComponentType.SECTION, 1, listOf(text(2)), button()))
        val section = whole.single() as SectionMessageComponent
        assertEquals(1, section.components.size)
    }

    @Test
    fun aSectionWithNoAccessoryIsDroppedRatherThanBorrowingItsLastTextBlock() {
        val mapped = map(SectionComponent(ComponentType.SECTION, 1, listOf(text(2), text(3)), null))
        assertTrue(mapped.isEmpty())
    }

    @Test
    fun aSectionWhoseAccessoryCannotBeRenderedIsDropped() {
        val noButtons = object : ComponentExperiments {
            override fun isEnabled(componentType: ComponentType) = componentType != ComponentType.BUTTON
        }
        val mapped = ComponentStateMapper.processComponentsToMessageComponents(
            listOf(SectionComponent(ComponentType.SECTION, 1, listOf(text(2)), button())),
            componentStoreState = null,
            componentExperiments = noButtons,
            animateEmojis = false,
        )
        assertTrue(mapped.isEmpty())
    }

    @Test
    fun aSectionNumbersItsTextBeforeItsAccessory() {
        val section = map(SectionComponent(ComponentType.SECTION, 1, listOf(text(2), text(3)), button()))
            .single() as SectionMessageComponent
        assertEquals(0, section.index)
        assertEquals(listOf(1, 2), section.components.map { it.index })
        assertEquals(3, section.accessory.index)
    }

    @Test
    fun anEmptyActionRowIsDropped() {
        assertTrue(map(ActionRowComponent(ComponentType.ACTION_ROW, emptyList())).isEmpty())
        assertEquals(1, map(ActionRowComponent(ComponentType.ACTION_ROW, listOf(button()))).size)
    }

    @Test
    fun droppingAChildLeavesTheSurvivingIndicesAlone() {
        // The empty row still consumes its own index, so the interaction indices of everything
        // after it do not shift when it disappears.
        val mapped = map(
            ActionRowComponent(ComponentType.ACTION_ROW, emptyList()),
            ActionRowComponent(ComponentType.ACTION_ROW, listOf(button())),
        )
        assertEquals(1, mapped.single().index)
    }

    @Test
    fun separatorDefaultsToADividerAtTheSmallStep() {
        val defaults = map(SeparatorComponent(ComponentType.SEPARATOR, 1, null, null))
            .single() as SeparatorMessageComponent
        assertTrue(defaults.divider)
        assertEquals(SeparatorComponent.SPACING_SMALL, defaults.spacing)

        val explicit = map(SeparatorComponent(ComponentType.SEPARATOR, 1, false, SeparatorComponent.SPACING_LARGE))
            .single() as SeparatorMessageComponent
        assertEquals(false, explicit.divider)
        assertEquals(SeparatorComponent.SPACING_LARGE, explicit.spacing)
    }

    @Test
    fun aContainerKeepsTheChildrenThatSurvive() {
        val container = map(
            ContainerComponent(
                ComponentType.CONTAINER,
                1,
                listOf(
                    text(2),
                    SectionComponent(ComponentType.SECTION, 3, emptyList(), button()),
                    MediaGalleryComponent(ComponentType.MEDIA_GALLERY, 4, emptyList()),
                ),
                accentColor = 0x5865F2,
                spoiler = null,
            ),
        ).single() as ContainerMessageComponent

        assertEquals(1, container.children.size)
        assertEquals(0x5865F2, container.accentColor)
    }
}

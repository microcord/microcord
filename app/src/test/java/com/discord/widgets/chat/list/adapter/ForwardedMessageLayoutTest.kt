package com.discord.widgets.chat.list.adapter

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ForwardedMessageLayoutTest {
    @Test
    fun snapshotIsOneFlatAccessibleContainerWithFooterLast() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/main/res/layout/widget_chat_list_adapter_item_forwarded_message.xml"))
        val all = document.getElementsByTagName("*")
        val elements = (0 until all.length).map { all.item(it) as Element }
        val ids = elements.mapNotNull { element ->
            element.getAttributeNS(ANDROID_NS, "id").takeIf(String::isNotEmpty)
        }

        assertEquals(1, elements.count { it.tagName.endsWith("MaterialCardView") })
        assertFalse(elements.any { it.tagName.endsWith("RecyclerView") })
        assertTrue(elements.any { it.tagName.endsWith("LinkifiedTextView") })
        assertTrue(ids.indexOf("@+id/forwarded_message_icon") < ids.indexOf("@+id/forwarded_message_label"))
        assertTrue(ids.indexOf("@+id/forwarded_message_label") < ids.indexOf("@+id/forwarded_message_content"))
        assertFalse(ids.contains("@+id/forwarded_message_guild_name"))
        assertTrue(ids.indexOf("@+id/forwarded_message_content") < ids.indexOf("@+id/forwarded_message_rows"))
        assertTrue(ids.indexOf("@+id/forwarded_message_rows") < ids.indexOf("@+id/forwarded_message_poll"))
        assertTrue(ids.indexOf("@+id/forwarded_message_rows") < ids.indexOf("@+id/forwarded_message_source"))

        val loading = elements.single { it.getAttributeNS(ANDROID_NS, "id") == "@+id/forwarded_message_loading" }
        val icon = elements.single { it.getAttributeNS(ANDROID_NS, "id") == "@+id/forwarded_message_icon" }
        val guildIcon = elements.single {
            it.getAttributeNS(ANDROID_NS, "id") == "@+id/forwarded_message_source_guild_icon"
        }
        val source = elements.single { it.getAttributeNS(ANDROID_NS, "id") == "@+id/forwarded_message_source" }
        assertEquals("@string/loading", loading.getAttributeNS(ANDROID_NS, "contentDescription"))
        assertEquals("@drawable/ic_forward_16dp", icon.getAttributeNS(ANDROID_NS, "src"))
        assertTrue(ids.indexOf("@+id/forwarded_message_source_guild_icon") < ids.indexOf("@+id/forwarded_message_source"))
        assertEquals("14dp", guildIcon.getAttributeNS(ANDROID_NS, "layout_width"))
        assertFalse(source.hasAttributeNS(ANDROID_NS, "clickable"))
        assertFalse(source.hasAttributeNS(ANDROID_NS, "focusable"))

        val binder = File(
            "src/main/java/com/discord/widgets/chat/list/adapter/WidgetChatListAdapterItemForwardedMessage.kt",
        ).readText()
        assertFalse(binder.contains("rowAdapter"))
        assertFalse(binder.contains("lifecycleRows"))
        assertFalse(binder.contains("onCreateViewHolder"))
        assertTrue(binder.contains("InlineMediaView"))
        assertTrue(binder.contains("StickerView"))
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}

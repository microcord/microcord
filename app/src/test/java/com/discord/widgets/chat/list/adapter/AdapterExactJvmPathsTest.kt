package com.discord.widgets.chat.list.adapter

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterExactJvmPathsTest {
    @Test
    fun remaining12621AdapterPathsAndFacadesExist() {
        val sourceRoot = sequenceOf(
            File("app/src/main/java/com/discord/widgets/chat/list/adapter"),
            File("src/main/java/com/discord/widgets/chat/list/adapter"),
        ).first(File::isDirectory)

        REFERENCE_PATHS.forEach { path ->
            assertTrue(path, File(sourceRoot, "$path.kt").isFile)
        }
        JVM_FACADES.forEach { facade ->
            assertEquals(
                "com.discord.widgets.chat.list.adapter.$facade",
                Class.forName("com.discord.widgets.chat.list.adapter.$facade").name,
            )
        }
    }

    private companion object {
        val REFERENCE_PATHS = listOf(
            "WidgetChatListItemKt",
            "WidgetChatListAdapterItemGuildWelcomeKt",
            "WidgetChatListAdapterItemCallMessageKt",
            "WidgetChatListAdapterItemBotComponentRowKt",
            "WidgetChatListAdapterItemAutoModSystemMessageEmbedKt",
        )
        val JVM_FACADES = REFERENCE_PATHS
    }
}

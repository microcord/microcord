package com.discord.widgets.user

import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.parser.Parser
import com.discord.utilities.textprocessing.MessageParseState
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.widgets.user.presence.ActivityCustomButtonClickHandler
import com.discord.widgets.user.usersheet.AST
import com.discord.widgets.user.usersheet.BioParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class UserExactPathContractsTest {
    @Test
    fun `remaining reference paths and aliases are exact`() {
        val paths = listOf(
            "widgets/user/UserNameFormatterKt.kt",
            "widgets/user/presence/ViewHolderUserRichPresenceKt.kt",
            "widgets/user/profile/DraweeSpanStringBuilderExtensionsKt.kt",
            "widgets/user/usersheet/WidgetUserSheetViewModelKt.kt",
        )
        assertEquals(paths, paths.filter { File("src/main/java/com/discord/$it").isFile })

        val handler: ActivityCustomButtonClickHandler = { _, _, _, _, _ -> }
        val ast: AST = mutableListOf<Node<MessageRenderContext>>()
        val parser: BioParser = Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState>(false)
        assertNotNull(handler)
        assertEquals(0, ast.size)
        assertNotNull(parser)
    }
}

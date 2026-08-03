package com.discord.widgets.guilds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

class GuildExactJvmPathsTest {
    @Test
    fun `126 21 guild file facades exist at their exact paths`() {
        val paths = listOf(
            "create/StockGuildTemplateKt.kt",
            "invite/GuildInviteUiHelperKt.kt",
            "join/GuildJoinHelperKt.kt",
        )
        val sourceRoot = sequenceOf(
            File("app/src/main/java/com/discord/widgets/guilds"),
            File("src/main/java/com/discord/widgets/guilds"),
        ).first(File::isDirectory)
        paths.forEach { assertTrue(it, File(sourceRoot, it).isFile) }

        listOf(
            "com.discord.widgets.guilds.create.StockGuildTemplateKt",
            "com.discord.widgets.guilds.invite.GuildInviteUiHelperKt",
            "com.discord.widgets.guilds.join.GuildJoinHelperKt",
        ).forEach { assertNotNull(Class.forName(it)) }
    }

    @Test
    fun `join helper exposes only the reference public entry point`() {
        val methods = Class.forName("com.discord.widgets.guilds.join.GuildJoinHelperKt")
            .declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && it.name == "joinGuild" }

        assertEquals(1, methods.size)
        assertEquals(10, methods.single().parameterCount)
    }
}

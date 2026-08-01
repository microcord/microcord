package com.discord.dialogs

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstPartyDialogMigrationTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }

    @Test
    fun `owned auth and utility files contain no platform dialogs`() {
        listOf(
            "com/discord/widgets/auth/WidgetAuthMfa.kt",
            "com/discord/utilities/captcha/CaptchaHelper.kt",
            "com/discord/utilities/uri/UriHandler.kt",
            "com/discord/widgets/settings/StatefulViews.kt",
        ).forEach { path ->
            val source = source(path)
            listOf("AlertDialog", "MaterialAlertDialogBuilder", "android.app.Dialog").forEach {
                assertFalse("$path must not use $it", source.contains(it))
            }
        }
    }

    @Test
    fun `custom content uses AppDialog and recreation safe fragment results`() {
        assertContains(
            "com/discord/widgets/auth/WidgetAuthMfaDialog.kt",
            ": AppDialog()",
            "setFragmentResult(REQUEST_BACKUP_CODE",
            "setFragmentResultListener(REQUEST_BACKUP_CODE)",
        )
        assertContains(
            "com/discord/utilities/uri/UnhandledUriDialog.kt",
            ": AppDialog(R.layout.layout_unhandled_uri)",
        )
        assertContains(
            "com/discord/widgets/settings/DiscardChangesDialog.kt",
            ": AppDialog(R.layout.view_dialog_confirmation)",
            "setFragmentResult(argumentsOrDefault.getString(ARG_REQUEST_KEY)",
        )
        assertContains(
            "com/discord/widgets/settings/StatefulViews.kt",
            "setFragmentResultListener(requestKey)",
            "DiscardChangesDialog.show(fragment.parentFragmentManager, requestKey)",
        )
    }

    @Test
    fun `captcha help uses the first party notice and safe external opener`() {
        assertContains(
            "com/discord/utilities/captcha/CaptchaHelper.kt",
            "WidgetNoticeDialog.Builder(activity)",
            "UriHandler.handle(activity, CAPTCHA_HELP_URL, forceExternal = true)",
            ".setPositiveButton(R.string.captcha_open_browser)",
            ".setNegativeButton(R.string.cancel)",
        )
    }

    private fun assertContains(path: String, vararg snippets: String) {
        val source = source(path)
        snippets.forEach { assertTrue("$path must contain $it", source.contains(it)) }
    }

    private fun source(path: String) = File(root, "app/src/main/java/$path").readText()
}

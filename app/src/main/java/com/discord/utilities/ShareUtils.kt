package com.discord.utilities

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.discord.R
import com.discord.app.AppActionActivity
import com.discord.utilities.intent.AppIntentParser
import com.discord.utilities.intent.ShareContractParser

object ShareUtils {
    const val DIRECT_SHARE_CATEGORY = "com.discord.intent.category.DIRECT_SHARE_TARGET"
    const val CONVERSATION_CATEGORY = "android.shortcut.conversation"

    data class SharedContent(
        val text: String?,
        val subject: String?,
        val uris: List<Uri>,
        val directShareId: Long?,
    ) {
        val message: String
            get() = composeMessage(subject, text)
    }

    data class ShortcutPerson(val id: Long, val name: String, val bot: Boolean = false)

    data class ConversationShortcut(
        val channelId: Long,
        val label: String,
        val persons: List<ShortcutPerson> = emptyList(),
        val icon: Bitmap? = null,
    )

    internal data class ShortcutPlan(
        val shortcuts: List<ConversationShortcut>,
        val staleIds: List<String>,
    )

    fun getSharedContent(intent: Intent): SharedContent? = runCatching { parseSharedContent(intent) }.getOrNull()

    private fun parseSharedContent(intent: Intent): SharedContent? {
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelableUri(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableUris(Intent.EXTRA_STREAM)
            else -> return null
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeUnless(String::isBlank)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeUnless(String::isBlank)
        if (ShareContractParser.parse(intent.action, text, subject, uris.map(Uri::toString)) == null) return null
        return SharedContent(
            text = text,
            subject = subject,
            uris = uris,
            directShareId = intent.getStringExtra(EXTRA_SHORTCUT_ID)?.toLongOrNull(),
        )
    }

    fun updateDynamicShortcuts(context: Context, candidates: List<ConversationShortcut>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
        val existingIds = ShortcutManagerCompat.getDynamicShortcuts(context).map(ShortcutInfoCompat::getId)
        val plan = planShortcuts(candidates, max, existingIds)
        if (plan.staleIds.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(context, plan.staleIds)
        ShortcutManagerCompat.setDynamicShortcuts(context, plan.shortcuts.mapIndexed { rank, candidate ->
            toShortcut(context, candidate, rank)
        })
    }

    internal fun planShortcuts(
        candidates: List<ConversationShortcut>,
        max: Int,
        existingIds: Collection<String>,
    ): ShortcutPlan {
        val selected = candidates.distinctBy(ConversationShortcut::channelId).take(max.coerceAtLeast(0))
        val selectedIds = selected.mapTo(HashSet()) { it.channelId.toString() }
        return ShortcutPlan(selected, existingIds.filter { it.toLongOrNull() != null && it !in selectedIds })
    }

    internal fun composeMessage(subject: String?, text: String?): String = when {
        subject.isNullOrBlank() -> text.orEmpty()
        text.isNullOrBlank() || subject == text -> subject
        else -> "$subject\n$text"
    }

    private fun toShortcut(
        context: Context,
        candidate: ConversationShortcut,
        rank: Int,
    ): ShortcutInfoCompat {
        val id = candidate.channelId.toString()
        val intent = Intent(Intent.ACTION_SEND)
            .setClass(context, AppActionActivity::class.java)
            .setPackage(context.packageName)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .putExtra(EXTRA_SHORTCUT_ID, id)
        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(candidate.label)
            .setLongLabel(candidate.label)
            .setIcon(candidate.icon?.let(IconCompat::createWithAdaptiveBitmap)
                ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .setRank(rank)
            .setLongLived(true)
            .setLocusId(LocusIdCompat(id))
            .setCategories(setOf(DIRECT_SHARE_CATEGORY, CONVERSATION_CATEGORY))
            .setPersons(candidate.persons.map {
                Person.Builder().setKey(it.id.toString()).setName(it.name).setBot(it.bot).build()
            }.toTypedArray())
            .build()
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableUri(name: String): Uri? = getParcelableExtra(name)

    @Suppress("DEPRECATION")
    private fun Intent.parcelableUris(name: String): List<Uri> = getParcelableArrayListExtra<Uri>(name).orEmpty()

    private const val EXTRA_SHORTCUT_ID = "android.intent.extra.shortcut.ID"
}

package com.discord.widgets.chat.list.adapter

import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import com.discord.api.message.MessageFlags
import com.discord.api.message.MessageReference
import com.discord.api.message.embed.EmbedType
import com.discord.api.message.embed.MessageEmbed
import com.discord.api.user.User
import com.discord.app.AppPermissionsRequests
import com.discord.models.message.Message
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.chat.list.entries.ForwardedMessageEntry
import com.google.android.material.card.MaterialCardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForwardedMessageBindTest {
    @Test
    fun forwardedEmbedBindsWithoutNestedRecyclerOrMessageChrome() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme_Dark)
            val recycler = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
            }
            val embed = MessageEmbed(
                    title = "Sanitized embed",
                    type = EmbedType.RICH,
                    description = "Forwarded description",
                    url = null,
                    timestamp = null,
                    color = null,
                    footer = null,
                    image = null,
                    thumbnail = null,
                    video = null,
                    provider = null,
                    author = null,
                    fields = null,
            )
            val snapshot = Message(
                    id = 2L,
                    channelId = 20L,
                    author = User(99L, "must-not-render"),
                    content = "Forwarded body",
                    embeds = listOf(embed),
                    type = 0,
            )
            val parent = Message(
                    id = 1L,
                    channelId = 10L,
                    author = User(1L, "parent"),
                    flags = MessageFlags.HAS_SNAPSHOT,
                    messageReference = MessageReference(2L, 20L, null, type = 1),
                    type = 0,
            )
            val forwarded = ForwardedMessageEntry(
                    parentMessage = parent,
                    snapshotIndex = 0,
                    state = ForwardedMessageEntry.State.CONTENT,
                    message = snapshot,
                    rows = listOf(
                        EmbedEntry(0, 0L, snapshot, null, embed, false, true, true, false),
                    ),
            )
            val adapter = WidgetChatListAdapter(
                    recycler,
                    permissions,
                    fragmentManager(),
                    object : WidgetChatListAdapter.EventHandler {},
            )
            adapter.data = data(forwarded)

            val holder = adapter.onCreateViewHolder(recycler, ChatListEntry.FORWARDED_MESSAGE)
            holder.onBindViewHolder(0)

            val root = holder.itemView as MaterialCardView
            val accessories = root.findViewById<LinearLayout>(R.id.forwarded_message_rows)
            assertEquals(1, accessories.childCount)
            assertNotNull(findText(root, "Sanitized embed"))
            assertFalse(descendants(root).any { it is RecyclerView })
            assertEquals(1, descendants(root).count { it is MaterialCardView })
            assertNotNull(root.findViewById<View>(R.id.forwarded_message_icon))
            assertNotNull(root.findViewById<View>(R.id.forwarded_message_source_guild_icon))
            assertEquals(null, findText(root, "must-not-render"))
            assertEquals(null, root.findViewById<View>(R.id.chat_list_adapter_item_text_avatar))
            assertEquals(null, root.findViewById<View>(R.id.chat_list_adapter_item_text_name))
        }
    }

    private fun data(entry: ForwardedMessageEntry) = object : WidgetChatListAdapter.Data {
        override val channelId = 10L
        override val channelNames = emptyMap<Long, String>()
        override val guild = null
        override val guildId = 0L
        override val list = listOf(entry)
        override val myRoleIds = emptySet<Long>()
        override val newMessagesMarkerMessageId = 0L
        override val oldestMessageId = 0L
        override val userId = 1L
        override val isSpoilerClickAllowed = false
    }

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(descendants(root.getChildAt(it))) }
    }

    private fun findText(root: View, text: String): View? = descendants(root).firstOrNull { view ->
        (view as? android.widget.TextView)?.text?.toString() == text
    }

    private fun fragmentManager(): FragmentManager {
        val constructor = Class.forName("androidx.fragment.app.FragmentManagerImpl").getDeclaredConstructor()
        constructor.isAccessible = true
        return constructor.newInstance() as FragmentManager
    }

    private val permissions = object : AppPermissionsRequests {
        override fun hasMedia() = true
        override fun requestVideoCallPermissions(onSuccess: () -> Unit) = onSuccess()
        override fun requestMedia(onSuccess: () -> Unit) = onSuccess()
        override fun requestMediaDownload(onSuccess: () -> Unit) = onSuccess()
        override fun requestMicrophone(onFailure: (() -> Unit)?, onSuccess: () -> Unit) = onSuccess()
        override fun requestCameraQRScanner(onSuccess: () -> Unit) = onSuccess()
        override fun requestCameraQRScanner(onSuccess: () -> Unit, onFailure: (() -> Unit)?) = onSuccess()
        override fun requestContacts(onSuccess: () -> Unit, onFailure: () -> Unit) = onSuccess()
        override fun requestNotifications(onFailure: () -> Unit, onSuccess: () -> Unit) = onSuccess()
    }
}

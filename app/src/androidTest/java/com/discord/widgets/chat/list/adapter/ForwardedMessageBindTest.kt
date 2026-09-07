package com.discord.widgets.chat.list.adapter

import android.view.ContextThemeWrapper
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import com.discord.api.message.MessageFlags
import com.discord.api.message.MessageReference
import com.discord.api.message.embed.EmbedType
import com.discord.api.message.embed.EmbedImage
import com.discord.api.message.embed.MessageEmbed
import com.discord.api.user.User
import com.discord.app.AppPermissionsRequests
import com.discord.databinding.WidgetChatListAdapterItemEmbedBinding
import com.discord.databinding.WidgetChatListAdapterItemForwardedMessageBinding
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.models.message.Message
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.chat.list.entries.ForwardedMessageEntry
import com.google.android.material.card.MaterialCardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForwardedMessageBindTest {
    @Test
    fun embedGalleryKeepsAllTilesAndSelectionAndClearsRecycledImages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            var launched: Intent? = null
            val context = object : ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme_Dark) {
                override fun startActivity(intent: Intent) { launched = intent }
            }
            val preferences = SharedPreferencesProvider.get()
            val keys = listOf("CACHE_KEY_INLINE_EMBED_MEDIA", "CACHE_KEY_RENDER_EMBEDS")
            val previous = keys.associateWith { if (preferences.contains(it)) preferences.getBoolean(it, true) else null }
            val recycler = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
            val adapter = WidgetChatListAdapter(recycler, permissions, fragmentManager(), object : WidgetChatListAdapter.EventHandler {})
            val icons = listOf(R.drawable.ic_close_24dp, R.drawable.ic_file_download_white_24dp,
                R.drawable.ic_security_24dp, R.drawable.ic_status_24dp)
            val embeds = icons.mapIndexed { index, icon ->
                MessageEmbed(null, EmbedType.RICH, if (index == 0) "Gallery fixture" else null,
                    "https://example.com/post", null, null, null,
                    EmbedImage("res:///$icon", null, 1000 + index, 1500 + index), null, null, null, null, null)
            }
            val holder = adapter.onCreateViewHolder(recycler, ChatListEntry.MESSAGE_EMBED)
            val binding = WidgetChatListAdapterItemEmbedBinding.bind(holder.itemView)
            fun bind(count: Int, hidden: Boolean = false) {
                val selectedEmbeds = embeds.take(count)
                val message = Message(id = 10, channelId = 20, type = 0,
                    content = if (hidden) "||https://example.com/post||" else "", embeds = selectedEmbeds)
                adapter.data = data(EmbedEntry(0, 0, message, null, selectedEmbeds[0], false, false, true, false))
                holder.onBindViewHolder(0)
                holder.itemView.measure(View.MeasureSpec.makeMeasureSpec(context.resources.displayMetrics.widthPixels,
                    View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                holder.itemView.layout(0, 0, holder.itemView.measuredWidth, holder.itemView.measuredHeight)
            }
            try {
                preferences.edit().putBoolean(keys[0], true).putBoolean(keys[1], true).commit()
                bind(4)
                val grid = binding.embedImageGrid
                assertEquals(4, grid.childCount)
                assertEquals(View.VISIBLE, grid.visibility)
                assertEquals(View.GONE, binding.chatListItemEmbedImage.visibility)
                assertEquals("Gallery fixture", binding.chatListItemEmbedDescription.text.toString())
                assertTrue(grid.getChildAt(2).top > grid.getChildAt(0).top)
                assertTrue(grid.getChildAt(0).left < grid.getChildAt(1).left)
                val firstTile = grid.getChildAt(0)
                grid.getChildAt(2).performClick()
                assertEquals(2, launched?.getIntExtra("INTENT_GALLERY_INDEX", -1))
                @Suppress("DEPRECATION")
                val gallery = launched?.getSerializableExtra("INTENT_GALLERY") as? List<*>
                assertEquals(4, gallery?.size)
                bind(3)
                assertEquals(3, grid.childCount)
                assertSame(firstTile, grid.getChildAt(0))
                assertTrue(grid.getChildAt(2).width > grid.getChildAt(0).width)
                launched = null
                bind(4, hidden = true)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    binding.chatListItemEmbedContent.importantForAccessibility)
                grid.getChildAt(1).performClick()
                assertNull(launched)
                bind(1)
                assertEquals(0, grid.childCount)
                assertEquals(View.GONE, grid.visibility)
                assertEquals(View.VISIBLE, binding.chatListItemEmbedImage.visibility)
                firstTile.performClick()
                assertNull(launched)
                holder.itemView.layoutDirection = View.LAYOUT_DIRECTION_RTL
                bind(4)
                assertTrue(grid.getChildAt(0).left > grid.getChildAt(1).left)
                preferences.edit().putBoolean(keys[0], false).commit()
                bind(4)
                assertEquals(View.GONE, binding.embedImageContainer.visibility)
                assertEquals(0, grid.childCount)
            } finally {
                (holder as WidgetChatListAdapterItemEmbed).onPause()
                preferences.edit().apply {
                    previous.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value) }
                }.commit()
            }
        }
    }

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

            val binding = WidgetChatListAdapterItemForwardedMessageBinding.bind(holder.itemView)
            val root = binding.root
            val accessories = binding.forwardedMessageRows
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

    private fun data(entry: ChatListEntry) = object : WidgetChatListAdapter.Data {
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

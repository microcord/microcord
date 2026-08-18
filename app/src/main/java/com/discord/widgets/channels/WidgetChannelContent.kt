package com.discord.widgets.channels

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.message.Message
import com.discord.api.message.attachment.MessageAttachment
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.databinding.WidgetChannelContentBinding
import com.discord.databinding.WidgetChannelContentItemBinding
import com.discord.models.domain.ModelSearchTabsResponse
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.images.MGImages
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.media.WidgetMedia
import com.discord.widgets.media.MediaViewerContext
import com.discord.widgets.media.mediaVideoPreviewUrl

internal fun isDirectVideoUrl(url: String?): Boolean {
    val path = url?.substringBefore('?')?.substringBefore('#')?.lowercase() ?: return false
    return VIDEO_EXTENSIONS.any(path::endsWith)
}

private val VIDEO_EXTENSIONS = listOf(".mp4", ".webm", ".mov", ".mkv")

class WidgetChannelContent : AppFragment(R.layout.widget_channel_content) {
    private enum class Tab(val key: String, val has: List<String>, val emptyText: Int) {
        MEDIA("media", listOf("image", "video"), R.string.channel_content_empty_media),
        FILES("files", listOf("file"), R.string.channel_content_empty_files),
        LINKS("links", listOf("link"), R.string.channel_content_empty_links),
    }

    private data class Item(
        val url: String,
        val previewUrl: String? = null,
        val title: String? = null,
        val description: String? = null,
        val video: Boolean = false,
        val attachment: MessageAttachment? = null,
        val channelId: Long,
        val messageId: Long,
        val metadata: String,
        val viewerContext: MediaViewerContext? = null,
    )

    private data class TabState(
        val items: MutableList<Item> = mutableListOf(),
        val seen: MutableSet<String> = mutableSetOf(),
        var cursor: com.discord.api.search.SearchCursor? = null,
        var loading: Boolean = false,
        var loaded: Boolean = false,
        var canLoadMore: Boolean = true,
    )

    private var _binding: WidgetChannelContentBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val states = Tab.entries.associateWith { TabState() }
    private val adapter = Adapter(::open, ::jump)
    private var tab = Tab.MEDIA
    private var gridMode = false
    private val guildId get() = mostRecentIntent.getLongExtra(EXTRA_GUILD_ID, 0L)
    private val channelId get() = mostRecentIntent.getLongExtra(EXTRA_CHANNEL_ID, 0L)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetChannelContentBinding.bind(view)
        setActionBarTitle(if (channelId == 0L) R.string.server_content else R.string.channel_content)
        setActionBarDisplayHomeAsUpEnabled(true, R.drawable.ic_arrow_back_white_24dp, R.string.back)
            ?.setNavigationOnClickListener { requireActivity().finish() }
        setOnBackPressed(handler = { requireActivity().finish(); true })
        gridMode = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(GRID_MODE, false)
        binding.channelContentMedia.setOnClickListener { select(Tab.MEDIA) }
        binding.channelContentFiles.setOnClickListener { select(Tab.FILES) }
        binding.channelContentLinks.setOnClickListener { select(Tab.LINKS) }
        binding.channelContentGridToggle.setOnClickListener {
            gridMode = !gridMode
            requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(GRID_MODE, gridMode).apply()
            configureLayout()
        }
        binding.channelContentRecycler.adapter = adapter
        binding.channelContentRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val last = (recyclerView.layoutManager as? LinearLayoutManager)?.findLastVisibleItemPosition() ?: return
                if (last >= adapter.itemCount - 4) load()
            }
        })
        select(Tab.MEDIA)
    }

    override fun onDestroyView() {
        binding.channelContentRecycler.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun select(value: Tab) {
        tab = value
        binding.channelContentMedia.isSelected = value == Tab.MEDIA
        binding.channelContentFiles.isSelected = value == Tab.FILES
        binding.channelContentLinks.isSelected = value == Tab.LINKS
        binding.channelContentGridToggle.visibility = if (value == Tab.MEDIA) View.VISIBLE else View.GONE
        configureLayout()
        val state = states.getValue(value)
        adapter.submit(state.items, gridMode && value == Tab.MEDIA)
        renderState(state)
        if (!state.loaded && !state.loading) load()
    }

    private fun configureLayout() {
        val grid = gridMode && tab == Tab.MEDIA
        binding.channelContentGridToggle.setText(if (grid) R.string.list else R.string.grid)
        binding.channelContentRecycler.layoutManager = if (grid) GridLayoutManager(context, 3) else LinearLayoutManager(context)
        adapter.submit(states.getValue(tab).items, grid)
    }

    private fun load() {
        val requestedTab = tab
        val state = states.getValue(requestedTab)
        if (state.loading || !state.canLoadMore) return
        val api = RestAPI.apiOrNull ?: return
        state.loading = true
        renderState(state)
        val body = RestAPIParams.SearchTabs(
            tabs = mapOf(requestedTab.key to RestAPIParams.SearchTabs.Tab(
                has = requestedTab.has,
                sortBy = "timestamp",
                sortOrder = "desc",
                limit = PAGE_SIZE,
                cursor = state.cursor,
            )),
            includeNsfw = true,
            channelIds = channelId.takeIf { guildId > 0L && it > 0L }?.let { listOf(it.toString()) },
            trackExactTotalHits = false,
        )
        val request = if (guildId > 0L) api.searchGuildMessageTabs(guildId, body)
        else api.searchChannelMessageTabs(channelId, body)
        request.restSubscribeOn(false).ui(this).appSubscribe(
            WidgetChannelContent::class.java,
            errorHandler = {
                state.loading = false
                state.loaded = true
                if (tab == requestedTab) renderState(state, failed = true)
            },
        ) { response ->
            val result = response.tabs?.get(requestedTab.key)
            val channels = result?.channels.orEmpty().associate { it.id to it.name.orEmpty() }
            val added = result?.messages.orEmpty().flatten().filter { it.hit != false }
                .flatMap { message -> items(requestedTab, message, channels[message.channelId].orEmpty()) }
                .filter { state.seen.add(it.url) }
                .sortedByDescending(Item::messageId)
            state.items += added
            state.items.sortByDescending(Item::messageId)
            state.cursor = result?.cursor?.takeUnless(com.discord.api.search.SearchCursor::isEmpty)
            state.canLoadMore = state.cursor != null && added.isNotEmpty()
            state.loading = false
            state.loaded = true
            if (tab == requestedTab) {
                adapter.submit(state.items, gridMode && requestedTab == Tab.MEDIA)
                renderState(state)
            }
        }
    }

    private fun items(tab: Tab, message: Message, channelName: String): List<Item> {
        val author = message.author?.username ?: "Unknown"
        val metadata = "$author • #${channelName.ifBlank { "channel" }}"
        val avatar = message.author?.let { user ->
            IconUtils.getForUser(user.id, user.avatar?.value, user.discriminator.toIntOrNull(), size = 32)
        }
        return when (tab) {
            Tab.MEDIA -> buildList {
                message.attachments.orEmpty().forEach { attachment ->
                    if (attachment.type != com.discord.api.message.attachment.MessageAttachmentType.FILE) {
                        val video = attachment.type == com.discord.api.message.attachment.MessageAttachmentType.VIDEO
                        add(Item(attachment.url, if (video) mediaVideoPreviewUrl(attachment.proxyUrl, attachment.width, attachment.height) else attachment.proxyUrl, attachment.filename, video = video, attachment = attachment, channelId = message.channelId, messageId = message.id, metadata = metadata))
                    }
                }
                message.embeds.orEmpty().forEach { embed ->
                    embed.video?.let { video ->
                        val stream = video.proxyUrl ?: video.url ?: return@forEach
                        if (!isDirectVideoUrl(stream)) return@forEach
                        val poster = embed.thumbnail?.proxyUrl ?: embed.thumbnail?.url ?: mediaVideoPreviewUrl(stream, video.width, video.height)
                        add(Item(stream, poster, embed.title, video = true, attachment = MessageAttachment(stream, 0, 0, stream, "video.${Uri.parse(stream).path?.substringAfterLast('.', "mp4")}", video.width, video.height, contentType = "video/mp4"), channelId = message.channelId, messageId = message.id, metadata = metadata))
                        return@forEach
                    }
                    embed.image?.url?.let { url ->
                        val proxy = embed.image.proxyUrl ?: url
                        add(Item(url, proxy, embed.title, attachment = MessageAttachment(url, 0, 0, proxy, "image.png", embed.image.width, embed.image.height, contentType = "image/png"), channelId = message.channelId, messageId = message.id, metadata = metadata))
                    } ?: embed.thumbnail?.url?.let { url ->
                        val proxy = embed.thumbnail.proxyUrl ?: url
                        add(Item(url, proxy, embed.title, attachment = MessageAttachment(url, 0, 0, proxy, "image.png", embed.thumbnail.width, embed.thumbnail.height, contentType = "image/png"), channelId = message.channelId, messageId = message.id, metadata = metadata))
                    }
                }
            }
            Tab.FILES -> message.attachments.orEmpty().map { attachment ->
                Item(attachment.url, title = attachment.filename, description = fileSize(attachment.size), attachment = attachment, channelId = message.channelId, messageId = message.id, metadata = metadata)
            }
            Tab.LINKS -> buildList {
                message.embeds.orEmpty().forEach { embed -> embed.url?.let { add(Item(it, title = embed.title, description = embed.description, channelId = message.channelId, messageId = message.id, metadata = metadata)) } }
                val matcher = Patterns.WEB_URL.matcher(message.content.orEmpty())
                while (matcher.find()) matcher.group()?.let { add(Item(it, channelId = message.channelId, messageId = message.id, metadata = metadata)) }
            }
        }.map { it.copy(viewerContext = MediaViewerContext(author, avatar, message.timestamp?.dateTimeMillis)) }
    }

    private fun renderState(state: TabState, failed: Boolean = false) {
        binding.channelContentProgress.visibility = if (state.loading && state.items.isEmpty()) View.VISIBLE else View.GONE
        val message = when {
            failed -> R.string.channel_content_load_failed
            state.loaded && state.items.isEmpty() -> tab.emptyText
            else -> null
        }
        binding.channelContentStatus.visibility = if (message == null) View.GONE else View.VISIBLE
        message?.let(binding.channelContentStatus::setText)
    }

    private fun open(item: Item) {
        if (tab == Tab.MEDIA && item.attachment != null) {
            val gallery = states.getValue(Tab.MEDIA).items.filter { it.attachment != null }
            val selected = gallery.indexOfFirst { it.url == item.url }.coerceAtLeast(0)
            WidgetMedia.launchGallery(
                requireContext(),
                gallery.mapNotNull(Item::attachment),
                selected,
                gallery.map(Item::viewerContext),
            )
        } else UriHandler.handle(requireContext(), item.url)
    }

    private fun jump(item: Item) {
        StoreStream.getMessagesLoader().jumpToMessage(item.channelId, item.messageId)
        requireActivity().finish()
    }

    private fun fileSize(bytes: Long): String = android.text.format.Formatter.formatFileSize(requireContext(), bytes)

    private class Adapter(
        private val open: (Item) -> Unit,
        private val jump: (Item) -> Unit,
    ) : RecyclerView.Adapter<Adapter.Holder>() {
        private var items: List<Item> = emptyList()
        private var grid = false

        fun submit(items: List<Item>, grid: Boolean) {
            this.items = items.toList()
            this.grid = grid
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            WidgetChannelContentItemBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position], grid)

        inner class Holder(private val binding: WidgetChannelContentItemBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: Item, grid: Boolean) {
                val tileSize = (binding.root.resources.displayMetrics.widthPixels - dp(40)) / 3
                binding.root.layoutParams = (binding.root.layoutParams as RecyclerView.LayoutParams).apply {
                    width = if (grid) tileSize else ViewGroup.LayoutParams.MATCH_PARENT
                    height = if (grid) width else ViewGroup.LayoutParams.WRAP_CONTENT
                    setMargins(if (grid) dp(2) else 0, 0, if (grid) dp(2) else 0, if (grid) dp(4) else dp(10))
                }
                binding.channelContentBody.setPadding(if (grid) 0 else dp(10), if (grid) 0 else dp(10), if (grid) 0 else dp(10), if (grid) 0 else dp(10))
                binding.channelContentPreviewContainer.layoutParams = binding.channelContentPreviewContainer.layoutParams.apply {
                    height = if (grid) tileSize else dp(180)
                }
                binding.channelContentPreviewContainer.visibility = if (item.previewUrl == null) View.GONE else View.VISIBLE
                binding.channelContentFileIcon.visibility = if (!grid && item.previewUrl == null && item.attachment != null) View.VISIBLE else View.GONE
                binding.channelContentDetails.visibility = if (grid) View.GONE else View.VISIBLE
                val avatar = item.viewerContext?.avatarUrl
                binding.channelContentGridAvatar.visibility = if (grid && avatar != null) View.VISIBLE else View.GONE
                avatar?.let {
                    MGImages.setImage(binding.channelContentAvatar, it)
                    MGImages.setImage(binding.channelContentGridAvatar, it)
                }
                binding.channelContentAvatar.visibility = if (avatar == null) View.GONE else View.VISIBLE
                item.previewUrl?.let { MGImages.setImage(binding.channelContentPreview, it) }
                binding.channelContentPlay.visibility = if (item.video) View.VISIBLE else View.GONE
                binding.channelContentTitle.text = item.title ?: item.url
                binding.channelContentMetadata.text = listOfNotNull(item.description, item.metadata).joinToString("\n")
                binding.root.setOnClickListener { open(item) }
                binding.root.setOnLongClickListener { jump(item); true }
            }
            private fun dp(value: Int) = (value * binding.root.resources.displayMetrics.density).toInt()
        }
    }

    companion object {
        private const val EXTRA_GUILD_ID = "guild_id"
        private const val EXTRA_CHANNEL_ID = "channel_id"
        private const val PAGE_SIZE = 25
        private const val PREFS = "channel_content"
        private const val GRID_MODE = "grid_mode"

        fun launch(context: Context, guildId: Long, channelId: Long) = AppScreenRouter.launch(
            context,
            WidgetChannelContent::class.java,
            Intent().putExtra(EXTRA_GUILD_ID, guildId).putExtra(EXTRA_CHANNEL_ID, channelId),
        )
    }
}

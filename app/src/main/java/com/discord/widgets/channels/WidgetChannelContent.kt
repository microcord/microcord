package com.discord.widgets.channels

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
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
import com.discord.views.ThemedColor
import com.discord.widgets.media.WidgetMedia
import com.discord.widgets.media.MediaViewerContext
import com.discord.widgets.media.mediaTilePreviewUrl
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
    private val adapters = Tab.entries.associateWith { Adapter(::open, ::jump) }
    private val recyclers = HashMap<Tab, RecyclerView>()
    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) = showTab(Tab.entries[position])
    }
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
        binding.channelContentPager.adapter = PageAdapter()
        // The pager grabs a touch after ~8 dp sideways however steep the gesture, so a fast, slightly
        // diagonal scroll flipped the page. The paging slop is twice that, enough for the list to
        // claim a vertical gesture first while a deliberate swipe still pages at once.
        (binding.channelContentPager.getChildAt(0) as? RecyclerView)?.setScrollingTouchSlop(RecyclerView.TOUCH_SLOP_PAGING)
        // Keep every page alive: switching tabs then never rebinds a list or loses its scroll position.
        binding.channelContentPager.offscreenPageLimit = Tab.entries.size - 1
        binding.channelContentPager.registerOnPageChangeCallback(pageCallback)
        showTab(Tab.MEDIA)
    }

    override fun onDestroyView() {
        binding.channelContentPager.unregisterOnPageChangeCallback(pageCallback)
        binding.channelContentPager.adapter = null
        recyclers.clear()
        _binding = null
        super.onDestroyView()
    }

    /** A tab tap slides the pager there; [showTab] then follows from the page callback. */
    private fun select(value: Tab) {
        binding.channelContentPager.setCurrentItem(value.ordinal, true)
    }

    /** The page that just became current: restyle the tabs and toggle, and load it on first view. */
    private fun showTab(value: Tab) {
        tab = value
        styleTab(binding.channelContentMedia, value == Tab.MEDIA)
        styleTab(binding.channelContentFiles, value == Tab.FILES)
        styleTab(binding.channelContentLinks, value == Tab.LINKS)
        // Invisible, not gone: it balances the spacer opposite so the tabs stay centred.
        binding.channelContentGridToggle.visibility = if (value == Tab.MEDIA) View.VISIBLE else View.INVISIBLE
        updateToggle()
        val state = states.getValue(value)
        renderState(state)
        if (!state.loaded && !state.loading) load(value) else loadUntilFilled(value)
    }

    /**
     * Loading is otherwise driven by scrolling, but a page asks for 25 messages and filtering to
     * media leaves far fewer tiles. If those don't fill the screen the list can't scroll, so nothing
     * would ever ask for more: keep going until it can, or until the results run out.
     */
    private fun loadUntilFilled(value: Tab) {
        val list = recyclers[value] ?: return
        list.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
            ) {
                list.removeOnLayoutChangeListener(this)
                val state = states.getValue(value)
                if (value == tab && list.height > 0 && state.loaded && state.canLoadMore && !state.loading &&
                    !list.canScrollVertically(1)
                ) {
                    load(value)
                }
            }
        })
        list.requestLayout()
    }

    /** One page per tab: a list that pages in more results as it nears the end. */
    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.Page>() {
        inner class Page(val list: RecyclerView) : RecyclerView.ViewHolder(list) {
            var pageTab = Tab.MEDIA
        }

        override fun getItemCount() = Tab.entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Page {
            val density = parent.resources.displayMetrics.density
            val list = RecyclerView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                clipToPadding = false
                setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), 0)
            }
            val page = Page(list)
            list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val last = (recyclerView.layoutManager as? LinearLayoutManager)?.findLastVisibleItemPosition() ?: return
                    if (last >= adapters.getValue(page.pageTab).itemCount - 4) load(page.pageTab)
                }
            })
            return page
        }

        override fun onBindViewHolder(holder: Page, position: Int) {
            val value = Tab.entries[position]
            holder.pageTab = value
            recyclers[value] = holder.list
            holder.list.adapter = adapters.getValue(value)
            applyLayout(value)
        }
    }

    /** Media is the one tab with a grid; the others are always lists. */
    private fun applyLayout(value: Tab) {
        val list = recyclers[value] ?: return
        val grid = gridMode && value == Tab.MEDIA
        list.layoutManager = if (grid) GridLayoutManager(context, 3) else LinearLayoutManager(context)
        adapters.getValue(value).submit(states.getValue(value).items, grid)
    }

    /** The selected tab takes the link colour and an underline; the rest sit muted, as in the mobile client. */
    private fun styleTab(view: TextView, selected: Boolean) {
        view.isSelected = selected
        view.setTextColor(ThemedColor.resolve(view.context, if (selected) R.attr.colorTextLink else R.attr.colorInteractiveNormal))
    }

    /** The button shows the layout a tap switches to, so it reads differently in each mode. */
    private fun updateToggle() {
        binding.channelContentGridToggle.setImageResource(if (gridMode) R.drawable.ic_channel_content_list else R.drawable.ic_channel_content_grid)
        binding.channelContentGridToggle.contentDescription = getString(if (gridMode) R.string.list else R.string.grid)
    }

    /** Only the toggle rebuilds the media list's layout; changing tabs leaves each page as it was. */
    private fun configureLayout() {
        updateToggle()
        applyLayout(Tab.MEDIA)
    }

    private fun load(requestedTab: Tab = tab) {
        val state = states.getValue(requestedTab)
        if (state.loading || !state.canLoadMore) return
        val api = RestAPI.apiOrNull ?: return
        state.loading = true
        if (tab == requestedTab) renderState(state)
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
            adapters.getValue(requestedTab).submit(state.items, gridMode && requestedTab == Tab.MEDIA)
            if (tab == requestedTab) {
                renderState(state)
                loadUntilFilled(requestedTab)
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
                        val poster = embed.thumbnail?.proxyUrl ?: video.proxyUrl?.let { mediaVideoPreviewUrl(it, video.width, video.height) }
                        add(Item(stream, poster, embed.title, video = true, attachment = MessageAttachment(stream, 0, 0, stream, "video.${Uri.parse(stream).path?.substringAfterLast('.', "mp4")}", video.width, video.height, contentType = "video/mp4"), channelId = message.channelId, messageId = message.id, metadata = metadata))
                        return@forEach
                    }
                    embed.image?.url?.let { url ->
                        val proxy = embed.image.proxyUrl
                        add(Item(url, proxy, embed.title, attachment = MessageAttachment(url, 0, 0, proxy ?: url, "image.png", embed.image.width, embed.image.height, contentType = "image/png"), channelId = message.channelId, messageId = message.id, metadata = metadata))
                    } ?: embed.thumbnail?.url?.let { url ->
                        val proxy = embed.thumbnail.proxyUrl
                        add(Item(url, proxy, embed.title, attachment = MessageAttachment(url, 0, 0, proxy ?: url, "image.png", embed.thumbnail.width, embed.thumbnail.height, contentType = "image/png"), channelId = message.channelId, messageId = message.id, metadata = metadata))
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
                val tileSize = (binding.root.resources.displayMetrics.widthPixels - dp(34)) / 3
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
                // Only the avatar that is on screen: the other is hidden and would just decode too.
                avatar?.let {
                    MGImages.setImage(if (grid) binding.channelContentGridAvatar else binding.channelContentAvatar, it)
                }
                binding.channelContentAvatar.visibility = if (avatar == null) View.GONE else View.VISIBLE
                item.previewUrl?.let {
                    // Decode at tile size, and keep GIFs still: dozens of animating tiles at full
                    // resolution is what made a long scroll slow and then got the app killed.
                    val metrics = binding.root.resources.displayMetrics
                    MGImages.setImage(
                        binding.channelContentPreview,
                        mediaTilePreviewUrl(it, if (grid) tileSize * 2 else metrics.widthPixels),
                        width = if (grid) tileSize else metrics.widthPixels,
                        height = if (grid) tileSize else dp(180),
                        autoPlayAnimations = false,
                    )
                }
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

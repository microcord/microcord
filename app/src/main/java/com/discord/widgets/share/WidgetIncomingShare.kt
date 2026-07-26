package com.discord.widgets.share

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import com.discord.app.AppFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.stores.StoreStream
import com.discord.utilities.ShareUtils
import com.discord.utilities.messagesend.MessageSendCoordinator
import com.discord.widgets.chat.input.WidgetChatInput
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.textfield.TextInputLayout
import com.lytefast.flexinput.model.Attachment

class WidgetIncomingShare : AppFragment(R.layout.widget_incoming_share) {
    data class Candidate(val channelId: Long, val title: String, val subtitle: String? = null)

    data class Callbacks(
        val loadCandidates: () -> List<Candidate>,
        val send: (channelId: Long, message: String, uris: List<Uri>) -> Boolean,
        val onSent: () -> Unit = {},
    )

    private lateinit var callbacks: Callbacks
    private lateinit var content: ShareUtils.SharedContent
    private val candidateAdapter = CandidateAdapter(::select)
    private val attachmentAdapter = AttachmentAdapter()
    private var candidates = emptyList<Candidate>()
    private var selected: Candidate? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        content = ShareUtils.SharedContent(
            arguments?.getString(ARG_TEXT),
            arguments?.getString(ARG_SUBJECT),
            arguments?.getStringArrayList(ARG_URIS).orEmpty().map(Uri::parse),
            arguments?.getLong(ARG_DIRECT_SHARE_ID, 0L)?.takeIf { it > 0 },
        )
        callbacks = callbacksFactory?.invoke(requireContext()) ?: defaultCallbacks(requireContext())
        candidates = callbacks.loadCandidates()

        toolbar(view).apply {
            setNavigationOnClickListener { requireActivity().finish() }
            inflateMenu(R.menu.menu_external_share)
            setOnMenuItemClickListener {
                if (it.itemId != R.id.menu_send) false else send().let { true }
            }
        }
        view.findViewById<TextInputLayout>(R.id.external_share_comment).editText?.setText(content.message)
        view.findViewById<RecyclerView>(R.id.external_share_list).apply {
            adapter = attachmentAdapter
            layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        }
        attachmentAdapter.uris = content.uris
        view.findViewById<View>(R.id.external_share_list_wrap).visibility =
            if (content.uris.isEmpty()) View.GONE else View.VISIBLE
        view.findViewById<RecyclerView>(R.id.external_share_search_results).apply {
            adapter = candidateAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
        candidateAdapter.items = candidates
        view.findViewById<TextInputLayout>(R.id.external_share_search).editText?.addTextChangedListener(
            SimpleTextWatcher { candidateAdapter.items = filterCandidates(candidates, it) },
        )
        view.findViewById<View>(R.id.widget_channel_search_item_remove).setOnClickListener { select(null) }
        select(preselectedCandidate(candidates, content.directShareId))
    }

    private fun send() {
        val candidate = selected ?: return
        val message = requireView().findViewById<TextInputLayout>(R.id.external_share_comment)
            .editText?.text?.toString().orEmpty()
        if (message.isBlank() && content.uris.isEmpty()) return
        if (callbacks.send(candidate.channelId, message, content.uris)) {
            callbacks.onSent()
            requireActivity().finish()
        } else {
            Toast.makeText(requireContext(), R.string.error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun select(candidate: Candidate?) {
        selected = candidate
        requireView().findViewById<com.discord.app.AppViewFlipper>(R.id.search_flipper).displayedChild =
            if (candidate == null) 0 else 1
        candidate?.let {
            requireView().findViewById<TextView>(R.id.item_name_tv).text = it.title
            requireView().findViewById<TextView>(R.id.item_group_tv).apply {
                text = it.subtitle
                visibility = if (it.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun toolbar(root: View): Toolbar = ((root as ViewGroup).getChildAt(0) as AppBarLayout).getChildAt(0) as Toolbar

    private class CandidateAdapter(private val onClick: (Candidate) -> Unit) : RecyclerView.Adapter<TextHolder>() {
        var items = emptyList<Candidate>()
            set(value) { field = value; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = TextHolder(textView(parent))
        override fun onBindViewHolder(holder: TextHolder, position: Int) = holder.bind(
            items[position].let { if (it.subtitle.isNullOrBlank()) it.title else "${it.title}\n${it.subtitle}" },
            View.OnClickListener { onClick(items[position]) },
        )
    }

    private class AttachmentAdapter : RecyclerView.Adapter<TextHolder>() {
        var uris = emptyList<Uri>()
            set(value) { field = value; notifyDataSetChanged() }
        override fun getItemCount() = uris.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = TextHolder(textView(parent))
        override fun onBindViewHolder(holder: TextHolder, position: Int) = holder.bind(
            uris[position].lastPathSegment ?: uris[position].toString(), null,
        )
    }

    private class TextHolder(private val text: TextView) : RecyclerView.ViewHolder(text) {
        fun bind(value: String, click: View.OnClickListener?) {
            text.text = value
            text.contentDescription = value
            text.setOnClickListener(click)
        }
    }

    private class SimpleTextWatcher(private val changed: (String) -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = changed(s?.toString().orEmpty())
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }

    companion object {
        @JvmField var callbacksFactory: ((Context) -> Callbacks)? = null

        fun newInstance(content: ShareUtils.SharedContent) = WidgetIncomingShare().apply {
            arguments = bundleOf(
                ARG_TEXT to content.text,
                ARG_SUBJECT to content.subject,
                ARG_URIS to ArrayList(content.uris.map(Uri::toString)),
                ARG_DIRECT_SHARE_ID to (content.directShareId ?: 0L),
            )
        }

        internal fun filterCandidates(candidates: List<Candidate>, query: String): List<Candidate> {
            val value = query.trim()
            return if (value.isEmpty()) candidates else candidates.filter {
                it.title.contains(value, ignoreCase = true) || it.subtitle?.contains(value, ignoreCase = true) == true
            }
        }

        internal fun preselectedCandidate(candidates: List<Candidate>, directShareId: Long?) =
            directShareId?.let { id -> candidates.firstOrNull { it.channelId == id } }

        internal fun defaultCandidates(): List<Candidate> = runCatching {
            val channels = StoreStream.getChannels().run {
                getChannelsForGuild(0L).values + StoreStream.getGuilds().getGuilds().keys.flatMap { getChannelsForGuild(it).values }
            }
            val permissions = StoreStream.getPermissions().getPermissionsByChannel()
            val guilds = StoreStream.getGuilds().getGuilds()
            channels.filter { channel ->
                channel.type in SENDABLE_TYPES &&
                    channel.recipients.orEmpty().none { it.system == true } &&
                    (channel.guildId == 0L || permissions[channel.id]?.and(Permission.SEND_MESSAGES) != 0L)
            }.distinctBy(Channel::id).map { channel ->
                Candidate(
                    channel.id,
                    channel.name ?: channel.recipients.orEmpty().joinToString(", ") { it.username },
                    guilds[channel.guildId]?.name,
                )
            }.filter { it.title.isNotBlank() }.sortedWith(compareBy<Candidate> { it.subtitle.orEmpty() }.thenBy { it.title })
        }.getOrDefault(emptyList())

        private fun defaultCallbacks(context: Context) = Callbacks(
            loadCandidates = ::defaultCandidates,
            send = { channelId, message, uris ->
                val channel = StoreStream.getChannels().getChannel(channelId)
                if (channel == null) false else if (uris.isNotEmpty()) {
                    persistPermissions(context, uris)
                    WidgetChatInput.uploadHandler?.invoke(
                        WidgetChatInput.UploadRequest(
                            channelId,
                            message,
                            uris.map { Attachment(it.hashCode().toLong(), it, displayName(context, it), it) },
                            null,
                        ),
                    ) == true
                } else {
                    WidgetChatInput.coordinatorProvider()?.send(
                        MessageSendCoordinator.Send(
                            channelId = channelId,
                            guildId = channel.guildId.takeUnless { it == 0L },
                            content = message,
                            canSend = true,
                            systemDm = channel.type == Channel.DM && channel.recipients.orEmpty().any { it.system == true },
                        ),
                    ) is MessageSendCoordinator.Submission.Accepted
                }
            },
        )

        private fun persistPermissions(context: Context, uris: List<Uri>) = uris.forEach {
            runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }

        private fun displayName(context: Context, uri: Uri): String {
            var cursor: Cursor? = null
            return try {
                cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                if (cursor?.moveToFirst() == true) cursor.getString(0) else null
            } catch (_: RuntimeException) {
                null
            } finally {
                cursor?.close()
            } ?: uri.lastPathSegment ?: "attachment"
        }

        private fun textView(parent: ViewGroup) = TextView(parent.context).apply {
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            isFocusable = true
            isClickable = true
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        private val SENDABLE_TYPES = setOf(
            Channel.GUILD_TEXT, Channel.DM, Channel.GROUP_DM, Channel.GUILD_ANNOUNCEMENT,
            Channel.ANNOUNCEMENT_THREAD, Channel.PUBLIC_THREAD, Channel.PRIVATE_THREAD,
        )
        private const val ARG_TEXT = "text"
        private const val ARG_SUBJECT = "subject"
        private const val ARG_URIS = "uris"
        private const val ARG_DIRECT_SHARE_ID = "direct_share_id"
    }
}

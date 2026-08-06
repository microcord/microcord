package com.discord.widgets.channels.settings

import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.InputFilter
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.isVisible
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDeletedMessage
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.isAnnouncementChannel
import com.discord.api.channel.isCategory
import com.discord.api.channel.isGuildForumChannel
import com.discord.api.channel.isGuildTextyChannel
import com.discord.api.channel.isThread
import com.discord.api.channel.isThreadOnlyChannel
import com.discord.api.channel.isThreaded
import com.discord.api.guild.GuildFeature
import com.discord.api.permission.Permission
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.models.guild.Guild
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.views.CheckedSetting
import com.discord.widgets.chat.pins.WidgetChannelPinnedMessages
import com.discord.widgets.channels.permissions.WidgetChannelSettingsPermissionsOverview
import com.discord.widgets.forums.ForumLayout
import com.discord.widgets.forums.ForumPostTagView
import com.discord.widgets.forums.ForumSortOrder
import com.discord.widgets.forums.ForumTagSetting
import com.discord.widgets.forums.PostTagData
import com.discord.widgets.forums.WidgetForumTagEdit
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.settings.SettingsStrings
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputLayout
import rx.Observable

/** Edits guild text, announcement, forum, and category channels. */
class WidgetTextChannelSettings : AppFragment(R.layout.widget_text_channel_settings) {

    private lateinit var name: TextInputLayout
    private lateinit var topic: TextInputLayout
    private lateinit var slowMode: SeekBar
    private lateinit var slowModeLabel: TextView
    private lateinit var save: FloatingActionButton
    private var model: Model? = null
    private var savedName = ""
    private var savedTopic = ""
    private var savedSlowMode = 0
    private var savedArchiveDuration = DEFAULT_ARCHIVE_DURATION
    private var draftArchiveDuration = DEFAULT_ARCHIVE_DURATION
    private var bindingState = false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        name = view.findViewById(R.id.channel_settings_edit_name)
        topic = view.findViewById(R.id.channel_settings_edit_topic)
        slowMode = view.findViewById(R.id.channel_settings_slow_mode_cooldown_slider)
        slowModeLabel = view.findViewById(R.id.channel_settings_slow_mode_cooldown_label)
        save = view.findViewById(R.id.channel_settings_save)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateSaveVisibility()
        }
        name.editText?.addTextChangedListener(watcher)
        topic.editText?.addTextChangedListener(watcher)
        slowMode.max = SLOWMODE_COOLDOWN_VALUES.lastIndex
        slowMode.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!bindingState && fromUser) updateSlowModeLabel(SLOWMODE_COOLDOWN_VALUES[progress])
                updateSaveVisibility()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        save.setOnClickListener { saveChanges() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        Model.observe(channelId()).ui(this).appSubscribe(WidgetTextChannelSettings::class.java) {
            if (it == null) appActivity?.finish() else render(it)
        }
    }

    private fun render(next: Model) {
        val root = view ?: return
        model = next
        val channel = next.channel
        val category = channel.isCategory()
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(if (category) R.string.category_settings else R.string.channel_settings)
        setActionBarSubtitle(channel.getDisplayNameOrDefault(requireContext()))
        setActionBarOptionsMenu(
            if (category) R.menu.menu_category_settings else R.menu.menu_text_channel_settings,
            onSelected = { item ->
                when (item.itemId) {
                    R.id.menu_channel_settings_delete -> deleteChannel(next)
                    R.id.menu_channel_settings_reset ->
                        StoreStream.getUserGuildSettings().setChannelNotificationsDefault(requireContext(), channel)
                }
            },
            onConfigured = { menu ->
                menu.findItem(R.id.menu_channel_settings_delete)?.isVisible = next.canManageChannel
            },
        )

        bindingState = true
        if (savedName.isEmpty() || name.editText?.text?.toString() == savedName) {
            savedName = channel.name.orEmpty()
            name.editText?.setText(savedName)
        }
        if (savedTopic.isEmpty() || topic.editText?.text?.toString() == savedTopic) {
            savedTopic = channel.topic.orEmpty()
            topic.editText?.setText(savedTopic)
        }
        savedSlowMode = channel.rateLimitPerUser
        slowMode.progress = slowModeProgress(savedSlowMode)
        updateSlowModeLabel(savedSlowMode)
        savedArchiveDuration = channel.defaultAutoArchiveDuration ?: DEFAULT_ARCHIVE_DURATION
        draftArchiveDuration = savedArchiveDuration
        bindingState = false

        val isThreadOnly = channel.isThreadOnlyChannel()
        root.findViewById<LinearLayout>(R.id.channel_settings_edit_wrap).isVisible = next.canManageChannel
        topic.isVisible = !category
        topic.hint = getString(
            if (isThreadOnly) R.string.form_label_channel_guidelines else R.string.form_label_channel_topic,
        )
        topic.editText?.filters = arrayOf(
            InputFilter.LengthFilter(if (isThreadOnly) MAX_FORUM_TOPIC_LENGTH else MAX_TOPIC_LENGTH),
        )
        // Only a channel that holds messages itself has pins; a forum's posts carry their own.
        root.findViewById<LinearLayout>(R.id.channel_settings_pinned_messages_container).isVisible =
            channel.type == Channel.GUILD_TEXT || channel.isAnnouncementChannel()
        root.findViewById<TextView>(R.id.channel_settings_pinned_messages).apply {
            isEnabled = next.isPinsEnabled
            setOnClickListener { WidgetChannelPinnedMessages.show(context, channel.id) }
        }
        root.findViewById<View>(R.id.channel_settings_pinned_messages_disabled_overlay).apply {
            isVisible = !next.isPinsEnabled
            setOnClickListener { AppToast.show(context, R.string.pins_disabled_nsfw) }
        }
        root.findViewById<TextView>(R.id.channel_settings_permissions).apply {
            isVisible = next.canManagePermissions
            setOnClickListener { WidgetChannelSettingsPermissionsOverview.launch(context, channel.id) }
        }
        root.findViewById<LinearLayout>(R.id.channel_settings_section_user_management).isVisible =
            next.canManageChannel || next.canManagePermissions

        root.findViewById<CheckedSetting>(R.id.channel_settings_announcement).apply {
            isVisible = next.canToggleAnnouncement
            if (isVisible) {
                val help = SettingsStrings.render(
                    context,
                    R.string.form_help_news,
                    "documentationLink" to ANNOUNCEMENT_ARTICLE_URL,
                ).trim()
                setSubtext(
                    SpannableStringBuilder(help)
                        .append("\n\n")
                        .append(getString(R.string.guild_profile_announcement_channel_disclaimer)),
                    true,
                )
            }
            setOnCheckedListener(null)
            isChecked = channel.isAnnouncementChannel()
            setOnCheckedListener { checked -> saveImmediate(type = if (checked) Channel.GUILD_ANNOUNCEMENT else Channel.GUILD_TEXT) }
        }
        root.findViewById<LinearLayout>(R.id.channel_settings_section_privacy_safety).isVisible =
            next.canManageChannel && !category
        root.findViewById<CheckedSetting>(R.id.channel_settings_nsfw).apply {
            setOnCheckedListener(null)
            isChecked = channel.nsfw
            setOnCheckedListener { checked -> saveImmediate(nsfw = checked) }
        }
        root.findViewById<LinearLayout>(R.id.channel_settings_section_slow_mode).isVisible =
            next.canManageChannel && !category && !channel.isAnnouncementChannel()

        configureForumTags(root, next)
        configureForumDefaults(root, next)

        val archiveSection = root.findViewById<LinearLayout>(R.id.channel_settings_section_default_auto_archive_duration)
        archiveSection.isVisible = next.canManageChannel && channel.isThreaded()
        bindArchiveOption(R.id.option_one_hour, R.id.option_one_hour_radio, 60)
        bindArchiveOption(R.id.option_twenty_four_hours, R.id.option_twenty_four_hours_radio, 1_440)
        bindArchiveOption(R.id.option_three_days, R.id.option_three_days_radio, 4_320)
        bindArchiveOption(R.id.option_seven_days, R.id.option_seven_days_radio, 10_080)
        updateArchiveRadios()
        updateSaveVisibility()
    }

    private fun configureForumTags(root: View, model: Model) {
        val channel = model.channel
        val section = root.findViewById<LinearLayout>(R.id.channel_settings_section_forum_tags)
        section.isVisible = channel.isThreadOnlyChannel()
        if (!section.isVisible) return

        val tags = channel.availableTags.orEmpty()
        root.findViewById<TextView>(R.id.channel_settings_forum_tags_description).isVisible = tags.isEmpty()
        root.findViewById<TextView>(R.id.channel_settings_forum_tag_create).apply {
            isVisible = tags.isEmpty()
            isEnabled = model.canManageChannel
            setOnClickListener { WidgetForumTagEdit.launch(context, channel.id, null) }
        }

        val container = root.findViewById<FlexboxLayout>(R.id.channel_settings_forum_tags)
        container.isVisible = tags.isNotEmpty()
        container.removeAllViews()
        tags.forEach { tag ->
            val pill = ForumPostTagView(requireContext()).apply {
                configureTag(PostTagData.fromTag(tag))
                isEnabled = model.canManageChannel
                alpha = if (model.canManageChannel) 1f else 0.6f
                setOnClickListener {
                    if (model.canManageChannel) WidgetForumTagEdit.launch(context, channel.id, tag.id)
                }
            }
            container.addView(pill, tagLayoutParams())
        }
        if (model.canManageChannel && tags.size < WidgetForumTagEdit.MAX_FORUM_TAGS) {
            val add = ForumPostTagView(requireContext()).apply {
                configureAdd()
                setOnClickListener { WidgetForumTagEdit.launch(context, channel.id, null) }
            }
            container.addView(add, tagLayoutParams())
        }

        root.findViewById<CheckedSetting>(R.id.channel_settings_forum_require_tag).apply {
            setOnCheckedListener(null)
            isChecked = (channel.flags ?: 0L) and Channel.FLAG_REQUIRE_TAG != 0L
            // Nothing but moderator tags leaves a member with no way to satisfy the requirement.
            isEnabled = model.canManageChannel && !(tags.isNotEmpty() && tags.all { it.moderated })
            setOnCheckedListener { checked ->
                val flags = (channel.flags ?: 0L)
                saveImmediate(flags = if (checked) flags or Channel.FLAG_REQUIRE_TAG else flags and Channel.FLAG_REQUIRE_TAG.inv())
            }
        }
    }

    private fun tagLayoutParams() = FlexboxLayout.LayoutParams(
        FlexboxLayout.LayoutParams.WRAP_CONTENT,
        FlexboxLayout.LayoutParams.WRAP_CONTENT,
    ).apply { setMargins(dpToPixels(6), dpToPixels(6), dpToPixels(6), dpToPixels(6)) }

    private fun configureForumDefaults(root: View, model: Model) {
        val channel = model.channel
        val section = root.findViewById<LinearLayout>(R.id.channel_settings_section_forum_defaults)
        section.isVisible = channel.isThreadOnlyChannel() && model.canManageChannel
        if (!section.isVisible) return

        // A media channel is always a gallery, so it has no layout to choose.
        val showLayout = channel.isGuildForumChannel()
        val layoutList = root.findViewById<CheckedSetting>(R.id.channel_settings_forum_layout_list)
        val layoutGallery = root.findViewById<CheckedSetting>(R.id.channel_settings_forum_layout_gallery)
        listOf(
            root.findViewById<TextView>(R.id.channel_settings_forum_layout_header),
            root.findViewById<TextView>(R.id.channel_settings_forum_layout_description),
            layoutList,
            layoutGallery,
        ).forEach { it.isVisible = showLayout }
        layoutList.isChecked = channel.defaultForumLayout != ForumLayout.GALLERY.apiValue
        layoutGallery.isChecked = channel.defaultForumLayout == ForumLayout.GALLERY.apiValue
        layoutList.setContainerClickListener { saveImmediate(defaultForumLayout = ForumLayout.LIST.apiValue) }
        layoutGallery.setContainerClickListener { saveImmediate(defaultForumLayout = ForumLayout.GALLERY.apiValue) }

        val sortActivity = root.findViewById<CheckedSetting>(R.id.channel_settings_forum_sort_activity)
        val sortCreation = root.findViewById<CheckedSetting>(R.id.channel_settings_forum_sort_creation)
        sortCreation.isChecked = channel.defaultSortOrder == ForumSortOrder.CREATION_DATE.ordinal
        sortActivity.isChecked = !sortCreation.isChecked
        sortActivity.setContainerClickListener { saveImmediate(defaultSortOrder = ForumSortOrder.LATEST_ACTIVITY.ordinal) }
        sortCreation.setContainerClickListener { saveImmediate(defaultSortOrder = ForumSortOrder.CREATION_DATE.ordinal) }

        val matchAll = root.findViewById<CheckedSetting>(R.id.channel_settings_forum_tag_match_all)
        val matchSome = root.findViewById<CheckedSetting>(R.id.channel_settings_forum_tag_match_some)
        matchAll.isChecked = channel.defaultTagSetting == ForumTagSetting.MATCH_ALL.apiValue
        matchSome.isChecked = !matchAll.isChecked
        matchSome.setContainerClickListener { saveImmediate(defaultTagSetting = ForumTagSetting.MATCH_SOME.apiValue) }
        matchAll.setContainerClickListener { saveImmediate(defaultTagSetting = ForumTagSetting.MATCH_ALL.apiValue) }
    }

    private fun bindArchiveOption(rowId: Int, radioId: Int, minutes: Int) {
        view?.findViewById<View>(rowId)?.setOnClickListener {
            draftArchiveDuration = minutes
            updateArchiveRadios()
            updateSaveVisibility()
        }
        view?.findViewById<MaterialRadioButton>(radioId)?.isChecked = draftArchiveDuration == minutes
    }

    private fun updateArchiveRadios() {
        listOf(
            R.id.option_one_hour_radio to 60,
            R.id.option_twenty_four_hours_radio to 1_440,
            R.id.option_three_days_radio to 4_320,
            R.id.option_seven_days_radio to 10_080,
        ).forEach { (id, value) -> view?.findViewById<MaterialRadioButton>(id)?.isChecked = draftArchiveDuration == value }
    }

    private fun saveChanges() {
        val channel = model?.channel ?: return
        val currentName = name.editText?.text?.toString().orEmpty()
        val currentTopic = topic.editText?.text?.toString().orEmpty()
        val currentSlowMode = SLOWMODE_COOLDOWN_VALUES[slowMode.progress]
        RestAPI.api.editTextChannel(
            channel.id,
            RestAPIParams.TextChannel(
                name = currentName.takeIf { it != savedName },
                type = null,
                topic = currentTopic.takeIf { it != savedTopic },
                nsfw = null,
                rateLimitPerUser = currentSlowMode.takeIf { it != savedSlowMode },
                defaultAutoArchiveDuration = draftArchiveDuration.takeIf { it != savedArchiveDuration },
            ),
        ).restSubscribeOn().ui(this).appSubscribe(WidgetTextChannelSettings::class.java, context = context) {
            AppToast.show(context, if (it.isCategory()) R.string.category_settings else R.string.channel_settings)
            hideKeyboard()
        }
    }

    private fun saveImmediate(
        type: Int? = null,
        nsfw: Boolean? = null,
        flags: Long? = null,
        defaultForumLayout: Int? = null,
        defaultSortOrder: Int? = null,
        defaultTagSetting: String? = null,
    ) {
        val channel = model?.channel ?: return
        RestAPI.api.editTextChannel(
            channel.id,
            RestAPIParams.TextChannel(
                name = null,
                type = type,
                topic = null,
                nsfw = nsfw,
                rateLimitPerUser = null,
                defaultAutoArchiveDuration = null,
                flags = flags,
                defaultForumLayout = defaultForumLayout,
                defaultSortOrder = defaultSortOrder,
                defaultTagSetting = defaultTagSetting,
            ),
        ).restSubscribeOn().ui(this).appSubscribe(WidgetTextChannelSettings::class.java, context = context) { }
    }

    private fun deleteChannel(model: Model) {
        if (model.isProtectedCommunityChannel) {
            AppToast.show(context, R.string.cannot_delete_channel)
            return
        }
        WidgetNoticeDialog.show(
            fragmentManager = parentFragmentManager,
            headerText = getString(if (model.channel.isCategory()) R.string.delete_category else R.string.delete_channel),
            bodyText = SettingsStrings.render(
                requireContext(),
                R.string.delete_channel_body,
                "channelName" to model.channel.getDisplayNameOrDefault(requireContext()),
            ),
            okText = getString(R.string.delete),
            cancelText = getString(R.string.cancel),
            listenerMap = mapOf(R.id.notice_ok to { _: View ->
                RestAPI.api.deleteChannel(model.channel.id).restSubscribeOn().ui(this)
                    .appSubscribe(WidgetTextChannelSettings::class.java, context = context) { deleted ->
                        deleted.getDeletedMessage()?.let { AppToast.show(context, it) }
                        appActivity?.finish()
                    }
            }),
            dialogTheme = R.attr.notice_theme_positive_red,
        )
    }

    private fun updateSlowModeLabel(seconds: Int) {
        slowModeLabel.text = durationLabel(requireContext(), seconds)
        slowMode.contentDescription = slowModeLabel.text
    }

    private fun updateSaveVisibility() {
        if (bindingState || !::save.isInitialized) return
        save.isVisible = name.editText?.text?.toString().orEmpty() != savedName ||
            topic.editText?.text?.toString().orEmpty() != savedTopic ||
            SLOWMODE_COOLDOWN_VALUES[slowMode.progress] != savedSlowMode ||
            draftArchiveDuration != savedArchiveDuration
    }

    private fun channelId() = mostRecentIntent.getLongExtra(ARG_CHANNEL_ID, -1L)

    internal data class Model(
        val guild: Guild,
        val channel: Channel,
        val canManageChannel: Boolean,
        val canManagePermissions: Boolean,
        val isPinsEnabled: Boolean,
        val isProtectedCommunityChannel: Boolean,
    ) {
        /** Only a plain text channel can become an announcement channel, and only back again. */
        val canToggleAnnouncement: Boolean = canManageChannel && guild.hasFeature(GuildFeature.NEWS) &&
            !isProtectedCommunityChannel &&
            (channel.type == Channel.GUILD_TEXT || channel.isAnnouncementChannel())

        companion object {
            fun observe(channelId: Long): Observable<Model?> = StoreStream.getChannels().observeChannel(channelId)
                .switchMap { channel ->
                    if (channel == null || channel.isThread() || (!channel.isGuildTextyChannel() && !channel.isCategory())) {
                        Observable.just(null)
                    } else {
                        Observable.combineLatest(
                            StoreStream.getGuilds().observeGuild(channel.guildId),
                            StoreStream.getUsers().observeMe(),
                            StoreStream.getPermissions().observePermissionsForChannel(channel.id),
                        ) { guild, me, permissions ->
                            guild?.let {
                                val protected = it.hasFeature(GuildFeature.COMMUNITY) &&
                                    (it.rulesChannelId == channel.id || it.publicUpdatesChannelId == channel.id)
                                Model(
                                    it,
                                    channel,
                                    PermissionUtils.canAndIsElevated(Permission.MANAGE_CHANNELS, permissions, me.mfaEnabled, it.mfaLevel),
                                    PermissionUtils.canAndIsElevated(Permission.MANAGE_ROLES, permissions, me.mfaEnabled, it.mfaLevel),
                                    !channel.nsfw,
                                    protected,
                                )
                            }
                        }
                    }
                }.computationLatest().distinctUntilChanged()
        }
    }

    companion object {
        const val ARG_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        const val DEFAULT_ARCHIVE_DURATION = 1_440
        private const val ANNOUNCEMENT_ARTICLE_URL = "https://support.discord.com/hc/en-us/articles/360032008192"
        private const val MAX_TOPIC_LENGTH = 1_024
        private const val MAX_FORUM_TOPIC_LENGTH = 4_096
        val SLOWMODE_COOLDOWN_VALUES = listOf(0, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1_800, 3_600, 7_200, 21_600)

        @JvmStatic
        fun launch(channelId: Long, context: Context) = AppScreenRouter.launch(
            context,
            WidgetTextChannelSettings::class.java,
            Intent().putExtra(ARG_CHANNEL_ID, channelId),
        )

        internal fun slowModeProgress(seconds: Int): Int =
            SLOWMODE_COOLDOWN_VALUES.indexOfFirst { it >= seconds }.takeIf { it >= 0 } ?: SLOWMODE_COOLDOWN_VALUES.lastIndex

        internal fun durationLabel(context: Context, seconds: Int): CharSequence = when {
            seconds == 0 -> context.getString(R.string.form_label_slowmode_off)
            seconds < 60 -> SettingsStrings.render(context, R.string.duration_seconds, "seconds" to seconds)
            seconds < 3_600 -> SettingsStrings.render(context, R.string.duration_minutes, "minutes" to seconds / 60)
            seconds < 86_400 -> SettingsStrings.render(context, R.string.duration_hours, "hours" to seconds / 3_600)
            else -> SettingsStrings.render(context, R.string.duration_days, "days" to seconds / 86_400)
        }
    }
}

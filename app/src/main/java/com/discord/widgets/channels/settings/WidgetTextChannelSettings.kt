package com.discord.widgets.channels.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.descendants
import androidx.core.view.isVisible
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDeletedMessage
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.isAnnouncementChannel
import com.discord.api.channel.isCategory
import com.discord.api.channel.isGuildForumChannel
import com.discord.api.channel.isGuildMediaChannel
import com.discord.api.channel.isGuildTextyChannel
import com.discord.api.channel.isSpoilerChannel
import com.discord.api.channel.isThread
import com.discord.api.channel.isThreadOnlyChannel
import com.discord.api.channel.isThreaded
import com.discord.api.guild.GuildFeature
import com.discord.api.permission.Permission
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.databinding.WidgetTextChannelSettingsBinding
import com.discord.dialogs.SelectorDialog
import com.discord.models.guild.Guild
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelGating
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.resources.DurationUnit
import com.discord.utilities.resources.formatDuration
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.channels.permissions.WidgetChannelSettingsPermissionsOverview
import com.discord.widgets.chat.list.actions.MessageMenuGroup
import com.discord.widgets.chat.pins.WidgetChannelPinnedMessages
import com.discord.widgets.forums.ForumLayout
import com.discord.widgets.forums.ForumPostTagView
import com.discord.widgets.forums.ForumSortOrder
import com.discord.widgets.forums.ForumTagSetting
import com.discord.widgets.forums.PostTagData
import com.discord.widgets.forums.WidgetForumTagEdit
import com.discord.widgets.integrations.WidgetChannelSettingsIntegrations
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.servers.community.WidgetServerSettingsCommunityOverview
import com.discord.widgets.servers.settings.invites.WidgetServerSettingsInstantInvites
import com.discord.widgets.settings.SettingsStrings
import com.google.android.flexbox.FlexboxLayout
import rx.Observable

/** Edits guild text, announcement, forum, and category channels. */
class WidgetTextChannelSettings : AppFragment() {

    /** The modern layout shares every id with the legacy one, so one binding drives both. */
    private val modern by lazy { !FeatureFlags.legacyUiEnabled() }
    private var _binding: WidgetTextChannelSettingsBinding? = null
    private val binding get() = checkNotNull(_binding)
    private var model: Model? = null
    private var savedName = ""
    private var savedTopic = ""
    private var savedSlowMode = 0
    private var savedMessageSlowMode = 0
    private var savedArchiveDuration = DEFAULT_ARCHIVE_DURATION
    private var draftArchiveDuration = DEFAULT_ARCHIVE_DURATION
    private var savedVisibility = ContentVisibility.DEFAULT
    private var draftVisibility = ContentVisibility.DEFAULT
    private var savedIsAnnouncement = false
    private var draftIsAnnouncement = false
    private var savedRequireTag = false
    private var draftRequireTag = false
    private var savedForumLayout = ForumLayout.LIST
    private var draftForumLayout = ForumLayout.LIST
    private var savedSortOrder = ForumSortOrder.LATEST_ACTIVITY
    private var draftSortOrder = ForumSortOrder.LATEST_ACTIVITY
    private var savedTagSetting = ForumTagSetting.MATCH_SOME
    private var draftTagSetting = ForumTagSetting.MATCH_SOME
    private var savedParentId = 0L
    private var draftParentId = 0L
    private var savedHideMediaDownloads = false
    private var draftHideMediaDownloads = false
    private var bindingState = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(
            if (modern) R.layout.widget_text_channel_settings_modern else R.layout.widget_text_channel_settings,
            container,
            false,
        )

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetTextChannelSettingsBinding.bind(view)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateSaveVisibility()
        }
        binding.channelSettingsEditName.editText?.addTextChangedListener(watcher)
        binding.channelSettingsEditTopic.editText?.addTextChangedListener(watcher)
        bindSlider(binding.channelSettingsSlowModeCooldownSlider, binding.channelSettingsSlowModeCooldownLabel)
        bindSlider(binding.channelSettingsMessageSlowModeSlider, binding.channelSettingsMessageSlowModeValue)
        binding.channelSettingsSave.setOnClickListener { saveChanges() }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun bindSlider(slider: SeekBar, valueLabel: TextView) {
        slider.max = SLOWMODE_COOLDOWN_VALUES.lastIndex
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!bindingState && fromUser) {
                    setSliderValueLabel(slider, valueLabel, SLOWMODE_COOLDOWN_VALUES[progress])
                }
                updateSaveVisibility()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        Model.observe(channelId()).ui(this).appSubscribe(WidgetTextChannelSettings::class.java) {
            if (it == null) appActivity?.finish() else render(it)
        }
    }

    private fun render(next: Model) {
        if (_binding == null) return
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
                // The modern screen has its own Delete row.
                menu.findItem(R.id.menu_channel_settings_delete)?.isVisible = next.canManageChannel && !modern
            },
        )

        bindingState = true
        if (savedName.isEmpty() || binding.channelSettingsEditName.editText?.text?.toString() == savedName) {
            savedName = channel.name.orEmpty()
            binding.channelSettingsEditName.editText?.setText(savedName)
        }
        if (savedTopic.isEmpty() || binding.channelSettingsEditTopic.editText?.text?.toString() == savedTopic) {
            savedTopic = channel.topic.orEmpty()
            binding.channelSettingsEditTopic.editText?.setText(savedTopic)
        }
        savedSlowMode = channel.rateLimitPerUser
        binding.channelSettingsSlowModeCooldownSlider.progress = slowModeProgress(savedSlowMode)
        setSliderValueLabel(binding.channelSettingsSlowModeCooldownSlider, binding.channelSettingsSlowModeCooldownLabel, savedSlowMode)
        savedMessageSlowMode = channel.defaultThreadRateLimitPerUser ?: 0
        binding.channelSettingsMessageSlowModeSlider.progress = slowModeProgress(savedMessageSlowMode)
        setSliderValueLabel(binding.channelSettingsMessageSlowModeSlider, binding.channelSettingsMessageSlowModeValue, savedMessageSlowMode)
        savedArchiveDuration = channel.defaultAutoArchiveDuration ?: DEFAULT_ARCHIVE_DURATION
        draftArchiveDuration = savedArchiveDuration
        savedVisibility = ContentVisibility.of(channel)
        draftVisibility = savedVisibility
        savedIsAnnouncement = channel.isAnnouncementChannel()
        draftIsAnnouncement = savedIsAnnouncement
        savedRequireTag = (channel.flags ?: Channel.FLAGS_NONE) and Channel.FLAG_REQUIRE_TAG != 0L
        draftRequireTag = savedRequireTag
        savedForumLayout = ForumLayout.fromApiValue(channel.defaultForumLayout) ?: ForumLayout.LIST
        draftForumLayout = savedForumLayout
        savedSortOrder = ForumSortOrder.entries.getOrNull(channel.defaultSortOrder ?: 0)
            ?: ForumSortOrder.LATEST_ACTIVITY
        draftSortOrder = savedSortOrder
        savedTagSetting = ForumTagSetting.fromApiValue(channel.defaultTagSetting)
        draftTagSetting = savedTagSetting
        savedParentId = channel.parentId
        draftParentId = savedParentId
        savedHideMediaDownloads =
            (channel.flags ?: Channel.FLAGS_NONE) and Channel.FLAG_HIDE_MEDIA_DOWNLOAD_OPTIONS != 0L
        draftHideMediaDownloads = savedHideMediaDownloads
        bindingState = false

        val isThreadOnly = channel.isThreadOnlyChannel()
        binding.channelSettingsEditWrap.isVisible = next.canManageChannel
        binding.channelSettingsEditTopic.isVisible = !category
        binding.channelSettingsEditTopic.hint = getString(
            if (isThreadOnly) R.string.form_label_channel_guidelines else R.string.form_label_channel_topic,
        )
        binding.channelSettingsEditTopic.editText?.filters = arrayOf(
            InputFilter.LengthFilter(if (isThreadOnly) MAX_FORUM_TOPIC_LENGTH else MAX_TOPIC_LENGTH),
        )
        binding.channelSettingsPinnedMessagesContainer.isVisible =
            channel.type == Channel.GUILD_TEXT || channel.isAnnouncementChannel()
        binding.channelSettingsPinnedMessages.apply {
            isEnabled = next.isPinsEnabled
            setOnClickListener { WidgetChannelPinnedMessages.show(context, channel.id) }
        }
        binding.channelSettingsPinnedMessagesDisabledOverlay.apply {
            isVisible = !next.isPinsEnabled
            setOnClickListener { AppToast.show(context, R.string.pins_disabled_nsfw) }
        }
        binding.channelSettingsPermissions.apply {
            isVisible = next.canManagePermissions
            setOnClickListener { WidgetChannelSettingsPermissionsOverview.launch(context, channel.id) }
        }
        // The modern section is only the Permissions card and its caption.
        binding.channelSettingsSectionUserManagement.isVisible =
            next.canManagePermissions || (!modern && next.canManageChannel)
        binding.channelSettingsSectionIntegrations.isVisible =
            next.canManageWebhooks && !category
        binding.channelSettingsIntegrations.setOnClickListener {
            WidgetChannelSettingsIntegrations.launch(requireContext(), channel.id)
        }

        val announcementHelp = if (next.canToggleAnnouncement) {
            SpannableStringBuilder(
                SettingsStrings.render(
                    requireContext(),
                    R.string.form_help_news,
                    "documentationLink" to ANNOUNCEMENT_ARTICLE_URL,
                ).trim(),
            )
                .append("\n\n")
                .append(getString(R.string.guild_profile_announcement_channel_disclaimer))
        } else null
        // The modern screen puts the help above the switch's card, as React Native does.
        binding.channelSettingsAnnouncementHelp.apply {
            isVisible = modern && announcementHelp != null
            text = announcementHelp
            movementMethod = LinkMovementMethod.getInstance()
        }
        binding.channelSettingsAnnouncement.apply {
            isVisible = next.canToggleAnnouncement
            if (isVisible && !modern) setSubtext(announcementHelp, true)
            setOnCheckedListener(null)
            isChecked = draftIsAnnouncement
            setOnCheckedListener { checked ->
                draftIsAnnouncement = checked
                updateSaveVisibility()
            }
        }
        binding.channelSettingsSectionPrivacySafety.isVisible =
            next.canManageChannel && !category
        configureContentVisibility()
        binding.channelSettingsSectionSlowMode.isVisible =
            next.canManageChannel && !category && !channel.isAnnouncementChannel()
        binding.channelSettingsSlowmodeLabel.setText(
            if (isThreadOnly) R.string.forum_setting_slow_mode_posts else R.string.form_label_slowmode_cooldown,
        )
        binding.channelSettingsSlowModeHelp.setText(
            if (isThreadOnly) R.string.form_help_slowmode_forum else R.string.form_help_slowmode,
        )
        binding.channelSettingsSectionMessageSlowMode.isVisible = isThreadOnly

        configureForumTags(next)
        configureForumDefaults(next)
        configureCategory(next)

        binding.channelSettingsSectionInvites.isVisible =
            next.canManageChannel && !category
        binding.channelSettingsInvites.setOnClickListener {
            WidgetServerSettingsInstantInvites.launchForChannel(requireContext(), channel.guildId, channel.id)
        }

        binding.channelSettingsSectionMediaDownload.isVisible =
            next.canManageChannel && channel.isGuildMediaChannel()
        binding.channelSettingsMediaDownload.apply {
            setOnCheckedListener(null)
            isChecked = !draftHideMediaDownloads
            setOnCheckedListener { checked ->
                draftHideMediaDownloads = !checked
                updateSaveVisibility()
            }
        }

        val archiveSection = binding.channelSettingsSectionDefaultAutoArchiveDuration
        archiveSection.isVisible = next.canManageChannel && channel.isThreaded()
        // The section above already ends in one when slow mode is hidden, as it is on announcements.
        binding.channelSettingsAutoArchiveDivider.isVisible =
            binding.channelSettingsSectionSlowMode.isVisible
        bindArchiveOptions()
        updateArchiveRadios()

        binding.channelSettingsEditTopicLabel.setText(
            if (isThreadOnly) R.string.form_label_channel_guidelines else R.string.form_label_channel_topic,
        )
        binding.channelSettingsEditTopicLabel.isVisible = modern && !category
        binding.channelSettingsNotifications.apply {
            isVisible = modern
            setOnClickListener { WidgetChannelNotificationSettings.launch(context, channel.id) }
        }
        binding.channelSettingsDelete.apply {
            isVisible = modern && next.canManageChannel
            setText(if (category) R.string.delete_category else R.string.delete_channel)
            setOnClickListener { deleteChannel(next) }
        }
        if (modern) {
            (binding.scrollView.getChildAt(0) as ViewGroup).descendants
                .filterIsInstance<MessageMenuGroup>().forEach(MessageMenuGroup::refresh)
        }
        updateSaveVisibility()
    }

    private fun configureForumTags(model: Model) {
        val channel = model.channel
        val section = binding.channelSettingsSectionForumTags
        section.isVisible = channel.isThreadOnlyChannel()
        if (!section.isVisible) return

        val tags = channel.availableTags.orEmpty()
        binding.channelSettingsForumTagsDescription.isVisible = tags.isEmpty()
        binding.channelSettingsForumTagCreate.apply {
            isVisible = tags.isEmpty()
            isEnabled = model.canManageChannel
            setOnClickListener { WidgetForumTagEdit.launch(context, channel.id, null) }
        }

        val container = binding.channelSettingsForumTags
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

        binding.channelSettingsForumRequireTag.apply {
            setOnCheckedListener(null)
            isChecked = draftRequireTag
            isEnabled = model.canManageChannel && !(tags.isNotEmpty() && tags.all { it.moderated })
            setOnCheckedListener { checked ->
                draftRequireTag = checked
                updateSaveVisibility()
            }
        }
    }

    private fun configureCategory(model: Model) {
        val channel = model.channel
        val categories = model.categories
        val section = binding.channelSettingsSectionCategory
        section.isVisible = !channel.isCategory() && !channel.isThread() &&
            model.canManageChannel && categories.isNotEmpty()
        if (!section.isVisible) return

        val labels = listOf(getString(R.string.uncategorized)) +
            categories.map { it.getDisplayNameOrDefault(requireContext()) }
        val ids = listOf(0L) + categories.map { it.id }
        binding.channelSettingsCategoryValue.text =
            labels[ids.indexOf(draftParentId).coerceAtLeast(0)]
        binding.channelSettingsCategory.setOnClickListener {
            SelectorDialog.show(
                childFragmentManager,
                getString(R.string.category),
                labels.toTypedArray(),
            ) { position ->
                draftParentId = ids[position]
                binding.channelSettingsCategoryValue.text = labels[position]
                updateSaveVisibility()
            }
        }
    }

    /** Default / Spoiler / Age-Restricted are one choice: a channel cannot be two of them at once. */
    private fun configureContentVisibility() {
        visibilityRows().forEach { (row, visibility) ->
            row.setContainerClickListener {
                draftVisibility = visibility
                updateContentVisibilityRadios()
                updateSaveVisibility()
            }
        }
        updateContentVisibilityRadios()
    }

    private fun updateContentVisibilityRadios() {
        if (_binding == null) return
        visibilityRows().forEach { (row, visibility) -> row.isChecked = draftVisibility == visibility }
        // The modern rows carry no subtext; the chosen option's description sits under the card.
        binding.channelSettingsVisibilityDescription.apply {
            isVisible = modern
            setText(
                when (draftVisibility) {
                    ContentVisibility.DEFAULT -> R.string.channel_content_visibility_default_description
                    ContentVisibility.SPOILER -> R.string.spoiler_channel_description
                    ContentVisibility.AGE_RESTRICTED -> R.string.form_help_nsfw
                },
            )
        }
    }

    private fun visibilityRows() = listOf(
        binding.channelSettingsVisibilityDefault to ContentVisibility.DEFAULT,
        binding.channelSettingsSpoiler to ContentVisibility.SPOILER,
        binding.channelSettingsNsfw to ContentVisibility.AGE_RESTRICTED,
    )

    private fun tagLayoutParams() = FlexboxLayout.LayoutParams(
        FlexboxLayout.LayoutParams.WRAP_CONTENT,
        FlexboxLayout.LayoutParams.WRAP_CONTENT,
    ).apply { setMargins(dpToPixels(6), dpToPixels(6), dpToPixels(6), dpToPixels(6)) }

    private fun configureForumDefaults(model: Model) {
        val channel = model.channel
        val section = binding.channelSettingsSectionForumDefaults
        section.isVisible = channel.isThreadOnlyChannel() && model.canManageChannel
        if (!section.isVisible) return

        val showLayout = channel.isGuildForumChannel()
        val layoutList = binding.channelSettingsForumLayoutList
        val layoutGallery = binding.channelSettingsForumLayoutGallery
        listOf(
            binding.channelSettingsForumLayoutHeader,
            binding.channelSettingsForumLayoutDescription,
            layoutList,
            layoutGallery,
        ).forEach { it.isVisible = showLayout }
        val sortActivity = binding.channelSettingsForumSortActivity
        val sortCreation = binding.channelSettingsForumSortCreation
        val matchSome = binding.channelSettingsForumTagMatchSome
        val matchAll = binding.channelSettingsForumTagMatchAll

        fun renderForumRadios() {
            layoutList.isChecked = draftForumLayout == ForumLayout.LIST
            layoutGallery.isChecked = draftForumLayout == ForumLayout.GALLERY
            sortActivity.isChecked = draftSortOrder == ForumSortOrder.LATEST_ACTIVITY
            sortCreation.isChecked = draftSortOrder == ForumSortOrder.CREATION_DATE
            matchSome.isChecked = draftTagSetting == ForumTagSetting.MATCH_SOME
            matchAll.isChecked = draftTagSetting == ForumTagSetting.MATCH_ALL
        }

        fun stage(change: () -> Unit) {
            change()
            renderForumRadios()
            updateSaveVisibility()
        }

        renderForumRadios()
        layoutList.setContainerClickListener { stage { draftForumLayout = ForumLayout.LIST } }
        layoutGallery.setContainerClickListener { stage { draftForumLayout = ForumLayout.GALLERY } }
        sortActivity.setContainerClickListener { stage { draftSortOrder = ForumSortOrder.LATEST_ACTIVITY } }
        sortCreation.setContainerClickListener { stage { draftSortOrder = ForumSortOrder.CREATION_DATE } }
        matchSome.setContainerClickListener { stage { draftTagSetting = ForumTagSetting.MATCH_SOME } }
        matchAll.setContainerClickListener { stage { draftTagSetting = ForumTagSetting.MATCH_ALL } }
    }

    private fun archiveOptions() = with(binding.durationSelector) {
        listOf(
            Triple(optionOneHour, optionOneHourRadio, 60),
            Triple(optionTwentyFourHours, optionTwentyFourHoursRadio, 1_440),
            Triple(optionThreeDays, optionThreeDaysRadio, 4_320),
            Triple(optionSevenDays, optionSevenDaysRadio, 10_080),
        )
    }

    private fun bindArchiveOptions() = archiveOptions().forEach { (row, _, minutes) ->
        row.setOnClickListener {
            draftArchiveDuration = minutes
            updateArchiveRadios()
            updateSaveVisibility()
        }
    }

    private fun updateArchiveRadios() =
        archiveOptions().forEach { (_, radio, minutes) -> radio.isChecked = draftArchiveDuration == minutes }

    private fun saveChanges() {
        val channel = model?.channel ?: return
        val currentName = binding.channelSettingsEditName.editText?.text?.toString().orEmpty()
        val currentTopic = binding.channelSettingsEditTopic.editText?.text?.toString().orEmpty()
        val currentSlowMode = SLOWMODE_COOLDOWN_VALUES[binding.channelSettingsSlowModeCooldownSlider.progress]
        val currentMessageSlowMode = SLOWMODE_COOLDOWN_VALUES[binding.channelSettingsMessageSlowModeSlider.progress]
        RestAPI.api.editTextChannel(
            channel.id,
            RestAPIParams.TextChannel(
                name = currentName.takeIf { it != savedName },
                type = (if (draftIsAnnouncement) Channel.GUILD_ANNOUNCEMENT else Channel.GUILD_TEXT)
                    .takeIf { draftIsAnnouncement != savedIsAnnouncement },
                topic = currentTopic.takeIf { it != savedTopic },
                nsfw = (draftVisibility == ContentVisibility.AGE_RESTRICTED)
                    .takeIf { draftVisibility != savedVisibility },
                rateLimitPerUser = currentSlowMode.takeIf { it != savedSlowMode },
                defaultAutoArchiveDuration = draftArchiveDuration.takeIf { it != savedArchiveDuration },
                defaultThreadRateLimitPerUser = currentMessageSlowMode.takeIf { it != savedMessageSlowMode },
                flags = draftFlags(channel).takeIf { it != (channel.flags ?: Channel.FLAGS_NONE) },
                defaultForumLayout = draftForumLayout.apiValue.takeIf { draftForumLayout != savedForumLayout },
                defaultSortOrder = draftSortOrder.ordinal.takeIf { draftSortOrder != savedSortOrder },
                defaultTagSetting = draftTagSetting.apiValue.takeIf { draftTagSetting != savedTagSetting },
            ),
        ).restSubscribeOn().ui(this).appSubscribe(WidgetTextChannelSettings::class.java, context = context) {
            AppToast.show(context, if (it.isCategory()) R.string.category_settings else R.string.channel_settings)
            hideKeyboard()
        }
        if (draftParentId != savedParentId) moveToCategory(channel)
    }

    /** `parent_id` only accepts an explicit null through the reorder route, so a move goes there. */
    private fun moveToCategory(channel: Channel) {
        RestAPI.api.reorderChannels(
            channel.guildId,
            listOf(
                RestAPIParams.ChannelPosition(
                    id = channel.id,
                    position = channel.position,
                    parentId = draftParentId.takeIf { it != 0L } ?: RestAPIParams.ChannelPosition.PARENT_ID_NONE,
                ),
            ),
        ).restSubscribeOn().ui(this).appSubscribe(WidgetTextChannelSettings::class.java, context = context) {
            savedParentId = draftParentId
        }
    }

    /** Spoiler, require-tag and media downloads share one field, so every draft folds in together. */
    private fun draftFlags(channel: Channel): Long {
        var flags = draftVisibility.flags(channel.flags ?: Channel.FLAGS_NONE)
        flags = if (draftRequireTag) {
            flags or Channel.FLAG_REQUIRE_TAG
        } else {
            flags and Channel.FLAG_REQUIRE_TAG.inv()
        }
        return if (draftHideMediaDownloads) {
            flags or Channel.FLAG_HIDE_MEDIA_DOWNLOAD_OPTIONS
        } else {
            flags and Channel.FLAG_HIDE_MEDIA_DOWNLOAD_OPTIONS.inv()
        }
    }

    private fun deleteChannel(model: Model) {
        if (model.isProtectedCommunityChannel) {
            val isRules = model.guild.rulesChannelId == model.channel.id
            WidgetNoticeDialog.show(
                fragmentManager = parentFragmentManager,
                headerText = getString(R.string.cannot_delete_channel),
                bodyText = SpannableStringBuilder(
                    getString(
                        if (isRules) R.string.delete_rules_channel_body else R.string.delete_updates_channel_body,
                    ),
                )
                    .append("\n\n")
                    .append(
                        SettingsStrings.render(
                            requireContext(),
                            R.string.mobile_designate_other_channel,
                            onLink = { WidgetServerSettingsCommunityOverview.launch(requireContext(), model.guild.id) },
                        ),
                    ),
                okText = getString(R.string.okay),
            )
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

    private fun setSliderValueLabel(slider: SeekBar, valueLabel: TextView, seconds: Int) {
        valueLabel.text = durationLabel(requireContext(), seconds)
        slider.contentDescription = valueLabel.text
    }

    private fun updateSaveVisibility() {
        if (bindingState || _binding == null) return
        binding.channelSettingsSave.isVisible = binding.channelSettingsEditName.editText?.text?.toString().orEmpty() != savedName ||
            binding.channelSettingsEditTopic.editText?.text?.toString().orEmpty() != savedTopic ||
            SLOWMODE_COOLDOWN_VALUES[binding.channelSettingsSlowModeCooldownSlider.progress] != savedSlowMode ||
            SLOWMODE_COOLDOWN_VALUES[binding.channelSettingsMessageSlowModeSlider.progress] != savedMessageSlowMode ||
            draftArchiveDuration != savedArchiveDuration ||
            draftVisibility != savedVisibility ||
            draftIsAnnouncement != savedIsAnnouncement ||
            draftRequireTag != savedRequireTag ||
            draftForumLayout != savedForumLayout ||
            draftSortOrder != savedSortOrder ||
            draftTagSetting != savedTagSetting ||
            draftParentId != savedParentId ||
            draftHideMediaDownloads != savedHideMediaDownloads
    }

    private fun channelId() = mostRecentIntent.getLongExtra(ARG_CHANNEL_ID, -1L)

    internal data class Model(
        val guild: Guild,
        val channel: Channel,
        val canManageChannel: Boolean,
        val canManagePermissions: Boolean,
        val canManageWebhooks: Boolean,
        val isPinsEnabled: Boolean,
        val isProtectedCommunityChannel: Boolean,
        val categories: List<Channel>,
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
                            StoreStream.getChannels().observeChannelsForGuild(channel.guildId, Channel.CATEGORY),
                        ) { guild, me, permissions, categories ->
                            guild?.let {
                                val protected = it.hasFeature(GuildFeature.COMMUNITY) &&
                                    (it.rulesChannelId == channel.id || it.publicUpdatesChannelId == channel.id)
                                Model(
                                    it,
                                    channel,
                                    PermissionUtils.canAndIsElevated(Permission.MANAGE_CHANNELS, permissions, me.mfaEnabled, it.mfaLevel),
                                    PermissionUtils.canAndIsElevated(Permission.MANAGE_ROLES, permissions, me.mfaEnabled, it.mfaLevel),
                                    PermissionUtils.canAndIsElevated(Permission.MANAGE_WEBHOOKS, permissions, me.mfaEnabled, it.mfaLevel),
                                    !ChannelGating.isChannelContentGated(channel, me),
                                    protected,
                                    categories.values.sortedBy { category -> category.position },
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
            seconds < 60 -> context.formatDuration(DurationUnit.SECONDS, seconds)
            seconds < 3_600 -> context.formatDuration(DurationUnit.MINUTES, seconds / 60)
            seconds < 86_400 -> context.formatDuration(DurationUnit.HOURS, seconds / 3_600)
            else -> context.formatDuration(DurationUnit.DAYS, seconds / 86_400)
        }
    }
}

/** The one content gate a channel can carry; the three are mutually exclusive. */
internal enum class ContentVisibility {
    DEFAULT,
    SPOILER,
    AGE_RESTRICTED,
    ;

    fun flags(existing: Long): Long = if (this == SPOILER) {
        existing or Channel.FLAG_IS_SPOILER_CHANNEL
    } else {
        existing and Channel.FLAG_IS_SPOILER_CHANNEL.inv()
    }

    companion object {
        fun of(channel: Channel): ContentVisibility = when {
            channel.isSpoilerChannel() -> SPOILER
            channel.nsfw -> AGE_RESTRICTED
            else -> DEFAULT
        }
    }
}

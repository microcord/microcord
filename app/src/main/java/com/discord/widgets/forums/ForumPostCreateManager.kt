package com.discord.widgets.forums

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.app.AppToast
import com.discord.models.domain.NonceGenerator
import com.discord.restapi.RestAPIParams
import com.discord.utilities.attachments.AttachmentLimits
import com.discord.restapi.PayloadJSON
import com.discord.stores.StoreStream
import com.discord.stores.StoreSlowMode
import com.discord.stores.StoreThreadDraft.ThreadDraftState
import com.discord.utilities.attachments.UploadAttachment
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rest.SendUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.filterIs
import com.discord.utilities.rx.filterNull
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.takeSingleUntilTimeout
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils.isPremium
import com.discord.utilities.user.UserUtils.isPremiumTier2
import rx.Observable
import rx.subjects.BehaviorSubject

object ForumPostCreateManager {
    data class AttachmentsRequest(
        val sizes: List<Long>,
        val maxFileSize: Long,
        val attachments: List<UploadAttachment>? = null,
    )

    private val isCreateInProgress = BehaviorSubject.create(false)

    @JvmStatic
    @JvmOverloads
    fun createForumPostWithMessage(
        context: Context,
        forumChannelId: Long,
        autoArchiveDuration: Int,
        content: String,
        threadDraftState: ThreadDraftState,
        attachmentsRequest: AttachmentsRequest? = null,
        onMessageTooLong: ((Int, Int) -> Unit)? = null,
        onFilesTooLarge: ((AttachmentLimits.Violation, Boolean) -> Unit)? = null,
    ): Boolean {
        if (isCreateInProgress.value == true) return false
        val permissions = StoreStream.getPermissions().getPermissionsByChannel()[forumChannelId]
        if (!ForumUtils.canCreatePost(permissions)) {
            AppToast.show(context, R.string.guild_event_location_disabled_no_permission)
            return false
        }
        val threadName = threadDraftState.threadName
        if (threadName.isNullOrEmpty()) {
            StoreStream.getThreadDraft().setDraftState(threadDraftState.copy(shouldDisplayNameError = true))
            return false
        }
        val channel = StoreStream.getChannels().getChannel(forumChannelId)
        val requiresTag = (channel?.flags ?: 0L) and Channel.FLAG_REQUIRE_TAG != 0L
        if (requiresTag && threadDraftState.appliedTags.isEmpty()) {
            AppToast.show(context, R.string.forum_post_tag_required_error)
            return false
        }

        val me = StoreStream.getUsers().getMe()
        if (!isValidFirstMessage(
                content,
                attachmentsRequest,
                me.isPremiumTier2,
                me.isPremium,
                onMessageTooLong,
                onFilesTooLarge,
            )
        ) return false

        isCreateInProgress.onNext(true)
        sendCreateForumPostWithMessageRequest(
            context,
            forumChannelId,
            threadName,
            content,
            attachmentsRequest?.attachments,
            autoArchiveDuration,
            threadDraftState.appliedTags,
        ).restSubscribeOn()
            .flatMap { channel ->
                StoreStream.handleThreadCreateOrUpdate(channel)
                StoreStream.getChannels().observeChannel(channel.id).filterNull().takeSingleUntilTimeout()
            }
            .ui()
            .appSubscribe(
                errorClass = ForumPostCreateManager::class.java,
                context = context,
                errorHandler = { error ->
                    // The upload is refused outright rather than the post being created without it.
                    if (error.code == EXPLICIT_CONTENT_CODE) {
                        AlertDialog.Builder(context)
                            .setTitle(R.string.upload_area_upload_failed_title)
                            .setMessage(R.string.sender_blocked_media_bot_guild_message_v2)
                            .setPositiveButton(R.string.dismiss, null)
                            .show()
                    }
                    if (error.code == THREAD_CREATE_RATE_LIMIT_CODE) {
                        StoreStream.getSlowMode().onCooldown(
                            forumChannelId,
                            error.retryAfterMs ?: 0,
                            StoreSlowMode.Type.ThreadCreate,
                        )
                    }
                },
                onTerminated = { isCreateInProgress.onNext(false) },
            ) { channel ->
                StoreStream.getSlowMode().onThreadCreated(forumChannelId)
                ChannelSelector.getInstance().selectChannel(channel)
            }
        return true
    }

    internal fun isValidFirstMessage(
        content: String,
        attachmentsRequest: AttachmentsRequest?,
        isPremiumTier2: Boolean,
        isPremium: Boolean,
        onMessageTooLong: ((Int, Int) -> Unit)? = null,
        onFilesTooLarge: ((AttachmentLimits.Violation, Boolean) -> Unit)? = null,
    ): Boolean {
        val maxMessageLength = if (isPremiumTier2) {
            SendUtils.MAX_MESSAGE_CHARACTER_COUNT_PREMIUM
        } else {
            SendUtils.MAX_MESSAGE_CHARACTER_COUNT
        }
        if (content.length > maxMessageLength) {
            onMessageTooLong?.invoke(content.length, maxMessageLength)
            return false
        }
        if (attachmentsRequest != null && !attachmentsRequest.attachments.isNullOrEmpty()) {
            val violation =
                AttachmentLimits.validate(attachmentsRequest.sizes, attachmentsRequest.maxFileSize)
            if (violation != null) {
                onFilesTooLarge?.invoke(violation, isPremium)
                return false
            }
        }
        return true
    }

    private fun sendCreateForumPostWithMessageRequest(
        context: Context,
        channelId: Long,
        name: String,
        content: String,
        attachments: List<UploadAttachment>?,
        autoArchiveDuration: Int,
        appliedTags: List<Long>,
    ): Observable<Channel> {
        val message = RestAPIParams.Message(
            content = content,
            nonce = NonceGenerator.computeNonce().toString(),
            stickerIds = emptyList(),
        )
        return SendUtils.getSendPayload(context.contentResolver, message, attachments)
            .filterIs<SendUtils.SendPayload.ReadyToSend>()
            .take(1)
            .flatMap { payload ->
                val api = RestAPI.apiOrNull
                    ?: return@flatMap Observable.error<Channel>(IllegalStateException("API unavailable"))
                api.createForumPost(
                    channelId,
                    PayloadJSON(forumPostBody(name, autoArchiveDuration, payload.message, appliedTags)),
                    payload.uploads.mapNotNull { it.part }.toTypedArray(),
                )
            }
    }

    /**
     * Puts the media the viewer just picked onto the post's opening message.
     *
     * The reference offers this because a forum row shows a thumbnail taken from that first
     * message, so media sent later would otherwise never reach the browser.
     */
    @JvmStatic
    fun addMediaToOriginalPost(
        context: Context,
        channelId: Long,
        firstMessageId: Long,
        content: String,
        attachments: List<UploadAttachment>,
        onDone: () -> Unit,
    ) {
        val message = RestAPIParams.Message(
            content = content,
            nonce = NonceGenerator.computeNonce().toString(),
            stickerIds = emptyList(),
        )
        SendUtils.getSendPayload(context.contentResolver, message, attachments)
            .filterIs<SendUtils.SendPayload.ReadyToSend>()
            .take(1)
            .flatMap { payload ->
                val api = RestAPI.apiOrNull
                    ?: return@flatMap Observable.error<com.discord.api.message.Message>(IllegalStateException("API unavailable"))
                api.editMessageWithAttachments(
                    channelId,
                    firstMessageId,
                    PayloadJSON(payload.message),
                    payload.uploads.mapNotNull { it.part }.toTypedArray(),
                )
            }
            .restSubscribeOn()
            .ui()
            .appSubscribe(
                errorClass = ForumPostCreateManager::class.java,
                context = context,
                onTerminated = onDone,
            ) { }
    }

    internal fun forumPostBody(
        name: String,
        autoArchiveDuration: Int,
        message: RestAPIParams.Message,
        appliedTags: List<Long> = emptyList(),
    ) = RestAPIParams.ForumThreadCreation(
        name = name,
        autoArchiveDuration = autoArchiveDuration,
        appliedTags = appliedTags,
        message = message,
    )

    private const val THREAD_CREATE_RATE_LIMIT_CODE = 20016
    private const val EXPLICIT_CONTENT_CODE = 200000
}

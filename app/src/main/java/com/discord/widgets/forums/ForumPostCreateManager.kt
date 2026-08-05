package com.discord.widgets.forums

import android.content.Context
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.app.AppToast
import com.discord.models.domain.NonceGenerator
import com.discord.restapi.RestAPIParams
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
        val currentFileSizeMB: Float,
        val maxFileSizeMB: Int,
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
        onFilesTooLarge: ((Int, Boolean) -> Unit)? = null,
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
        onFilesTooLarge: ((Int, Boolean) -> Unit)? = null,
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
        if (attachmentsRequest != null &&
            !attachmentsRequest.attachments.isNullOrEmpty() &&
            attachmentsRequest.currentFileSizeMB >= attachmentsRequest.maxFileSizeMB
        ) {
            onFilesTooLarge?.invoke(attachmentsRequest.maxFileSizeMB, isPremium)
            return false
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
                    PayloadJSON(forumPostBody(name, autoArchiveDuration, payload.message)),
                    payload.uploads.map { it.part }.toTypedArray(),
                )
            }
    }

    internal fun forumPostBody(
        name: String,
        autoArchiveDuration: Int,
        message: RestAPIParams.Message,
    ) = RestAPIParams.ForumThreadCreation(
        name = name,
        autoArchiveDuration = autoArchiveDuration,
        message = message,
    )

    private const val THREAD_CREATE_RATE_LIMIT_CODE = 20016
}

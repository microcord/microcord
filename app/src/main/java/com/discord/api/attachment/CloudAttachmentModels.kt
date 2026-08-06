package com.discord.api.attachment

import com.google.gson.annotations.SerializedName

/**
 * One file the client is about to upload.
 *
 * [id] is the client's own handle for the file, echoed back so a batch response can be matched to
 * its request; it is unrelated to the snowflake the finished attachment gets.
 */
data class UploadAttachmentRequest(
    val id: String,
    val filename: String,
    @SerializedName("file_size") val fileSize: Long,
)

data class CreateAttachmentsBody(val files: List<UploadAttachmentRequest>)

/**
 * Where to PUT one file.
 *
 * [uploadUrl] is a presigned Google Cloud Storage URL that already carries its own credentials, and
 * [uploadFilename] is the handle the message-create call uses to claim the finished upload.
 */
data class CloudAttachment(
    val id: String,
    @SerializedName("upload_url") val uploadUrl: String,
    @SerializedName("upload_filename") val uploadFilename: String,
)

data class CreateAttachmentsResponse(val attachments: List<CloudAttachment>)

data class RefreshAttachmentUrlsBody(
    @SerializedName("attachment_urls") val attachmentUrls: List<String>,
)

data class RefreshedAttachment(val original: String, val refreshed: String)

data class RefreshAttachmentUrlsResponse(
    @SerializedName("refreshed_urls") val refreshedUrls: List<RefreshedAttachment>,
)

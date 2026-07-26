package com.discord.media_picker

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Parcelable
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException

/**
 * Builds the "choose an image" chooser.
 *
 * The platform chooser for a single action only ever offers apps that handle *that* action, so a
 * chooser built from `ACTION_GET_CONTENT` alone would never list the camera. This assembles one
 * explicit intent per resolving activity across three actions and hands the extras to
 * [Intent.createChooser], which is the only way to get gallery apps, document providers and the
 * camera into the same sheet.
 *
 * Entries are keyed by package name, so an app that answers more than one action appears once. The
 * later actions win, which is deliberate: a package that can both hand back content *and* capture
 * gets listed as the capture, because that is the more specific capability.
 *
 * @throws IOException when the device has no app that can supply an image at all.
 */
@Throws(IOException::class)
internal fun buildImageChooserIntent(
    packageManager: PackageManager,
    chooserTitle: CharSequence?,
    captureFileURI: Uri,
    mimeType: String,
): Intent {
    val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        .putExtra(MediaStore.EXTRA_OUTPUT, captureFileURI)
        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    val pick = Intent(Intent.ACTION_PICK).apply {
        type = mimeType
        putExtra(Intent.EXTRA_MIME_TYPES, mimeType)
    }

    val getContent = Intent(Intent.ACTION_GET_CONTENT).apply {
        type = mimeType
        putExtra(Intent.EXTRA_MIME_TYPES, mimeType)
    }

    val intentsByPackage = LinkedHashMap<String, Intent>()
    intentsByPackage.addResolvedIntents(packageManager, getContent)
    intentsByPackage.addResolvedIntents(packageManager, pick)
    // The camera needs to be told where to write; the other two hand back a URI of their own.
    intentsByPackage.addResolvedIntents(packageManager, capture) { intent ->
        intent.putExtra(MediaStore.EXTRA_OUTPUT, captureFileURI)
    }

    val intents = intentsByPackage.values.toList()
    if (intents.isEmpty()) throw IOException("No media applications available on this device.")

    // createChooser needs one intent to describe the sheet and the rest as extras.
    return Intent.createChooser(intents.first(), chooserTitle).apply {
        val remaining = intents.drop(1)
        if (remaining.isNotEmpty()) {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, remaining.toTypedArray<Parcelable>())
        }
    }
}

/**
 * Adds one explicit copy of [intent] per activity that can handle it, keyed by package name.
 *
 * The copies are explicit — component *and* package set — because `EXTRA_INITIAL_INTENTS` entries
 * are launched directly rather than re-resolved.
 */
private fun MutableMap<String, Intent>.addResolvedIntents(
    packageManager: PackageManager,
    intent: Intent,
    configure: ((Intent) -> Unit)? = null,
) {
    packageManager.queryIntentActivities(intent, 0).forEach { resolveInfo ->
        val activityInfo = resolveInfo.activityInfo
        val explicit = Intent(intent).apply {
            component = ComponentName(activityInfo.packageName, activityInfo.name)
            setPackage(activityInfo.packageName)
        }
        configure?.invoke(explicit)
        put(activityInfo.packageName, explicit)
    }
}

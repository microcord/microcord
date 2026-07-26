package com.discord.widgets.contact_sync

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.discord.app.AppLog

/**
 * Reads the device address book for contact sync.
 *
 * This is the reference's `com.discord.utilities.contacts.ContactsProviderUtils`. That package does
 * not exist in this reconstruction and is owned by the utilities wave, so the port lives here for
 * now — **move it to `com/discord/utilities/contacts/` when that package lands** and keep the
 * interface, which is what makes the flow testable without a real content provider.
 *
 * PRIVACY CONTRACT — this is the only class in the app that touches the user's address book:
 *  - Nothing read here is ever logged. The failure paths below log the *fact* of a failure and the
 *    exception type, never a row, a number, or a name.
 *  - The returned values go straight into `RestAPIParams.UploadContacts`, whose `toString` reports
 *    only the entry count.
 *  - No method returns a contact's name except [ownName], which looks up a single number the user
 *    themselves just typed, in order to prefill their own display name.
 */
interface ContactsProvider {

    /** True when `READ_CONTACTS` has already been granted. */
    fun hasContactPermissions(context: Context): Boolean

    /** Every normalised phone number in the address book. Empty when permission is missing. */
    fun allContactPhoneNumbers(context: Context): Set<String>

    /** The address-book display name stored against [number], used to prefill the name field. */
    fun ownName(context: Context, number: String): String?
}

/** The real [ContactsProvider], backed by [ContactsContract]. */
object AndroidContactsProvider : ContactsProvider {

    override fun hasContactPermissions(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Reads `Phone.NORMALIZED_NUMBER` (`data4`), which is the E.164 form the server matches on.
     * Rows without one — a contact saved as a local, unnormalised string — are skipped, matching the
     * reference; they simply cannot be matched against another account's number.
     */
    override fun allContactPhoneNumbers(context: Context): Set<String> {
        val numbers = LinkedHashSet<String>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null,
                null,
                null,
                null,
            )?.use { cursor ->
                val column = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)
                if (column < 0) return@use
                while (cursor.moveToNext()) {
                    cursor.getString(column)?.let(numbers::add)
                }
            }
        } catch (failure: Exception) {
            // No contact data in the message: only that the read failed and how.
            AppLog.w("Contact lookup failed (${failure.javaClass.simpleName})")
        }
        return numbers
    }

    override fun ownName(context: Context, number: String): String? {
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(number),
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let { return it }
                }
                null
            }
        } catch (failure: Exception) {
            AppLog.w("Contact name lookup failed (${failure.javaClass.simpleName})")
            null
        }
    }
}

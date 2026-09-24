package com.guftugu.app.ui.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.guftugu.app.BuildConfig
import com.guftugu.app.R
import com.guftugu.app.protocol.Invite
import java.text.DateFormat
import java.util.Date

/**
 * Sending an invite: a friendly message with the code, shared through any app the person
 * chooses (WhatsApp, SMS, email…) or texted to one phone contact picked with the system picker
 * (no contacts permission: the picker hands us just that one number).
 */
object InviteShare {

    fun message(context: Context, invite: Invite, groupName: String?): String {
        val expires = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(invite.expiresAt))
        val intro = if (groupName != null) context.getString(R.string.invite_msg_group, groupName)
        else context.getString(R.string.invite_msg_friend)
        val lines = mutableListOf(intro, "", context.getString(R.string.invite_msg_code, invite.code, expires))
        if (BuildConfig.DOWNLOAD_URL.isNotEmpty()) lines += context.getString(R.string.invite_msg_download, BuildConfig.DOWNLOAD_URL)
        lines += context.getString(R.string.invite_msg_steps)
        return lines.joinToString("\n")
    }

    /** Android share sheet: WhatsApp, SMS, email, … */
    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val chooser = Intent.createChooser(send, context.getString(R.string.invite_share_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
    }

    /** SMS to one number, message pre-filled. */
    fun sms(context: Context, phone: String, text: String) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phone)))
            .putExtra("sms_body", text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { share(context, text) }
    }
}

/**
 * Launches the system phone-number picker; [onPicked] gets the chosen number (or nothing if the
 * person backed out). Returns the launch function.
 */
@Composable
fun rememberPhoneContactPicker(onPicked: (phone: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult
        val phone = runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        if (!phone.isNullOrBlank()) onPicked(phone)
    }
    return {
        runCatching { launcher.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }
    }
}

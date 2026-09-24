package com.guftugu.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.guftugu.app.R
import com.guftugu.app.ui.media.AttachmentPickers
import com.guftugu.app.ui.theme.Brushes
import com.guftugu.app.ui.theme.GoldButton
import com.guftugu.app.ui.theme.Ornament

/** Attach menu: a small grid of round river-blue targets with gold hairlines. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(pickers: AttachmentPickers, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Ornament(Modifier.padding(bottom = 14.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                AttachOption(Icons.Outlined.Image, stringResource(R.string.attach_photo)) { onDismiss(); pickers.pickImage() }
                AttachOption(Icons.Outlined.Movie, stringResource(R.string.attach_video)) { onDismiss(); pickers.pickVideo() }
                AttachOption(Icons.Outlined.PhotoCamera, stringResource(R.string.attach_camera_photo)) { onDismiss(); pickers.captureImage() }
                AttachOption(Icons.Outlined.Videocam, stringResource(R.string.attach_camera_video)) { onDismiss(); pickers.captureVideo() }
                AttachOption(Icons.Outlined.Description, stringResource(R.string.attach_file)) { onDismiss(); pickers.pickFile() }
            }
        }
    }
}

@Composable
private fun AttachOption(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.width(64.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(1.dp, Brushes.goldSoft, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
    }
}

/** Long-press actions for one message: Reply · Copy (text) · Delete (mine). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionSheet(
    message: MessageUi,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).navigationBarsPadding()) {
            SheetAction(Icons.AutoMirrored.Outlined.Reply, stringResource(R.string.chat_action_reply)) { onDismiss(); onReply() }
            if (message.copyText != null) {
                SheetAction(Icons.Outlined.ContentCopy, stringResource(R.string.chat_action_copy)) { onDismiss(); onCopy() }
            }
            if (message.isMine && message.body !is BubbleBody.Deleted) {
                SheetAction(Icons.Outlined.Delete, stringResource(R.string.chat_action_delete), tint = MaterialTheme.colorScheme.error) { onDismiss(); onDelete() }
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint, modifier = Modifier.padding(start = 18.dp))
    }
}

@Composable
fun DeleteMessageDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_delete_title), style = MaterialTheme.typography.headlineSmall) },
        text = { Text(stringResource(R.string.chat_delete_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.chat_delete_confirm), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/** Small caption prompt shown after picking a photo/video. */
@Composable
fun CaptionDialog(pending: PendingMedia, onSend: (String) -> Unit, onCancel: () -> Unit) {
    var caption by rememberSaveable(pending.uri) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.caption_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                Text(
                    pending.fileName ?: stringResource(if (pending.type == com.guftugu.app.protocol.ContentType.VIDEO) R.string.attach_video else R.string.attach_photo),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    placeholder = { Text(stringResource(R.string.caption_hint)) },
                    maxLines = 3,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = { GoldButton(stringResource(R.string.caption_send), onClick = { onSend(caption) }) },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

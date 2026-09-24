package com.guftugu.app.ui.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Runtime permissions for calls: the microphone is required, the camera is optional (a video
 * call can be joined with the camera off).
 *
 * Usage from any screen (chat call buttons, incoming call):
 * ```
 * val request = rememberCallPermissionRequester { mic, camera -> if (mic) start(...) }
 * Button(onClick = { request(video = true) })
 * ```
 */
object CallPermissions {
    fun hasMicrophone(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun hasCamera(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun required(video: Boolean): Array<String> =
        if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)

    /** Deep link to this app's settings page, for the "denied permanently" case. */
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Returns a function `request(video)` that resolves immediately when everything is already
 * granted, otherwise shows the system dialog and reports through [onResult] `(micGranted, cameraGranted)`.
 */
@Composable
fun rememberCallPermissionRequester(onResult: (micGranted: Boolean, cameraGranted: Boolean) -> Unit): (video: Boolean) -> Unit {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val mic = result[Manifest.permission.RECORD_AUDIO] ?: CallPermissions.hasMicrophone(context)
        val camera = result[Manifest.permission.CAMERA] ?: CallPermissions.hasCamera(context)
        latest.value(mic, camera)
    }
    return remember(launcher) {
        { video: Boolean ->
            val mic = CallPermissions.hasMicrophone(context)
            val camera = CallPermissions.hasCamera(context)
            if (mic && (!video || camera)) {
                latest.value(true, camera)
            } else {
                launcher.launch(CallPermissions.required(video))
            }
        }
    }
}

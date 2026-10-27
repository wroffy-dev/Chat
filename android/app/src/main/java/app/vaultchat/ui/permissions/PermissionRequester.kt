package app.vaultchat.ui.permissions

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.vaultchat.domain.permissions.AppPermission
import app.vaultchat.domain.permissions.PermissionStatus
import app.vaultchat.domain.permissions.PermissionStatusResolver

/**
 * Remembers whether the app has asked for a permission before. Android does not expose this,
 * and it is needed to tell "not asked yet" from "permanently denied". Not sensitive.
 */
class PermissionPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("permission_requests", Context.MODE_PRIVATE)

    fun wasRequested(permission: AppPermission): Boolean = prefs.getBoolean(permission.name, false)

    fun markRequested(permission: AppPermission) {
        prefs.edit().putBoolean(permission.name, true).apply()
    }
}

@Stable
class PermissionRequest internal constructor(
    val permission: AppPermission,
    status: PermissionStatus,
) {
    var status: PermissionStatus by mutableStateOf(status)
        internal set

    internal var launch: () -> Unit = {}

    /** Shows the system dialog. Call after explaining why (when status says showRationale). */
    fun request() = launch()
}

/**
 * Permission handling for features that need camera, microphone, notifications or Bluetooth.
 * The status refreshes on every resume, so changes made in system settings are picked up.
 */
@Composable
fun rememberPermissionRequest(
    permission: AppPermission,
    preferences: PermissionPreferences,
    onResult: (PermissionStatus) -> Unit = {},
): PermissionRequest {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    fun currentStatus(): PermissionStatus = resolveStatus(context, activity, permission, preferences)

    val request = remember(permission) { PermissionRequest(permission, currentStatus()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        preferences.markRequested(permission)
        request.status = currentStatus()
        onResult(request.status)
    }
    request.launch = {
        if (permission.isRuntimePermissionOn(Build.VERSION.SDK_INT)) {
            launcher.launch(permission.manifestName)
        } else {
            onResult(PermissionStatus.Granted)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        request.status = currentStatus()
    }
    return request
}

private fun resolveStatus(
    context: Context,
    activity: Activity?,
    permission: AppPermission,
    preferences: PermissionPreferences,
): PermissionStatus {
    if (!permission.isRuntimePermissionOn(Build.VERSION.SDK_INT)) return PermissionStatus.Granted
    val granted = ContextCompat.checkSelfPermission(context, permission.manifestName) ==
        PackageManager.PERMISSION_GRANTED
    val rationale = activity?.let {
        ActivityCompat.shouldShowRequestPermissionRationale(it, permission.manifestName)
    } ?: false
    return PermissionStatusResolver.resolve(granted, rationale, preferences.wasRequested(permission))
}

/** Opens this app's system settings page, the only place a permanently denied permission can be granted. */
fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

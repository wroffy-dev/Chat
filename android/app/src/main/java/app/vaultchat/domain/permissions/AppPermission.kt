package app.vaultchat.domain.permissions

/**
 * Runtime permissions the app will need, and the Android version that introduced each one.
 * Calling features declare and request them in Phase 3 (docs/08-calling.md §10.1).
 */
enum class AppPermission(val manifestName: String, val minSdk: Int) {
    CAMERA("android.permission.CAMERA", 23),
    MICROPHONE("android.permission.RECORD_AUDIO", 23),
    NOTIFICATIONS("android.permission.POST_NOTIFICATIONS", 33),
    BLUETOOTH_CONNECT("android.permission.BLUETOOTH_CONNECT", 31);

    /** Below [minSdk] the permission does not exist and is treated as granted. */
    fun isRuntimePermissionOn(sdkInt: Int): Boolean = sdkInt >= minSdk
}

sealed interface PermissionStatus {
    data object Granted : PermissionStatus
    /** Not granted; asking will show the system dialog (possibly after a rationale). */
    data class Requestable(val showRationale: Boolean) : PermissionStatus
    /** Denied permanently ("Don't ask again"); only the app settings screen can change it. */
    data object PermanentlyDenied : PermissionStatus
}

object PermissionStatusResolver {
    /**
     * Android exposes only "granted" and "should show rationale", so a permanent denial is
     * inferred: not granted, no rationale, and the app has asked before.
     */
    fun resolve(
        granted: Boolean,
        shouldShowRationale: Boolean,
        previouslyRequested: Boolean,
    ): PermissionStatus = when {
        granted -> PermissionStatus.Granted
        shouldShowRationale -> PermissionStatus.Requestable(showRationale = true)
        previouslyRequested -> PermissionStatus.PermanentlyDenied
        else -> PermissionStatus.Requestable(showRationale = false)
    }
}

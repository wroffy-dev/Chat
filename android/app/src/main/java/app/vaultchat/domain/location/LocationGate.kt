package app.vaultchat.domain.location

/**
 * What stands between the user and the app (owner decision D-06, docs/11): the app is used only while
 * the user shares their location with the administrator. Checked in this order.
 */
enum class LocationBlock {
    /** Sharing is on, permission granted and the phone's location is on. */
    NONE,
    /** The user has not agreed (or turned sharing off, or the explanation changed). */
    CONSENT,
    /** Agreed, but Android's location permission is not granted (or was revoked). */
    PERMISSION,
    /** The phone's own location setting is off, so no position can be read. */
    DEVICE_OFF,
}

object LocationGate {
    /**
     * [sharingOn]: the server's answer, or the last known one when offline; null if never known
     * (then the user has not agreed on this phone yet).
     */
    fun decide(sharingOn: Boolean?, permissionGranted: Boolean, deviceLocationOn: Boolean): LocationBlock = when {
        sharingOn != true -> LocationBlock.CONSENT
        !permissionGranted -> LocationBlock.PERMISSION
        !deviceLocationOn -> LocationBlock.DEVICE_OFF
        else -> LocationBlock.NONE
    }
}

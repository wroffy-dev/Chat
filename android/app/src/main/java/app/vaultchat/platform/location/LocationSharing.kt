package app.vaultchat.platform.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import app.vaultchat.data.location.LocationApi
import app.vaultchat.data.location.LocationStateDto
import app.vaultchat.data.network.ApiResult
import app.vaultchat.domain.location.LocationBlock
import app.vaultchat.domain.location.LocationGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.coroutines.resume

data class LocationUi(
    val block: LocationBlock,
    /** When the server last received this phone's location. */
    val lastSentAt: Instant? = null,
    /** When the administrator last looked at it (shown to the user, D-06). */
    val lastViewedByAdminAt: Instant? = null,
    /** True while the phone's position is being read and sent. */
    val sending: Boolean = false,
    /** The last attempt to read a position found none (e.g. indoors with GPS only). */
    val unavailable: Boolean = false,
)

/**
 * Location sharing with the administrator (owner decision D-06, docs/11). The user agrees once; from
 * then on the app reads one position each time it is opened (never in the background) and sends it.
 * Without sharing on, Android's permission and the phone's location, the app is blocked
 * ([LocationGate]). The last server answer is cached per user so the app opens offline.
 */
class LocationSharing(
    context: Context,
    private val api: LocationApi,
    private val scope: CoroutineScope,
    private val currentUserId: () -> String?,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("location_sharing", Context.MODE_PRIVATE)
    private val manager: LocationManager? = appContext.getSystemService(LocationManager::class.java)
    private val lock = Mutex()

    private val _state = MutableStateFlow(LocationUi(block = decide()))
    val state: StateFlow<LocationUi> = _state.asStateFlow()

    fun hasPermission(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun precise(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    fun deviceLocationOn(): Boolean = manager?.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.isLocationEnabled else it.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } ?: false

    /** Re-reads permission and the phone's setting (e.g. after returning from Android's settings). */
    fun recheck() {
        _state.value = _state.value.copy(block = decide())
    }

    /** Each time the app comes to the front: ask the server, then send a fresh position if allowed. */
    fun onAppOpened() {
        recheck()
        scope.launch { lock.withLock { syncAndSend() } }
    }

    /** The user agreed on the consent screen (permission already granted). */
    suspend fun enable(consentVersion: Int): Boolean = lock.withLock {
        when (val r = api.enable(consentVersion, precise())) {
            is ApiResult.Ok -> {
                apply(r.value)
                send()
                true
            }
            else -> false
        }
    }

    /** Turned off in the menu: the server deletes the stored position; the app is blocked again. */
    suspend fun disable(): Boolean = lock.withLock {
        when (val r = api.disable()) {
            is ApiResult.Ok -> {
                apply(r.value)
                true
            }
            else -> false
        }
    }

    /** The server's current consent version, needed on the consent screen; null when offline. */
    suspend fun consentVersion(): Int? = (api.state() as? ApiResult.Ok)?.value?.currentConsentVersion

    private suspend fun syncAndSend() {
        when (val r = api.state()) {
            is ApiResult.Ok -> apply(r.value)
            else -> recheck() // Offline: keep the last known answer.
        }
        if (_state.value.block == LocationBlock.NONE) send()
    }

    private suspend fun send() {
        if (!hasPermission() || !deviceLocationOn()) return
        _state.value = _state.value.copy(sending = true, unavailable = false)
        val fix = currentFix()
        if (fix == null) {
            _state.value = _state.value.copy(sending = false, unavailable = true)
            return
        }
        val captured = Instant.ofEpochMilli(fix.time).let { if (it.isAfter(Instant.now())) Instant.now() else it }
        val accuracy = if (fix.hasAccuracy() && fix.accuracy > 0f) fix.accuracy.toDouble() else 1_000.0
        when (val r = api.report(fix.latitude, fix.longitude, accuracy, captured.toString(), precise())) {
            is ApiResult.Ok -> apply(r.value)
            // Turned off from another phone, or the explanation changed: ask again.
            is ApiResult.HttpError -> if (r.code == "SHARING_DISABLED") { cacheSharing(false); recheck() }
            ApiResult.NetworkError -> Unit
        }
        _state.value = _state.value.copy(sending = false)
    }

    private fun apply(dto: LocationStateDto) {
        cacheSharing(dto.enabled)
        _state.value = _state.value.copy(
            block = decide(),
            lastSentAt = dto.lastSentAt?.let(::parse),
            lastViewedByAdminAt = dto.lastViewedByAdminAt?.let(::parse),
        )
    }

    private fun decide(): LocationBlock = LocationGate.decide(cachedSharingOn(), hasPermission(), deviceLocationOn())

    private fun key(): String? = currentUserId()?.let { "sharing_on_$it" }

    private fun cachedSharingOn(): Boolean? = key()?.takeIf { prefs.contains(it) }?.let { prefs.getBoolean(it, false) }

    private fun cacheSharing(on: Boolean) {
        key()?.let { prefs.edit().putBoolean(it, on).apply() }
    }

    /** One position: a recent cached one if any, otherwise a fresh reading (30 s at most). */
    @SuppressLint("MissingPermission") // hasPermission() is checked by every caller.
    private suspend fun currentFix(): Location? {
        val lm = manager ?: return null
        val providers = providers(lm)
        val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < RECENT_MS }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
        if (recent != null) return recent
        for (provider in providers) {
            val fix = withTimeoutOrNull(FIX_TIMEOUT_MS) { readOnce(lm, provider) }
            if (fix != null) return fix
        }
        return null
    }

    private fun providers(lm: LocationManager): List<String> {
        val all = lm.getProviders(true)
        val order = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (precise()) add(LocationManager.GPS_PROVIDER)
        }
        return order.filter { it in all }
    }

    @SuppressLint("MissingPermission")
    private suspend fun readOnce(lm: LocationManager, provider: String): Location? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                lm.getCurrentLocation(provider, cancel, appContext.mainExecutor) { if (cont.isActive) cont.resume(it) }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (cont.isActive) cont.resume(location)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        }
    }

    private fun parse(iso: String): Instant? = runCatching { Instant.parse(iso) }.getOrNull()

    private companion object {
        const val RECENT_MS = 2 * 60_000L
        const val FIX_TIMEOUT_MS = 20_000L
    }
}

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
import app.vaultchat.data.location.ConsentDto
import app.vaultchat.data.location.LocationApi
import app.vaultchat.data.location.LocationStateDto
import app.vaultchat.data.network.ApiResult
import app.vaultchat.domain.location.LocationBlock
import app.vaultchat.domain.location.LocationGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import kotlinx.serialization.json.Json
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
    /** The position was read but the server did not take it: its error code, or "offline". */
    val sendError: String? = null,
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
        scope.launch {
            lock.withLock { syncAndSend() }
            // No position yet (e.g. GPS still searching): one more try a little later.
            if (_state.value.unavailable) {
                delay(RETRY_MS)
                lock.withLock { if (_state.value.block == LocationBlock.NONE) send() }
            }
        }
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

    /** "Send now" from the menu: read and send a position at once. */
    fun sendNow() {
        scope.launch { lock.withLock { if (_state.value.block == LocationBlock.NONE) send() } }
    }

    /**
     * The consent text to show, always from the server (backend/consents/location-sharing.json). The last
     * one fetched is kept so the menu can show what was agreed while offline; agreeing needs the server.
     */
    suspend fun consent(): ConsentDto? = when (val r = api.consent()) {
        is ApiResult.Ok -> r.value.also { prefs.edit().putString(CONSENT_CACHE, json.encodeToString(ConsentDto.serializer(), it)).apply() }
        else -> null
    }

    /** The last consent text fetched, for showing while offline; null if never fetched. */
    fun cachedConsent(): ConsentDto? = prefs.getString(CONSENT_CACHE, null)?.let {
        runCatching { json.decodeFromString(ConsentDto.serializer(), it) }.getOrNull()
    }

    private suspend fun syncAndSend() {
        when (val r = api.state()) {
            is ApiResult.Ok -> apply(r.value)
            else -> recheck() // Offline: keep the last known answer.
        }
        if (_state.value.block == LocationBlock.NONE) send()
    }

    private suspend fun send() {
        if (!hasPermission() || !deviceLocationOn()) return
        _state.value = _state.value.copy(sending = true, unavailable = false, sendError = null)
        try {
            readAndReport()
        } finally {
            // "Sending…" never stays on screen, whatever happened.
            _state.value = _state.value.copy(sending = false)
        }
    }

    private suspend fun readAndReport() {
        // Any failure reading the position (an unexpected provider error included) only marks it
        // unavailable; it never takes the app down.
        val fix = try {
            currentFix()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (fix == null) {
            _state.value = _state.value.copy(unavailable = true)
            return
        }
        val captured = Instant.ofEpochMilli(fix.time).let { if (it.isAfter(Instant.now())) Instant.now() else it }
        val accuracy = if (fix.hasAccuracy() && fix.accuracy > 0f) fix.accuracy.toDouble() else 1_000.0
        when (val r = api.report(fix.latitude, fix.longitude, accuracy, captured.toString(), precise())) {
            is ApiResult.Ok -> apply(r.value)
            is ApiResult.HttpError -> {
                // Turned off from another phone, or the explanation changed: ask again.
                if (r.code == "SHARING_DISABLED") {
                    cacheSharing(false)
                    recheck()
                }
                _state.value = _state.value.copy(sendError = r.code ?: "HTTP_${r.status}")
            }
            ApiResult.NetworkError -> _state.value = _state.value.copy(sendError = "offline")
        }
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

    /**
     * One position: a cached one under 2 minutes old, else a fresh reading from each enabled provider in
     * turn (fused, network, GPS, then any other), else the newest cached one under 55 minutes old (the
     * server takes points up to an hour old). Phones differ in which providers they offer: some list
     * only GPS when Google's location accuracy is off, and GPS needs a view of the sky.
     */
    @SuppressLint("MissingPermission") // hasPermission() is checked by every caller.
    private suspend fun currentFix(): Location? {
        val lm = manager ?: return null
        val providers = providers(lm)
        val cached = (providers + LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        cached.filter { age(it) < RECENT_MS }.minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }?.let { return it }
        // Every provider at once; the first position wins, and the wait is bounded.
        val fresh = withTimeoutOrNull(FRESH_TIMEOUT_MS) {
            coroutineScope {
                val first = CompletableDeferred<Location?>()
                val reads = providers.map { provider ->
                    launch {
                        val fix = try {
                            readOnce(lm, provider)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        if (fix != null) first.complete(fix)
                    }
                }
                launch {
                    reads.joinAll()
                    first.complete(null)
                }
                first.await().also { coroutineContext.cancelChildren() }
            }
        }
        return fresh ?: cached.filter { age(it) < FALLBACK_MS }.maxByOrNull { it.time }
    }

    private fun age(l: Location) = System.currentTimeMillis() - l.time

    private fun providers(lm: LocationManager): List<String> {
        val enabled = runCatching { lm.getProviders(true) }.getOrDefault(emptyList()).filter { it != LocationManager.PASSIVE_PROVIDER }
        val preferred = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }
        return preferred.filter { it in enabled } + enabled.filter { it !in preferred }
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

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val CONSENT_CACHE = "consent_location_sharing"
        const val RECENT_MS = 2 * 60_000L
        const val FALLBACK_MS = 55 * 60_000L
        const val FRESH_TIMEOUT_MS = 30_000L
        const val RETRY_MS = 30_000L
    }
}

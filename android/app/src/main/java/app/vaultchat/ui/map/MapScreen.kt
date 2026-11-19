package app.vaultchat.ui.map

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vaultchat.BuildConfig
import app.vaultchat.R
import app.vaultchat.data.location.FriendsMapDto
import app.vaultchat.data.location.LocationApi
import app.vaultchat.data.network.ApiResult
import app.vaultchat.ui.home.BottomTab
import app.vaultchat.ui.home.VaultBottomBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/** One pin: a connected person, or the user (name null). x and y are Web Mercator in 0..1. */
private data class Pin(val name: String?, val x: Double, val y: Double, val capturedAtMs: Long)

private sealed interface MapLoad {
    data object Loading : MapLoad
    data class Ready(val pins: List<Pin>) : MapLoad
    data object SharingOff : MapLoad
    data object Offline : MapLoad
}

/**
 * The Map tab (owner, 2026-11-19; D-06 widened): the latest location of each connected person who agreed to
 * the current location consent, and the user's own. Only the latest point is shown, never a history. Map
 * pictures are OpenStreetMap tiles, fetched straight from tile.openstreetmap.org (that server sees which
 * area is viewed, not who is on it).
 */
@Composable
fun MapScreen(api: LocationApi, onChat: () -> Unit, onCamera: () -> Unit) {
    var load by remember { mutableStateOf<MapLoad>(MapLoad.Loading) }
    var reloads by remember { mutableIntStateOf(0) }
    LaunchedEffect(reloads) {
        load = when (val r = api.connectionsMap()) {
            is ApiResult.Ok -> MapLoad.Ready(r.value.toPins())
            is ApiResult.HttpError -> if (r.status == 409) MapLoad.SharingOff else MapLoad.Offline
            ApiResult.NetworkError -> MapLoad.Offline
        }
    }
    val colors = MaterialTheme.colorScheme
    Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.map_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                SmallFloatingActionButton(onClick = { load = MapLoad.Loading; reloads++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_retry))
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (val l = load) {
                    MapLoad.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    MapLoad.SharingOff -> Message(stringResource(R.string.map_sharing_off))
                    MapLoad.Offline -> Message(stringResource(R.string.server_unreachable))
                    is MapLoad.Ready -> TileMap(l.pins)
                }
            }
            VaultBottomBar(selected = BottomTab.MAP, onChat = onChat, onCamera = onCamera, onMap = {})
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun FriendsMapDto.toPins(): List<Pin> {
    val list = people.map { Pin(it.username, mercatorX(it.lng), mercatorY(it.lat), epochMs(it.capturedAt)) }
    return list + listOfNotNull(me?.let { Pin(null, mercatorX(it.lng), mercatorY(it.lat), epochMs(it.capturedAt)) })
}

private fun epochMs(iso: String): Long = runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)

private fun mercatorX(lng: Double) = (lng + 180.0) / 360.0

private fun mercatorY(lat: Double): Double {
    val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
    return (1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0
}

private const val MIN_ZOOM = 2.0
private const val MAX_ZOOM = 18.0

/** A pannable, pinch-zoomable map of OpenStreetMap tiles with the pins drawn on top. */
@Composable
private fun TileMap(pins: List<Pin>) {
    val density = LocalDensity.current
    val tilePx = with(density) { 256.dp.toPx() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var cx by remember { mutableDoubleStateOf(0.5) }
    var cy by remember { mutableDoubleStateOf(0.5) }
    var zoom by remember { mutableDoubleStateOf(3.0) }
    var fitted by remember { mutableStateOf(false) }

    fun fit(points: List<Pin>) {
        if (size.width == 0 || points.isEmpty()) return
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        cx = (minX + maxX) / 2
        cy = (minY + maxY) / 2
        val spanX = max(maxX - minX, 1e-9)
        val spanY = max(maxY - minY, 1e-9)
        // The zoom at which the pins fill about 70 % of the view; one pin alone shows its neighbourhood.
        val zx = log2(size.width * 0.7 / (spanX * tilePx))
        val zy = log2(size.height * 0.7 / (spanY * tilePx))
        zoom = if (points.size == 1) 14.0 else min(min(zx, zy), 15.0).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }
    LaunchedEffect(size, pins) {
        if (!fitted && size.width > 0) {
            fit(pins)
            fitted = true
        }
    }

    val tiles = remember { TileCache() }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFE5E3DF))
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    zoom = (zoom + log2(zoomChange.toDouble())).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    val world = 2.0.pow(zoom) * tilePx
                    cx = (cx - pan.x / world).let { it - floor(it) }
                    cy = (cy - pan.y / world).coerceIn(0.0, 1.0)
                }
            },
    ) {
        val world = 2.0.pow(zoom) * tilePx
        val tz = floor(zoom).toInt().coerceIn(MIN_ZOOM.toInt(), MAX_ZOOM.toInt())
        val n = 1 shl tz
        val ts = world / n
        val w = size.width.toDouble()
        val h = size.height.toDouble()
        val left = cx * world - w / 2
        val top = cy * world - h / 2
        val needed = buildList {
            if (size.width > 0) {
                for (ty in floor(top / ts).toInt()..floor((top + h) / ts).toInt()) {
                    if (ty < 0 || ty >= n) continue
                    for (tx in floor(left / ts).toInt()..floor((left + w) / ts).toInt()) add(Triple(tx, ty, tz))
                }
            }
        }
        LaunchedEffect(needed) {
            needed.forEach { (tx, ty, z) ->
                val key = TileKey(z, Math.floorMod(tx, n), ty)
                if (tiles.wants(key)) scope.launch { tiles.fetch(key) }
            }
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            needed.forEach { (tx, ty, z) ->
                val image = tiles.images[TileKey(z, Math.floorMod(tx, n), ty)] ?: return@forEach
                val x = tx * ts - left
                val y = ty * ts - top
                drawImage(
                    image,
                    dstOffset = IntOffset(x.toInt(), y.toInt()),
                    dstSize = IntSize((x + ts).toInt() - x.toInt(), (y + ts).toInt() - y.toInt()),
                )
            }
        }
        val now = System.currentTimeMillis()
        pins.forEach { pin ->
            // The pin nearest to the view across the date line.
            var dx = (pin.x - cx) * world
            if (dx > world / 2) dx -= world
            if (dx < -world / 2) dx += world
            val px = w / 2 + dx
            val py = (pin.y - cy) * world + h / 2
            if (px < -200 || px > w + 200 || py < -200 || py > h + 200) return@forEach
            PinLabel(
                pin = pin,
                ago = DateUtils.getRelativeTimeSpanString(pin.capturedAtMs, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                modifier = Modifier.offset { IntOffset(px.toInt() - 24.dp.roundToPx(), py.toInt() - 24.dp.roundToPx()) },
            )
        }
        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallFloatingActionButton(onClick = { zoom = (zoom + 1).coerceAtMost(MAX_ZOOM) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.map_zoom_in))
            }
            SmallFloatingActionButton(onClick = { zoom = (zoom - 1).coerceAtLeast(MIN_ZOOM) }) {
                Text("−", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            if (pins.none { it.name != null }) {
                Surface(
                    color = colors.surface.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(12.dp),
                ) {
                    Text(stringResource(R.string.map_nobody), modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            // A chip per pin: tap to centre the map on that person.
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pins.forEach { pin ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = colors.surface,
                        shadowElevation = 2.dp,
                        modifier = Modifier.clickable {
                            cx = pin.x
                            cy = pin.y
                            zoom = max(zoom, 14.0)
                        },
                    ) {
                        Text(
                            pin.name ?: stringResource(R.string.map_me),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            // OpenStreetMap's licence asks for this credit wherever its map is shown.
            Text(
                stringResource(R.string.map_attribution),
                fontSize = 10.sp,
                color = Color(0xFF333333),
                modifier = Modifier.align(Alignment.End).background(Color.White.copy(alpha = 0.7f)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun PinLabel(pin: Pin, ago: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val me = pin.name == null
    val fill = if (me) colors.tertiary else colors.primary
    val onFill = if (me) colors.onTertiary else colors.onPrimary
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Box(
            modifier = Modifier.size(48.dp).border(3.dp, Color.White, CircleShape).background(fill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                (pin.name ?: stringResource(R.string.map_me)).take(1).uppercase(),
                color = onFill,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            )
        }
        Surface(shape = RoundedCornerShape(8.dp), color = colors.surface, shadowElevation = 2.dp) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(
                    pin.name ?: stringResource(R.string.map_me),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(ago, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

private data class TileKey(val z: Int, val x: Int, val y: Int)

/** Downloaded map tiles, kept in memory while the Map is open (at most [MAX] of them). */
private class TileCache {
    val images = mutableStateMapOf<TileKey, ImageBitmap>()
    private val loading = HashSet<TileKey>()
    private val order = ArrayDeque<TileKey>()

    fun wants(key: TileKey): Boolean = key !in images && loading.add(key)

    suspend fun fetch(key: TileKey) {
        val image = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("https://tile.openstreetmap.org/${key.z}/${key.x}/${key.y}.png")
                    // OpenStreetMap's tile policy asks apps to name themselves.
                    .header("User-Agent", "VaultChat/${BuildConfig.VERSION_CODE} (private Android app)")
                    .build()
                client.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) null else r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                }
            }.getOrNull()
        }
        loading.remove(key)
        if (image != null) {
            images[key] = image
            order.addLast(key)
            while (order.size > MAX) images.remove(order.removeFirst())
        }
    }

    companion object {
        const val MAX = 120
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
        }
    }
}

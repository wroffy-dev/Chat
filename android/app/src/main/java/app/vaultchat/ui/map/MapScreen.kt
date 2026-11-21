package app.vaultchat.ui.map

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vaultchat.BuildConfig
import app.vaultchat.R
import app.vaultchat.data.location.FriendsMapDto
import app.vaultchat.data.location.LocationApi
import app.vaultchat.data.network.ApiResult
import app.vaultchat.ui.components.AppIcons
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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/** One pin: a connected person, or the user (name null). x and y are Web Mercator in 0..1. */
private data class Pin(val name: String?, val x: Double, val y: Double, val capturedAtMs: Long, val connectionId: String? = null)

private sealed interface MapLoad {
    data object Loading : MapLoad
    data class Ready(val pins: List<Pin>) : MapLoad
    data object SharingOff : MapLoad
    data object Offline : MapLoad
}

// The owner's reference (2026-11-20): round buttons and round markers; the map itself stays light (owner,
// same day: "Map ko light hi rhne do"), as OpenStreetMap draws it.
private val MapGround = Color(0xFFE5E3DF)
private val DarkButton = Color(0xFF262D44)
private val LabelFill = Color(0xF0121626)
private val MeBlue = Color(0xFF1E88FF)

/** Marker colours; each person keeps the same one (picked from their name). */
private val PersonColors = listOf(
    Color(0xFF1E88FF), Color(0xFF00A86B), Color(0xFFFF7A1A), Color(0xFFE5397F),
    Color(0xFF8E5CF7), Color(0xFF00A3BF), Color(0xFFF2B705), Color(0xFFE5484D),
)

/**
 * The Map tab (owner, 2026-11-19; D-06 widened; look from the owner's reference 2026-11-20): the latest
 * location of each connected person who agreed to the current location consent, and the user's own, as round
 * markers on the map. Only the latest point is shown, never a history. Names are not listed under the map;
 * tapping a marker shows that person's name and how long ago. Map pictures are OpenStreetMap tiles, fetched
 * straight from tile.openstreetmap.org (that server sees which area is viewed, not who is on it).
 */
@Composable
fun MapScreen(
    api: LocationApi,
    onChat: () -> Unit,
    onCamera: () -> Unit,
    onOpenChat: (connectionId: String) -> Unit = {},
    onCallPerson: (connectionId: String, username: String, video: Boolean) -> Unit = { _, _, _ -> },
) {
    var load by remember { mutableStateOf<MapLoad>(MapLoad.Loading) }
    var reloads by remember { mutableIntStateOf(0) }
    LaunchedEffect(reloads) {
        load = when (val r = api.connectionsMap()) {
            is ApiResult.Ok -> MapLoad.Ready(r.value.toPins())
            is ApiResult.HttpError -> if (r.status == 409) MapLoad.SharingOff else MapLoad.Offline
            ApiResult.NetworkError -> MapLoad.Offline
        }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MapGround) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth().background(MapGround)) {
                when (val l = load) {
                    MapLoad.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    MapLoad.SharingOff -> Message(stringResource(R.string.map_sharing_off))
                    MapLoad.Offline -> Message(stringResource(R.string.server_unreachable))
                    is MapLoad.Ready -> TileMap(l.pins, onOpenChat, onCallPerson)
                }
                RoundButton(
                    icon = Icons.Filled.Refresh,
                    description = stringResource(R.string.action_retry),
                    light = false,
                    onClick = { load = MapLoad.Loading; reloads++ },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                )
            }
            VaultBottomBar(selected = BottomTab.MAP, onChat = onChat, onCamera = onCamera, onMap = {})
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 16.sp, lineHeight = 22.sp, color = Color(0xFF333333), textAlign = TextAlign.Center)
    }
}

/** A round floating button of the reference: white with a dark icon, or dark with a white icon. */
@Composable
private fun RoundButton(icon: ImageVector, description: String, light: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (light) Color.White else DarkButton,
        contentColor = if (light) Color(0xFF111111) else Color.White,
        shadowElevation = 6.dp,
        modifier = modifier.size(52.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(24.dp))
        }
    }
}

private fun FriendsMapDto.toPins(): List<Pin> {
    val list = people.map { Pin(it.username, mercatorX(it.lng), mercatorY(it.lat), epochMs(it.capturedAt), it.connectionId) }
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

/** A pannable, pinch-zoomable map of OpenStreetMap tiles with the markers drawn on top. */
@Composable
private fun TileMap(
    pins: List<Pin>,
    onOpenChat: (connectionId: String) -> Unit,
    onCallPerson: (connectionId: String, username: String, video: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val tilePx = with(density) { 256.dp.toPx() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var cx by remember { mutableDoubleStateOf(0.5) }
    var cy by remember { mutableDoubleStateOf(0.5) }
    var zoom by remember { mutableDoubleStateOf(3.0) }
    var fitted by remember { mutableStateOf(false) }
    // The marker whose name is shown ("" for me), or none.
    var selected by remember { mutableStateOf<String?>(null) }

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
        // The zoom at which the markers fill about 60 % of the view; one marker alone shows its neighbourhood.
        val zx = log2(size.width * 0.6 / (spanX * tilePx))
        val zy = log2(size.height * 0.6 / (spanY * tilePx))
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
    val me = pins.firstOrNull { it.name == null }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MapGround)
            .onSizeChanged { size = it }
            .pointerInput(Unit) { detectTapGestures { selected = null } }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    zoom = (zoom + log2(zoomChange.toDouble())).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    val world = 2.0.pow(zoom) * tilePx
                    cx = (cx - pan.x / world).let { it - floor(it) }
                    cy = (cy - pan.y / world).coerceIn(0.0, 1.0)
                }
            },
    ) {
        // Panning and zooming change only cx, cy and zoom. They are read while laying out and drawing, never
        // while composing, so a moving map redraws without rebuilding the markers on every frame
        // (owner 2026-11-21: smoother, no lag).
        LaunchedEffect(Unit) {
            snapshotFlow { visibleTiles(cx, cy, zoom, size, tilePx).map { (tx, ty, z) -> TileKey(z, Math.floorMod(tx, 1 shl z), ty) } }
                .distinctUntilChanged()
                .collect { keys -> keys.forEach { key -> if (tiles.wants(key)) scope.launch { tiles.fetch(key) } } }
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val world = 2.0.pow(zoom) * tilePx
            val left = cx * world - size.width / 2.0
            val top = cy * world - size.height / 2.0
            visibleTiles(cx, cy, zoom, IntSize(size.width.toInt(), size.height.toInt()), tilePx).forEach { (tx, ty, z) ->
                val n = 1 shl z
                val ts = world / n
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
        // Me first, so people stay on top of me when we are in the same place; the chosen one on top of all.
        pins.sortedBy { (if (it.name == null) 0 else 1) + (if ((it.name ?: "") == selected) 2 else 0) }.forEach { pin ->
            val key = pin.name ?: ""
            Marker(
                pin = pin,
                selected = selected == key,
                ago = DateUtils.getRelativeTimeSpanString(pin.capturedAtMs, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                onClick = { selected = if (selected == key) null else key },
                onChat = pin.connectionId?.let { id -> { onOpenChat(id) } },
                onCall = pin.connectionId?.let { id -> { video: Boolean -> onCallPerson(id, pin.name.orEmpty(), video) } },
                position = {
                    // The marker nearest to the view across the date line.
                    val world = 2.0.pow(zoom) * tilePx
                    var dx = (pin.x - cx) * world
                    if (dx > world / 2) dx -= world
                    if (dx < -world / 2) dx += world
                    IntOffset((size.width / 2.0 + dx).toInt(), ((pin.y - cy) * world + size.height / 2.0).toInt())
                },
            )
        }
        if (pins.none { it.name != null }) {
            Surface(
                color = LabelFill,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.TopCenter).padding(start = 16.dp, end = 84.dp, top = 16.dp),
            ) {
                Text(stringResource(R.string.map_nobody), modifier = Modifier.padding(14.dp), fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (pins.size > 1) {
                RoundButton(AppIcons.Group, stringResource(R.string.map_everyone), light = true, onClick = {
                    selected = null
                    fit(pins)
                })
            }
            if (me != null) {
                RoundButton(AppIcons.NearMe, stringResource(R.string.map_center_me), light = false, onClick = {
                    cx = me.x
                    cy = me.y
                    zoom = max(zoom, 15.0)
                    selected = ""
                })
            }
        }
        // OpenStreetMap's licence asks for this credit wherever its map is shown.
        Text(
            stringResource(R.string.map_attribution),
            fontSize = 10.sp,
            color = Color(0xFF333333),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/** A small white round button in a marker's name label. */
@Composable
private fun LabelAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = Color.White, contentColor = Color(0xFF111111), modifier = Modifier.size(38.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
        }
    }
}

/** First letters of a name, for its marker: "partner.phone" → "PP", "ravi" → "R". */
private fun initials(name: String): String {
    val parts = name.split('.', '_', '-', ' ').filter { it.isNotBlank() }
    return parts.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { name.take(1).uppercase() }
}

/**
 * A marker placed so that its round part sits on the point (px, py). People are a coloured circle with their
 * initials and a white ring; I am a round marker in the theme's colour with "M". Tapping shows the name and "x min ago" above.
 */
@Composable
private fun Marker(
    pin: Pin,
    selected: Boolean,
    ago: String,
    onClick: () -> Unit,
    /** Where the point is on screen; read while laying out, so moving the map does not recompose. */
    position: () -> IntOffset,
    onChat: (() -> Unit)? = null,
    onCall: ((video: Boolean) -> Unit)? = null,
) {
    val me = pin.name == null
    val anchor: Dp = if (me) 24.dp else 27.dp
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.layout { measurable, constraints ->
            val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            val at = position()
            layout(0, 0) { p.place(at.x - p.width / 2, at.y - p.height + anchor.roundToPx()) }
        },
    ) {
        if (selected) {
            Surface(color = LabelFill, contentColor = Color.White, shape = RoundedCornerShape(12.dp), shadowElevation = 4.dp) {
                Column(modifier = Modifier.widthIn(max = 200.dp).padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        pin.name ?: stringResource(R.string.map_me),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(ago, fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                    // Chat, voice call and video call with this person straight from the Map (owner, 2026-11-21).
                    if (onChat != null && onCall != null) {
                        Row(modifier = Modifier.padding(top = 8.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LabelAction(AppIcons.Chat, stringResource(R.string.map_open_chat), onChat)
                            LabelAction(Icons.Filled.Call, stringResource(R.string.map_voice_call)) { onCall(false) }
                            LabelAction(AppIcons.Videocam, stringResource(R.string.map_video_call)) { onCall(true) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        if (me) {
            // Me as before v162 (owner, 2026-11-20): a round marker in the theme's own colour with "M".
            val colors = MaterialTheme.colorScheme
            Surface(
                onClick = onClick,
                shape = CircleShape,
                color = colors.tertiary,
                contentColor = colors.onTertiary,
                shadowElevation = 6.dp,
                modifier = Modifier.size(48.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize().border(3.dp, if (selected) MeBlue else Color.White, CircleShape),
                ) {
                    Text(stringResource(R.string.map_me).take(1).uppercase(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.onTertiary)
                }
            }
        } else {
            val name = pin.name.orEmpty()
            val color = PersonColors[abs(name.hashCode()) % PersonColors.size]
            Surface(
                onClick = onClick,
                shape = CircleShape,
                color = color,
                contentColor = Color.White,
                shadowElevation = 6.dp,
                modifier = Modifier.size(54.dp).semantics { contentDescription = name },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize().border(3.dp, if (selected) MeBlue else Color.White, CircleShape),
                ) {
                    Text(initials(name), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

private data class TileKey(val z: Int, val x: Int, val y: Int)

/** The tiles (x not yet wrapped around the date line, y, zoom level) that cover a view of [size] pixels. */
private fun visibleTiles(cx: Double, cy: Double, zoom: Double, size: IntSize, tilePx: Float): List<Triple<Int, Int, Int>> {
    if (size.width == 0) return emptyList()
    val world = 2.0.pow(zoom) * tilePx
    val tz = floor(zoom).toInt().coerceIn(MIN_ZOOM.toInt(), MAX_ZOOM.toInt())
    val n = 1 shl tz
    val ts = world / n
    val left = cx * world - size.width / 2.0
    val top = cy * world - size.height / 2.0
    return buildList {
        for (ty in floor(top / ts).toInt()..floor((top + size.height) / ts).toInt()) {
            if (ty < 0 || ty >= n) continue
            for (tx in floor(left / ts).toInt()..floor((left + size.width) / ts).toInt()) add(Triple(tx, ty, tz))
        }
    }
}

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

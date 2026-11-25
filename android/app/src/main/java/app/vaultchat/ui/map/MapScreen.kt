package app.vaultchat.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import app.vaultchat.ui.components.pressScale
import app.vaultchat.ui.components.staggeredAppear
import app.vaultchat.ui.theme.screenBackground
import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import app.vaultchat.ui.theme.panel
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
import app.vaultchat.ui.components.builtInAvatar
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
private data class Pin(val name: String?, val x: Double, val y: Double, val capturedAtMs: Long, val connectionId: String? = null, val avatar: String? = null)

private sealed interface MapLoad {
    data object Loading : MapLoad
    data class Ready(val pins: List<Pin>) : MapLoad
    data object SharingOff : MapLoad
    data object Offline : MapLoad
}

// The owner's reference (2026-11-20): round buttons and round markers; the map itself stays light (owner,
// same day: "Map ko light hi rhne do"), as OpenStreetMap draws it.
private val MapGround = Color(0xFFE5E3DF)
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
    // A rounded map card on the app's light background, above the navigation bar (owner 2026-11-22).
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().screenBackground().safeDrawingPadding()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 6.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(MapGround),
            ) {
                when (val l = load) {
                    MapLoad.Loading -> MapCard(Modifier.align(Alignment.Center)) {
                        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                        Text(stringResource(R.string.loading), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                    }
                    MapLoad.SharingOff -> Message(stringResource(R.string.map_sharing_off))
                    MapLoad.Offline -> Message(stringResource(R.string.server_unreachable))
                    is MapLoad.Ready -> TileMap(l.pins, onOpenChat, onCallPerson)
                }
                RoundButton(
                    icon = Icons.Filled.Refresh,
                    description = stringResource(R.string.action_retry),
                    light = true,
                    onClick = { load = MapLoad.Loading; reloads++ },
                    modifier = Modifier.align(Alignment.TopEnd).padding(14.dp).staggeredAppear(0),
                )
            }
            VaultBottomBar(selected = BottomTab.MAP, onChat = onChat, onCamera = onCamera, onMap = {})
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        MapCard {
            Text(text, fontSize = 16.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        }
    }
}

/** A floating card over the map, rising in. */
@Composable
private fun MapCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .staggeredAppear(0)
            .panel(RoundedCornerShape(24.dp), strong = true)
            .padding(horizontal = 22.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

/** A round Material floating button over the map: a light surface, or the primary colour (owner 2026-11-22). */
@Composable
private fun RoundButton(icon: ImageVector, description: String, light: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Material floating buttons: a light surface, or the primary colour for "where am I".
    val colors = MaterialTheme.colorScheme
    val look = if (light) {
        Modifier.shadow(4.dp, CircleShape).clip(CircleShape).background(colors.surfaceContainerLowest)
    } else {
        Modifier.shadow(4.dp, CircleShape).clip(CircleShape).background(colors.primary)
    }
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(52.dp)
            .pressScale(interaction, 0.88f)
            .then(look)
            .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClick = onClick),
    ) {
        Icon(icon, contentDescription = description, tint = if (light) colors.onSurface else colors.onPrimary, modifier = Modifier.size(24.dp))
    }
}


private fun FriendsMapDto.toPins(): List<Pin> {
    val list = people.map {
        // The nickname the user gave them, else their username (D-32).
        val name = it.nickname?.takeIf { n -> n.isNotBlank() } ?: it.username
        Pin(name, mercatorX(it.lng), mercatorY(it.lat), epochMs(it.capturedAt), it.connectionId, it.avatar)
    }
    return list + listOfNotNull(me?.let { Pin(null, mercatorX(it.lng), mercatorY(it.lat), epochMs(it.capturedAt), avatar = it.avatar) })
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
                val x = tx * ts - left
                val y = ty * ts - top
                val dstOffset = IntOffset(x.toInt(), y.toInt())
                val dstSize = IntSize((x + ts).toInt() - x.toInt(), (y + ts).toInt() - y.toInt())
                val wx = Math.floorMod(tx, n)
                val image = tiles.images[TileKey(z, wx, ty)]
                if (image != null) {
                    drawImage(image, dstOffset = dstOffset, dstSize = dstSize)
                } else if (z > MIN_ZOOM.toInt()) {
                    // Until it arrives, the matching quarter of the tile one level out, enlarged: zooming in never
                    // flashes empty squares.
                    val parent = tiles.images[TileKey(z - 1, wx / 2, ty / 2)]
                    if (parent != null) {
                        val half = parent.width / 2
                        drawImage(
                            parent,
                            srcOffset = IntOffset((wx % 2) * half, (ty % 2) * half),
                            srcSize = IntSize(half, half),
                            dstOffset = dstOffset,
                            dstSize = dstSize,
                        )
                    }
                }
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
            Text(
                stringResource(R.string.map_nobody),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 14.dp, end = 80.dp, top = 14.dp)
                    .staggeredAppear(1)
                    .panel(RoundedCornerShape(18.dp), strong = true)
                    .padding(14.dp),
            )
        }
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 20.dp).staggeredAppear(2),
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
                .padding(start = 14.dp, bottom = 12.dp)
                .background(Color.White.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** A small white round button in a marker's name label. */
@Composable
private fun LabelAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .pressScale(interaction, 0.86f)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClick = onClick),
    ) {
        Icon(icon, contentDescription = description, tint = Color(0xFF111111), modifier = Modifier.size(20.dp))
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
        // The name card grows out of the marker with a spring (owner 2026-11-22: premium motion).
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn() + scaleIn(spring(dampingRatio = 0.7f, stiffness = 500f), initialScale = 0.6f, transformOrigin = TransformOrigin(0.5f, 1f)),
            exit = fadeOut() + scaleOut(targetScale = 0.8f, transformOrigin = TransformOrigin(0.5f, 1f)),
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.panel(RoundedCornerShape(18.dp), strong = true)) {
                Column(modifier = Modifier.widthIn(max = 220.dp).padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        pin.name ?: stringResource(R.string.map_me),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(ago, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        }
        // Markers drop in with a small bounce, and the chosen one stands a little taller.
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 380f)) }
        val lift by animateFloatAsState(if (selected) 1.12f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "markerLift")
        val pop = Modifier.graphicsLayer {
            val scale = appear.value * lift
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0.5f, 1f)
        }
        if (me) {
            // Me as before v162 (owner, 2026-11-20): a round marker in the theme's own colour with "M".
            val colors = MaterialTheme.colorScheme
            // Where I am: a soft ring that keeps spreading out from my marker, like a beacon.
            val beacon = rememberInfiniteTransition(label = "beacon")
            val wave by beacon.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "beaconWave")
            val ringColor = colors.tertiary
            val myAvatar = builtInAvatar(pin.avatar)
            Surface(
                onClick = onClick,
                shape = CircleShape,
                color = myAvatar?.background ?: colors.tertiary,
                contentColor = colors.onTertiary,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .then(pop)
                    .drawBehind {
                        val r = size.minDimension / 2f
                        drawCircle(ringColor.copy(alpha = 0.35f * (1f - wave)), radius = r * (1f + 0.9f * wave))
                    }
                    .size(48.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize().border(3.dp, if (selected) MeBlue else Color.White, CircleShape),
                ) {
                    // My chosen avatar (D-32), else "M" as before.
                    if (myAvatar != null) {
                        Text(myAvatar.glyph, fontSize = 24.sp)
                    } else {
                        Text(stringResource(R.string.map_me).take(1).uppercase(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.onTertiary)
                    }
                }
            }
        } else {
            val name = pin.name.orEmpty()
            val avatar = builtInAvatar(pin.avatar)
            val color = avatar?.background ?: PersonColors[abs(name.hashCode()) % PersonColors.size]
            Surface(
                onClick = onClick,
                shape = CircleShape,
                color = color,
                contentColor = Color.White,
                shadowElevation = 6.dp,
                modifier = Modifier.then(pop).size(54.dp).semantics { contentDescription = name },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize().border(3.dp, if (selected) MeBlue else Color.White, CircleShape),
                ) {
                    // Their chosen avatar (D-32), else their initials.
                    if (avatar != null) {
                        Text(avatar.glyph, fontSize = 27.sp)
                    } else {
                        Text(initials(name), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
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

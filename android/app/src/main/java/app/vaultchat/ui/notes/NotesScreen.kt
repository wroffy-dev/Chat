package app.vaultchat.ui.notes

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vaultchat.R
import app.vaultchat.data.notes.NoteDto
import app.vaultchat.data.notes.NoteLineDto
import app.vaultchat.data.notes.NotesApi
import app.vaultchat.ui.components.AppIcons
import app.vaultchat.ui.components.BannerKind
import app.vaultchat.ui.components.EmptyState
import app.vaultchat.ui.components.InitialsAvatar
import app.vaultchat.ui.components.StatusBanner
import app.vaultchat.ui.components.VaultIconButton
import app.vaultchat.ui.components.pressScale
import app.vaultchat.ui.home.AskNotificationPermissionOnce
import app.vaultchat.ui.components.staggeredAppear
import app.vaultchat.ui.theme.Spacing
import app.vaultchat.ui.theme.screenBackground
import java.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Just Notes: the screen the app opens on (D-31, owner 2026-11-23). The user's own notes (white cards) and those
 * of the people they are connected with (mint cards), kept on the server. Tapping the "Just Notes" title opens the
 * rest of the app (chats, Map, camera); nothing on the screen points to that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    viewModel: NotesViewModel,
    onOpen: (id: String?) -> Unit,
    onOpenChats: () -> Unit,
    onSessionEnded: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.signedOut) { if (state.signedOut) onSessionEnded() }
    // The app opens here, so the notification permission (calls, "New notes added") is asked here too.
    AskNotificationPermissionOnce()
    val haptics = LocalHapticFeedback.current
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(state.notes, query) { state.notes.filter { it.matches(query) } }
    val gridState = rememberLazyStaggeredGridState()
    // The button shrinks to its icon once the list is scrolled, so it does not cover the cards.
    val atTop by remember { derivedStateOf { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset < 40 } }

    Box(Modifier.fillMaxSize().screenBackground().safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.xl, bottom = Spacing.md)) {
                Text(
                    stringResource(R.string.notes_title),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier
                        .semantics { heading() }
                        .pointerInput(Unit) {
                            // A tap (or a hold) opens everything else: chats, Map and camera (owner 2026-11-23).
                            detectTapGestures(
                                onTap = { onOpenChats() },
                                onLongPress = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onOpenChats()
                                },
                            )
                        },
                )
                if (!state.loading && state.notes.isNotEmpty()) {
                    Text(
                        pluralStringResource(R.plurals.notes_count, state.notes.size, state.notes.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.notes.isNotEmpty()) {
                SearchField(query, onChange = { query = it }, modifier = Modifier.padding(horizontal = Spacing.screen))
                Spacer(Modifier.height(Spacing.md))
            }
            if (state.offline && state.notes.isEmpty()) {
                StatusBanner(
                    kind = BannerKind.WARNING,
                    title = stringResource(R.string.notes_offline),
                    actionLabel = stringResource(R.string.notes_retry),
                    onAction = { viewModel.refresh() },
                    modifier = Modifier.padding(horizontal = Spacing.screen),
                )
            }
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { viewModel.refresh(pulled = true) },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    state.notes.isEmpty() && !state.offline -> EmptyState(
                        title = stringResource(R.string.notes_empty_title),
                        body = stringResource(R.string.notes_empty_body),
                    )
                    shown.isEmpty() && query.isNotBlank() -> EmptyState(
                        title = stringResource(R.string.notes_no_match_title),
                        body = stringResource(R.string.notes_no_match_body, query.trim()),
                    )
                    else -> LazyVerticalStaggeredGrid(
                        state = gridState,
                        columns = StaggeredGridCells.Fixed(2),
                        contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = 104.dp),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        verticalItemSpacing = Spacing.md,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(shown, key = { _, n -> n.id }) { index, note ->
                            NoteCard(note, onClick = { onOpen(note.id) }, modifier = Modifier.staggeredAppear(index))
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { onOpen(null) },
            expanded = atTop,
            icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            text = { Text(stringResource(R.string.notes_new), fontWeight = FontWeight.SemiBold) },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.xl),
        )
    }
}

private fun NoteDto.matches(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return title.contains(q, ignoreCase = true) || body.contains(q, ignoreCase = true) ||
        authorName.contains(q, ignoreCase = true) || lines.any { it.body.contains(q, ignoreCase = true) }
}

/** A soft pill that filters the notes on this phone; nothing is sent anywhere. */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface)
    Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerHigh, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Row(Modifier.padding(start = Spacing.lg, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Spacing.md))
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier.weight(1f).padding(vertical = Spacing.md),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text(stringResource(R.string.notes_search_hint), style = textStyle.copy(color = colors.onSurfaceVariant))
                        inner()
                    }
                },
            )
            AnimatedVisibility(visible = query.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.notes_search_clear), tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** A note in the list: the user's own on a white card, a connected person's in mint green (owner 2026-11-23). */
@Composable
private fun NoteCard(note: NoteDto, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (note.mine) colors.surface else colors.primaryContainer,
        contentColor = if (note.mine) colors.onSurface else colors.onPrimaryContainer,
        border = BorderStroke(1.dp, if (note.mine) colors.outlineVariant.copy(alpha = 0.7f) else colors.primary.copy(alpha = 0.3f)),
        shadowElevation = if (note.mine) 1.dp else 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interaction, pressed = 0.97f)
            .clip(RoundedCornerShape(20.dp))
            .clickable(interactionSource = interaction, indication = ripple(), onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md + 2.dp)) {
            if (!note.mine) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InitialsAvatar(note.authorName, size = 24.dp, avatar = note.authorAvatar)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        stringResource(R.string.notes_from, note.authorName),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
            }
            if (note.title.isNotBlank()) {
                Text(
                    note.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (note.body.isNotBlank()) {
                if (note.title.isNotBlank()) Spacer(Modifier.height(Spacing.xs))
                Text(
                    note.body,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                    color = if (note.mine) colors.onSurfaceVariant else colors.onPrimaryContainer.copy(alpha = 0.85f),
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (note.title.isBlank() && note.body.isBlank() && note.lines.isEmpty()) {
                Text(stringResource(R.string.notes_untitled), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            // The latest added lines, each with who added it; other people's in green.
            if (note.lines.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.sm))
                for (line in note.lines.takeLast(3)) {
                    LineAccent(line.mine) {
                        Text(
                            stringResource(R.string.note_line_preview, if (line.mine) stringResource(R.string.note_line_you) else line.authorName, line.body),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (line.mine) colors.onSurfaceVariant else colors.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    relativeTime(note.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.outline,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (note.lines.isNotEmpty()) {
                    Text(
                        pluralStringResource(R.plurals.notes_lines_count, note.lines.size, note.lines.size),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = colors.primary,
                    )
                }
            }
        }
    }
}

/** A thin bar to the left of an added line: green for other people's lines, grey for the user's own. */
@Composable
private fun LineAccent(mine: Boolean, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(if (mine) colors.outlineVariant else colors.primary.copy(alpha = 0.6f)),
        )
        Spacer(Modifier.width(Spacing.sm))
        Box(Modifier.weight(1f)) { content() }
    }
}

private fun relativeTime(iso: String): String = runCatching {
    DateUtils.getRelativeTimeSpanString(Instant.parse(iso).toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}.getOrDefault("")

/** Reading or writing one note; saved on its own while typing (D-31). */
@Composable
fun NoteEditorScreen(
    viewModel: NoteEditorViewModel,
    onBack: () -> Unit,
    onSessionEnded: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    LaunchedEffect(state.signedOut) { if (state.signedOut) onSessionEnded() }
    LaunchedEffect(state.deleted) { if (state.deleted && !leaving) { leaving = true; onBack() } }

    fun leave() {
        if (leaving) return
        leaving = true
        val saving = viewModel.leave()
        // Wait a moment for the last save so the list shows it; it carries on in the background if slower.
        scope.launch {
            withTimeoutOrNull(1_500) { saving.join() }
            onBack()
        }
    }
    BackHandler(onBack = ::leave)

    Column(Modifier.fillMaxSize().screenBackground().safeDrawingPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VaultIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.note_back), ::leave)
            Spacer(Modifier.weight(1f))
            SaveChip(state.save)
            if (state.mine) {
                Spacer(Modifier.width(Spacing.sm))
                // A soft red button: clearly "delete", but not shouting next to the text (owner 2026-11-23).
                FilledTonalIconButton(
                    onClick = { confirmDelete = true },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = colors.errorContainer,
                        contentColor = colors.error,
                    ),
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.note_delete), modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(Spacing.xs))
        }
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xxl, vertical = Spacing.sm),
        ) {
            if (!state.mine) {
                Surface(shape = RoundedCornerShape(16.dp), color = colors.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm + 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        InitialsAvatar(state.authorUsername, size = 32.dp, avatar = state.authorAvatar)
                        Spacer(Modifier.width(Spacing.md))
                        Text(
                            stringResource(R.string.note_shared_by, state.authorUsername),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.onPrimaryContainer,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.lg))
            }
            val titleStyle = MaterialTheme.typography.headlineSmall.copy(color = colors.onSurface, fontWeight = FontWeight.SemiBold)
            BasicTextField(
                value = state.title,
                onValueChange = viewModel::setTitle,
                readOnly = !state.mine,
                textStyle = titleStyle,
                cursorBrush = SolidColor(colors.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (state.title.isEmpty() && state.mine) Text(stringResource(R.string.note_title_hint), style = titleStyle.copy(color = colors.outline))
                        inner()
                    }
                },
            )
            state.updatedAt?.let { at ->
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    stringResource(R.string.note_edited, relativeTime(at)),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.outline,
                )
            }
            Spacer(Modifier.height(Spacing.lg))
            val bodyStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface, lineHeight = 26.sp)
            BasicTextField(
                value = state.body,
                onValueChange = viewModel::setBody,
                readOnly = !state.mine,
                textStyle = bodyStyle,
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                decorationBox = { inner ->
                    Box {
                        if (state.body.isEmpty() && state.mine) Text(stringResource(R.string.note_body_hint), style = bodyStyle.copy(color = colors.outline))
                        inner()
                    }
                },
            )
            if (state.lines.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.xl))
                Text(
                    stringResource(R.string.note_lines_header),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.xs))
                for (line in state.lines) {
                    LineRow(line, canRemove = line.mine || state.mine, onRemove = { viewModel.deleteLine(line.id) })
                }
            }
            if (state.lineFailed) {
                Spacer(Modifier.height(Spacing.sm))
                Text(stringResource(R.string.note_line_failed), style = MaterialTheme.typography.labelMedium, color = colors.error)
            }
            Spacer(Modifier.height(Spacing.xl))
        }
        // Anyone who can see a saved note can add a line under it (owner 2026-11-23).
        if (state.exists) AddLineBar(sending = state.sendingLine, onAdd = viewModel::addLine)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = colors.error) },
            title = {
                Text(
                    if (state.title.isBlank()) stringResource(R.string.note_delete_title) else stringResource(R.string.note_delete_title_named, state.title.trim()),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = { Text(stringResource(R.string.note_delete_body)) },
            confirmButton = {
                Button(
                    onClick = { confirmDelete = false; viewModel.delete() },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                ) { Text(stringResource(R.string.note_delete_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.note_cancel)) } },
        )
    }
}

/** "Saving…", "Saved" with a tick, or "Not saved" in red; nothing before the first change. */
@Composable
private fun SaveChip(save: SaveState) {
    val colors = MaterialTheme.colorScheme
    AnimatedVisibility(visible = save != SaveState.IDLE, enter = fadeIn(), exit = fadeOut()) {
        val failed = save == SaveState.FAILED
        Surface(shape = RoundedCornerShape(50), color = if (failed) colors.errorContainer else colors.surfaceContainerHigh) {
            Row(Modifier.padding(horizontal = Spacing.md, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                when (save) {
                    SaveState.SAVING -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = colors.onSurfaceVariant)
                    SaveState.SAVED -> Icon(AppIcons.Done, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
                    else -> {}
                }
                if (!failed) Spacer(Modifier.width(6.dp))
                Text(
                    when (save) {
                        SaveState.SAVING -> stringResource(R.string.note_saving)
                        SaveState.SAVED -> stringResource(R.string.note_saved)
                        else -> stringResource(R.string.note_save_failed_short)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (failed) colors.error else colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** One added line: who added it (others' in green) and the text; the cross removes it where allowed. */
@Composable
private fun LineRow(line: NoteLineDto, canRemove: Boolean, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalAlignment = Alignment.Top) {
        Box(Modifier.weight(1f).padding(top = Spacing.xs)) {
            LineAccent(line.mine) {
                Column {
                    Text(
                        if (line.mine) stringResource(R.string.note_line_you) else line.authorName,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (line.mine) colors.outline else colors.primary,
                    )
                    Text(
                        line.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (line.mine) colors.onSurface else colors.primary,
                    )
                }
            }
        }
        if (canRemove) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.note_line_delete), tint = colors.outline, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun AddLineBar(sending: Boolean, onAdd: (String, (Boolean) -> Unit) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    val send = {
        if (text.isNotBlank()) onAdd(text) { ok -> if (ok) text = "" }
    }
    Surface(color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(NotesApi.LINE_MAX) },
                placeholder = { Text(stringResource(R.string.note_add_line_hint)) },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = colors.surface,
                    focusedContainerColor = colors.surface,
                    unfocusedBorderColor = colors.outlineVariant,
                    focusedBorderColor = colors.primary,
                ),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.sm))
            FilledIconButton(
                onClick = send,
                enabled = text.isNotBlank() && !sending,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            ) {
                if (sending) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.note_add_line))
                }
            }
        }
    }
}

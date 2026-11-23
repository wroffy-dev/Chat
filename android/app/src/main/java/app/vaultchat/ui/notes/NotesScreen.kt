package app.vaultchat.ui.notes

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
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
import app.vaultchat.ui.components.BannerKind
import app.vaultchat.ui.components.EmptyState
import app.vaultchat.ui.components.StatusBanner
import app.vaultchat.ui.components.VaultIconButton
import app.vaultchat.ui.theme.Spacing
import app.vaultchat.ui.theme.screenBackground
import java.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Just Notes: the screen the app opens on (D-31, owner 2026-11-23). The user's own notes, kept on the server.
 * Holding the "Just Notes" title opens the chats; nothing on the screen points to that.
 */
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
    val haptics = LocalHapticFeedback.current

    Box(Modifier.fillMaxSize().screenBackground().safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            Text(
                stringResource(R.string.notes_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
                maxLines = 1,
                modifier = Modifier
                    .padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.lg, bottom = Spacing.sm)
                    .semantics { heading() }
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenChats()
                        })
                    },
            )
            if (state.offline && state.notes.isEmpty()) {
                StatusBanner(
                    kind = BannerKind.WARNING,
                    title = stringResource(R.string.notes_offline),
                    actionLabel = stringResource(R.string.notes_retry),
                    onAction = viewModel::refresh,
                    modifier = Modifier.padding(horizontal = Spacing.screen),
                )
            }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.notes.isEmpty() && !state.offline -> EmptyState(
                    title = stringResource(R.string.notes_empty_title),
                    body = stringResource(R.string.notes_empty_body),
                )
                else -> LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalItemSpacing = Spacing.sm,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.notes, key = { it.id }) { note -> NoteCard(note, onClick = { onOpen(note.id) }) }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { onOpen(null) },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.notes_new)) },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.lg),
        )
    }
}

@Composable
private fun NoteCard(note: NoteDto, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(Spacing.md)) {
            if (note.title.isNotBlank()) {
                Text(
                    note.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (note.body.isNotBlank()) {
                if (note.title.isNotBlank()) Spacer(Modifier.height(Spacing.xs))
                Text(
                    note.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (note.title.isBlank() && note.body.isBlank()) {
                Text(stringResource(R.string.notes_untitled), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(relativeTime(note.updatedAt), style = MaterialTheme.typography.labelSmall, color = colors.outline)
        }
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
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VaultIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.note_back), ::leave)
            Spacer(Modifier.weight(1f))
            val status = when (state.save) {
                SaveState.SAVING -> stringResource(R.string.note_saving)
                SaveState.SAVED -> stringResource(R.string.note_saved)
                SaveState.FAILED -> stringResource(R.string.note_save_failed)
                SaveState.IDLE -> ""
            }
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                color = if (state.save == SaveState.FAILED) colors.error else colors.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.sm).weight(1f, fill = false),
            )
            VaultIconButton(Icons.Filled.Delete, stringResource(R.string.note_delete), { confirmDelete = true })
        }
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl, vertical = Spacing.sm),
        ) {
            val titleStyle = MaterialTheme.typography.headlineSmall.copy(color = colors.onSurface, fontWeight = FontWeight.Medium)
            BasicTextField(
                value = state.title,
                onValueChange = viewModel::setTitle,
                textStyle = titleStyle,
                cursorBrush = SolidColor(colors.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (state.title.isEmpty()) Text(stringResource(R.string.note_title_hint), style = titleStyle.copy(color = colors.outline))
                        inner()
                    }
                },
            )
            Spacer(Modifier.height(Spacing.md))
            val bodyStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface)
            BasicTextField(
                value = state.body,
                onValueChange = viewModel::setBody,
                textStyle = bodyStyle,
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (state.body.isEmpty()) Text(stringResource(R.string.note_body_hint), style = bodyStyle.copy(color = colors.outline))
                        inner()
                    }
                },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.note_delete_title)) },
            text = { Text(stringResource(R.string.note_delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete() }) {
                    Text(stringResource(R.string.note_delete_confirm), color = colors.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.note_cancel)) } },
        )
    }
}

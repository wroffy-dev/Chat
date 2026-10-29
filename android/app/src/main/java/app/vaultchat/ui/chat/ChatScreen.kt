package app.vaultchat.ui.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.text.Editable
import android.text.format.DateUtils
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vaultchat.R
import app.vaultchat.domain.chat.ChatMessage
import app.vaultchat.domain.chat.ChatNotice
import app.vaultchat.domain.chat.ChatPhase
import app.vaultchat.domain.chat.ChatText
import app.vaultchat.domain.chat.Outgoing
import app.vaultchat.domain.chat.OutgoingStatus
import app.vaultchat.platform.push.Push
import app.vaultchat.ui.components.AppIcons
import app.vaultchat.ui.components.BannerKind
import app.vaultchat.ui.components.InitialsAvatar
import app.vaultchat.ui.components.FullScreenLoading
import app.vaultchat.ui.components.StatusBanner

/**
 * Text chat (docs/07-chat-and-disappearing-messages.md, docs/15-android-ui-ux.md). Back and the
 * toolbar's Back both leave the chat: [onBack] closes the screen, which is the exit.
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    myUserId: String,
    peerUsername: String,
    onBack: () -> Unit,
    onSessionEnded: () -> Unit,
    onCall: (video: Boolean) -> Unit,
) {
    val controller = viewModel.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findActivity()

    // App in the foreground → the presence lease is renewed. This screen displayed → messages are seen.
    DisposableEffect(activity) {
        val lifecycle = (activity as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, _ ->
            controller.setForeground(lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true)
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    val entryLifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(entryLifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            controller.setOnScreen(entryLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        entryLifecycle.addObserver(observer)
        onDispose { entryLifecycle.removeObserver(observer) }
    }
    // The recent-apps thumbnail must not show messages (API 33+). Screenshots stay possible.
    DisposableEffect(activity) {
        if (Build.VERSION.SDK_INT >= 33) activity?.setRecentsScreenshotEnabled(false)
        onDispose { if (Build.VERSION.SDK_INT >= 33) activity?.setRecentsScreenshotEnabled(true) }
    }
    LaunchedEffect(Unit) { Push.cancelMessageNotification(context) }
    LaunchedEffect(state.phase) {
        if (state.phase == ChatPhase.SESSION_ENDED) onSessionEnded()
    }
    BackHandler(onBack = onBack)

    // Held in memory only: not rememberSaveable, so a draft never lands in saved instance state.
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(state.restoredDraft) {
        state.restoredDraft?.let {
            if (draft.isBlank()) draft = it
            controller.draftRestored()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                    InitialsAvatar(peerUsername, size = 40.dp)
                    Text(
                        peerUsername,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 12.dp).semantics { heading() },
                    )
                    CallButton(AppIcons.Videocam, stringResource(R.string.home_video_call)) { onCall(true) }
                    CallButton(Icons.Filled.Call, stringResource(R.string.home_voice_call)) { onCall(false) }
                }
            }
            Text(
                stringResource(R.string.chat_deletion_rule),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            )
            noticeText(state.notice)?.let { (kind, text) ->
                StatusBanner(
                    kind = kind,
                    title = text,
                    actionLabel = stringResource(R.string.chat_dismiss),
                    onAction = controller::dismissNotice,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            when (state.phase) {
                ChatPhase.ENTERING -> Box(modifier = Modifier.weight(1f)) { FullScreenLoading() }
                ChatPhase.UNAVAILABLE -> Box(modifier = Modifier.weight(1f).padding(16.dp)) {
                    StatusBanner(BannerKind.ERROR, stringResource(R.string.chat_unavailable))
                }
                else -> MessageList(
                    messages = state.messages,
                    outgoing = state.outgoing,
                    myUserId = myUserId,
                    onRetry = controller::retry,
                    onDiscard = controller::discard,
                    modifier = Modifier.weight(1f),
                )
            }

            val tooLong = ChatText.tooLong(draft.trim())
            if (tooLong) {
                Text(
                    stringResource(R.string.chat_too_long, ChatText.MAX_CODE_POINTS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PrivateTextInput(
                        value = draft,
                        onValueChange = { draft = it },
                        hint = stringResource(R.string.chat_input_hint),
                        enabled = state.phase == ChatPhase.OPEN,
                        modifier = Modifier.weight(1f),
                    )
                    val canSend = state.phase == ChatPhase.OPEN && draft.isNotBlank() && !tooLong
                    FilledIconButton(
                        onClick = { if (controller.send(draft)) draft = "" },
                        enabled = canSend,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
                    }
                }
            }
        }
    }
}

/**
 * Message input as a platform EditText, because Compose text fields cannot ask the keyboard not to
 * learn from what is typed (docs/07 §4 rule 6). The keyboard may still ignore the request. The text
 * is never put in saved instance state.
 */
@Composable
private fun PrivateTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val textColor = colors.onSurface.toArgb()
    val hintColor = colors.onSurfaceVariant.toArgb()
    val onChange by rememberUpdatedState(onValueChange)
    AndroidView(
        modifier = modifier
            .background(colors.background, RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        factory = { context ->
            EditText(context).apply {
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                maxLines = 5
                isSaveEnabled = false
                background = null
                addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
                    },
                )
            }
        },
        update = { field ->
            if (field.text.toString() != value) {
                field.setText(value)
                field.setSelection(value.length)
            }
            field.isEnabled = enabled
            field.hint = hint
            field.setTextColor(textColor)
            field.setHintTextColor(hintColor)
        },
    )
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    outgoing: List<Outgoing>,
    myUserId: String,
    onRetry: (String) -> Unit,
    onDiscard: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val total = messages.size + outgoing.size
    LaunchedEffect(total) {
        if (total > 0) listState.animateScrollToItem(total - 1)
    }
    if (total == 0) {
        Box(modifier = modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.chat_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages, key = { it.id }) { m ->
            val mine = m.senderId == myUserId
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                Bubble(m.text, mine = mine)
                Text(
                    DateUtils.formatDateTime(LocalContext.current, m.createdAtEpochMs, DateUtils.FORMAT_SHOW_TIME),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        items(outgoing, key = { it.clientMessageId }) { o ->
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                Bubble(o.text, mine = true, faded = true)
                if (o.status == OutgoingStatus.SENDING) {
                    Text(stringResource(R.string.chat_sending), style = MaterialTheme.typography.labelSmall)
                } else {
                    Row {
                        Text(
                            stringResource(R.string.chat_not_sent),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                        TextButton(onClick = { onRetry(o.clientMessageId) }) { Text(stringResource(R.string.action_retry)) }
                        TextButton(onClick = { onDiscard(o.clientMessageId) }) { Text(stringResource(R.string.chat_discard)) }
                    }
                }
            }
        }
    }
}

/** White rounded bubbles on the grey background (reference design); mine sit on the right. */
@Composable
private fun Bubble(text: String, mine: Boolean, faded: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val corner = 16.dp
    val shape = if (mine) {
        RoundedCornerShape(topStart = corner, topEnd = corner, bottomStart = corner, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = corner, topEnd = corner, bottomStart = 4.dp, bottomEnd = corner)
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface.copy(alpha = if (faded) 0.6f else 1f),
            modifier = Modifier
                .widthIn(max = 290.dp)
                .background(colors.surface, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun CallButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.padding(horizontal = 4.dp).size(42.dp),
    ) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun noticeText(notice: ChatNotice?): Pair<BannerKind, String>? = when (notice) {
    null -> null
    ChatNotice.CLEARED -> BannerKind.INFO to stringResource(R.string.chat_notice_cleared)
    ChatNotice.CLEARED_NOT_SENT -> BannerKind.WARNING to stringResource(R.string.chat_notice_cleared_not_sent)
    ChatNotice.LEFT_WHILE_AWAY -> BannerKind.INFO to stringResource(R.string.chat_notice_left_while_away)
    ChatNotice.TOO_LONG -> BannerKind.WARNING to stringResource(R.string.chat_too_long, ChatText.MAX_CODE_POINTS)
    ChatNotice.RATE_LIMITED -> BannerKind.WARNING to stringResource(R.string.chat_notice_rate_limited)
    ChatNotice.NETWORK -> BannerKind.WARNING to stringResource(R.string.server_unreachable)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

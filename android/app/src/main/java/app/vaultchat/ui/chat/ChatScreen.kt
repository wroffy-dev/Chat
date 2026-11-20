package app.vaultchat.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.vaultchat.domain.chat.EmojiText
import app.vaultchat.domain.chat.QUICK_REACTIONS
import app.vaultchat.domain.chat.MessageKind
import app.vaultchat.domain.chat.Reaction
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.text.Editable
import android.text.format.DateUtils
import android.text.InputType
import android.text.TextWatcher
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.graphics.graphicsLayer
import app.vaultchat.ui.components.PrimaryButton
import app.vaultchat.ui.components.TypingDots
import app.vaultchat.ui.components.VaultIconButton
import app.vaultchat.ui.theme.Motion
import app.vaultchat.ui.theme.Radii
import app.vaultchat.ui.theme.Sizes
import app.vaultchat.ui.theme.Spacing
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
    /** Opens the walkie-talkie for this chat (D-26). */
    onTalk: () -> Unit = {},
    /** Snaps (D-27): take one for this person; how many of theirs wait; open them. */
    onSnap: () -> Unit = {},
    waitingSnaps: Int = 0,
    onOpenSnaps: () -> Unit = {},
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
    // D-23: while a chat is open, Android blanks it in screenshots, screen recordings, casting and the
    // recent-apps thumbnail (FLAG_SECURE). It cannot stop a photo taken with another camera, and a
    // modified (rooted) phone can ignore it.
    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) activity?.setRecentsScreenshotEnabled(false)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= 33) activity?.setRecentsScreenshotEnabled(true)
        }
    }
    LaunchedEffect(Unit) { Push.cancelMessageNotification(context) }
    LaunchedEffect(state.phase) {
        if (state.phase == ChatPhase.SESSION_ENDED) onSessionEnded()
    }
    BackHandler(onBack = onBack)

    // Held in memory only: not rememberSaveable, so a draft never lands in saved instance state.
    var draft by remember { mutableStateOf("") }
    // The message being replied to (owner request 2026-11-13); dropped if it disappears from the chat.
    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    LaunchedEffect(state.messages) { if (replyTo != null && state.messages.none { it.id == replyTo?.id }) replyTo = null }
    LaunchedEffect(state.restoredDraft) {
        state.restoredDraft?.let {
            if (draft.isBlank()) draft = it
            controller.draftRestored()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 0.5.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                    InitialsAvatar(peerUsername, size = Sizes.avatarHeader)
                    Column(modifier = Modifier.weight(1f).padding(start = Spacing.md)) {
                        Text(
                            peerUsername,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() },
                        )
                        // "typing…" with animated dots, sliding in and out (data from chat.typing; no presence).
                        AnimatedVisibility(
                            visible = state.peerTyping,
                            enter = fadeIn(tween(Motion.FAST)) + expandVertically(tween(Motion.FAST)),
                            exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.FAST)),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                Text(
                                    stringResource(R.string.chat_typing),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                TypingDots()
                            }
                        }
                    }
                    CallButton(AppIcons.Mic, stringResource(R.string.talk_button)) { onTalk() }
                    CallButton(AppIcons.Videocam, stringResource(R.string.home_video_call)) { onCall(true) }
                    CallButton(Icons.Filled.Call, stringResource(R.string.home_voice_call)) { onCall(false) }
                }
            }
            DeletionRule()
            if (waitingSnaps > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = 6.dp).clip(MaterialTheme.shapes.large).clickable(onClick = onOpenSnaps),
                ) {
                    Text(
                        if (waitingSnaps == 1) stringResource(R.string.snap_waiting, peerUsername) else stringResource(R.string.snap_waiting_more, waitingSnaps, peerUsername),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
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
                    reactions = state.reactions,
                    onReact = controller::react,
                    onReply = { replyTo = it },
                    onCallBack = onCall,
                    peerUsername = peerUsername,
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
            replyTo?.let { target ->
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.chat_replying_to, if (target.senderId == myUserId) stringResource(R.string.chat_you) else peerUsername),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(target.text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { replyTo = null }) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    // Snapchat's camera button beside the text field: a snap for this person.
                    FilledIconButton(
                        onClick = onSnap,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.size(Sizes.touch),
                    ) { Icon(AppIcons.PhotoCamera, contentDescription = stringResource(R.string.snap_button), modifier = Modifier.size(22.dp)) }
                    PrivateTextInput(
                        value = draft,
                        onValueChange = {
                            draft = it
                            if (it.isNotBlank()) controller.typing()
                        },
                        hint = stringResource(R.string.chat_input_hint),
                        enabled = state.phase == ChatPhase.OPEN,
                        modifier = Modifier.weight(1f),
                    )
                    val canSend = state.phase == ChatPhase.OPEN && draft.isNotBlank() && !tooLong
                    // The send button grows in when there is something to send, and shrinks back after.
                    val sendScale by animateFloatAsState(if (canSend) 1f else 0.88f, tween(Motion.FAST), label = "send")
                    FilledIconButton(
                        onClick = {
                            if (controller.send(draft, replyTo?.id)) {
                                draft = ""
                                replyTo = null
                            }
                        },
                        enabled = canSend,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.size(Sizes.touch).graphicsLayer { scaleX = sendScale; scaleY = sendScale },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send), modifier = Modifier.size(22.dp))
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
    // A soft outline in the accent colour while typing: the field shows it has focus.
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(if (focused) colors.primary.copy(alpha = 0.55f) else Color.Transparent, tween(Motion.FAST), label = "inputBorder")
    AndroidView(
        modifier = modifier
            .heightIn(min = Sizes.touch)
            .background(colors.background, RoundedCornerShape(Radii.input))
            .border(1.dp, border, RoundedCornerShape(Radii.input))
            .padding(horizontal = Spacing.lg, vertical = 2.dp),
        factory = { context ->
            EditText(context).apply {
                setOnFocusChangeListener { _, has -> focused = has }
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
    reactions: Map<String, List<Reaction>>,
    onReact: (messageId: String, emoji: String) -> Unit,
    onReply: (ChatMessage) -> Unit,
    onCallBack: (video: Boolean) -> Unit,
    peerUsername: String,
    myUserId: String,
    onRetry: (String) -> Unit,
    onDiscard: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val total = messages.size + outgoing.size
    // Opening a chat jumps straight to the newest message; only new messages after that scroll smoothly.
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(total) {
        if (total == 0) return@LaunchedEffect
        if (placed) listState.animateScrollToItem(total - 1) else listState.scrollToItem(total - 1)
        placed = true
    }
    if (total == 0) {
        // An empty chat explains itself quietly: who it is with and that messages do not stay.
        Column(
            modifier = modifier.fillMaxWidth().padding(Spacing.xxxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
        ) {
            InitialsAvatar(peerUsername, size = 72.dp)
            Text(peerUsername, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.chat_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.md),
    ) {
        items(messages, key = { it.id }) { m ->
            val mine = m.senderId == myUserId
            if (m.kind == MessageKind.MISSED_CALL) {
                Box(Modifier.animateItem().padding(vertical = Spacing.sm)) { MissedCallLine(m, mine = mine, onCallBack = onCallBack) }
                return@items
            }
            val onThis = reactions[m.id].orEmpty()
            val quoted = m.replyToId?.let { id -> messages.find { it.id == id } }
            var picking by remember { mutableStateOf(false) }
            val index = messages.indexOf(m)
            // A run is consecutive messages from one sender: tight corners inside it, the time under its last one.
            val prev = messages.getOrNull(index - 1)
            val next = messages.getOrNull(index + 1)
            val firstOfRun = prev == null || prev.senderId != m.senderId || prev.kind == MessageKind.MISSED_CALL
            val lastOfRun = next == null || next.senderId != m.senderId || next.kind == MessageKind.MISSED_CALL
            Column(
                modifier = Modifier.fillMaxWidth().animateItem().padding(top = if (firstOfRun) Spacing.sm else Spacing.xxs),
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            ) {
                if (quoted != null) {
                    QuotedMessage(
                        who = if (quoted.senderId == myUserId) stringResource(R.string.chat_you) else peerUsername,
                        text = if (quoted.kind == MessageKind.MISSED_CALL) stringResource(R.string.chat_missed_call_short) else quoted.text,
                    )
                }
                Box {
                    Bubble(m.text, mine = mine, firstOfRun = firstOfRun, lastOfRun = lastOfRun, onLongPress = { picking = true })
                    if (picking) {
                        ReactionPicker(
                            chosen = onThis.find { it.userId == myUserId }?.emoji,
                            alignEnd = mine,
                            onPick = { emoji ->
                                picking = false
                                onReact(m.id, emoji)
                            },
                            onDismiss = { picking = false },
                            onReply = {
                                picking = false
                                onReply(m)
                            },
                        )
                    }
                }
                if (onThis.isNotEmpty()) ReactionPill(onThis, onClick = { picking = true })
                if (lastOfRun) {
                    Text(
                        DateUtils.formatDateTime(LocalContext.current, m.createdAtEpochMs, DateUtils.FORMAT_SHOW_TIME),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
                    )
                }
            }
        }
        items(outgoing, key = { it.clientMessageId }) { o ->
            val sending = o.status == OutgoingStatus.SENDING
            Column(
                modifier = Modifier.fillMaxWidth().animateItem().padding(top = Spacing.xxs),
                horizontalAlignment = Alignment.End,
            ) {
                Bubble(o.text, mine = true, firstOfRun = false, lastOfRun = true, faded = sending, failed = !sending)
                if (sending) {
                    Text(
                        stringResource(R.string.chat_sending),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                        Text(
                            stringResource(R.string.chat_not_sent),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = Spacing.xs),
                        )
                        TextButton(onClick = { onRetry(o.clientMessageId) }) { Text(stringResource(R.string.action_retry)) }
                        TextButton(onClick = { onDiscard(o.clientMessageId) }) { Text(stringResource(R.string.chat_discard)) }
                    }
                }
            }
        }
    }
}

/** The message a reply answers, shown small above its bubble: who wrote it and its first line. */
@Composable
private fun QuotedMessage(who: String, text: String) {
    Column(
        modifier = Modifier
            .widthIn(max = 290.dp)
            .padding(bottom = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(who, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A call that was not answered, as a centred line in the chat (owner request 2026-11-13). The person who
 * missed it can tap it to call back; the caller sees "No answer".
 */
@Composable
private fun MissedCallLine(m: ChatMessage, mine: Boolean, onCallBack: (video: Boolean) -> Unit) {
    val video = m.text == "video"
    val time = DateUtils.formatDateTime(LocalContext.current, m.createdAtEpochMs, DateUtils.FORMAT_SHOW_TIME)
    val label = stringResource(
        when {
            mine && video -> R.string.chat_no_answer_video
            mine -> R.string.chat_no_answer_voice
            video -> R.string.chat_missed_video
            else -> R.string.chat_missed_voice
        },
        time,
    )
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surface)
                .then(if (mine) Modifier else Modifier.clickable(onClickLabel = stringResource(R.string.chat_call_back)) { onCallBack(video) })
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(
                if (video) AppIcons.Videocam else Icons.Filled.Call,
                contentDescription = null,
                tint = if (mine) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            Text(label, style = MaterialTheme.typography.labelMedium)
            if (!mine) Text(stringResource(R.string.chat_call_back), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Message bubbles (UI upgrade 2026-11-20; replaces the Snapchat-style bars): mine on the right in the accent
 * green, theirs on the left on the light surface. Corners tighten on the sender's side inside a run, so a
 * run reads as one block. At most about 78 % of the width, so long messages wrap instead of stretching.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun Bubble(
    text: String,
    mine: Boolean,
    firstOfRun: Boolean,
    lastOfRun: Boolean,
    faded: Boolean = false,
    failed: Boolean = false,
    onLongPress: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val big = Radii.bubble
    val tight = Radii.bubbleTight
    val shape = if (mine) {
        RoundedCornerShape(topStart = big, topEnd = if (firstOfRun) big else tight, bottomStart = big, bottomEnd = if (lastOfRun) big else tight)
    } else {
        RoundedCornerShape(topStart = if (firstOfRun) big else tight, topEnd = big, bottomStart = if (lastOfRun) big else tight, bottomEnd = big)
    }
    val press = if (onLongPress != null) {
        Modifier.combinedClickable(onClick = {}, onLongClickLabel = stringResource(R.string.chat_react), onLongClick = onLongPress)
    } else {
        Modifier
    }
    val alpha = if (faded) 0.65f else 1f
    // Only emoji (1 to 3): large and without a bubble, as on WhatsApp (D-25).
    val emojiCount = EmojiText.count(text.trim())
    if (emojiCount != null && emojiCount in 1..EmojiText.MAX_BIG) {
        Text(
            text.trim(),
            fontSize = when (emojiCount) { 1 -> 48.sp; 2 -> 40.sp; else -> 34.sp },
            color = colors.onSurface.copy(alpha = alpha),
            modifier = Modifier.clip(shape).then(press).padding(horizontal = Spacing.xs),
        )
        return
    }
    val container = when {
        failed -> colors.errorContainer
        mine -> colors.primary
        else -> colors.surface
    }
    val content = when {
        failed -> colors.onErrorContainer
        mine -> colors.onPrimary
        else -> colors.onSurface
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = content.copy(alpha = alpha),
            modifier = Modifier
                .widthIn(max = maxWidth * 0.78f)
                .clip(shape)
                .background(container.copy(alpha = if (faded) 0.8f else 1f))
                .then(press)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

/**
 * The WhatsApp-style row of quick reactions, floating above the pressed message. The emoji already
 * chosen is ringed; picking it again removes it.
 */
@Composable
private fun ReactionPicker(chosen: String?, alignEnd: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit, onReply: () -> Unit) {
    val lift = with(LocalDensity.current) { 56.dp.roundToPx() }
    Popup(
        alignment = if (alignEnd) Alignment.TopEnd else Alignment.TopStart,
        offset = IntOffset(0, -lift),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
            Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                QUICK_REACTIONS.forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (emoji == chosen) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .clickable { onPick(emoji) },
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 26.sp) }
                }
                TextButton(onClick = onReply) { Text(stringResource(R.string.chat_reply)) }
            }
        }
    }
}

/** The reactions on a message, tucked under its bubble: each emoji once, with a count when both chose it. */
@Composable
private fun ReactionPill(reactions: List<Reaction>, onClick: () -> Unit) {
    val counts = reactions.groupingBy { it.emoji }.eachCount()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.background),
        shadowElevation = 1.dp,
        modifier = Modifier.padding(horizontal = 8.dp).offset(y = (-6).dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            counts.forEach { (emoji, n) ->
                Text(if (n > 1) "$emoji $n" else emoji, fontSize = 14.sp)
            }
        }
    }
}

/**
 * A small pill under the header: messages do not stay (D-01/D-02). Tapping it opens a sheet with the full rule.
 * There are no expiry options to choose: the rule is fixed by the owner's decisions, so none are shown.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DeletionRule() {
    var open by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm, bottom = Spacing.xs), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                .clickable(onClickLabel = stringResource(R.string.chat_deletion_more)) { open = true }
                .padding(horizontal = Spacing.md, vertical = 6.dp),
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = muted, modifier = Modifier.size(13.dp))
            Text(stringResource(R.string.chat_deletion_short), style = MaterialTheme.typography.labelMedium, color = muted)
        }
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = Spacing.xxl, end = Spacing.xxl, bottom = Spacing.xxxl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Box(
                    modifier = Modifier.size(44.dp).background(MaterialTheme.colorScheme.background, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                Text(stringResource(R.string.chat_deletion_sheet_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.chat_deletion_rule), style = MaterialTheme.typography.bodyMedium, color = muted)
                Text(stringResource(R.string.chat_deletion_sheet_note), style = MaterialTheme.typography.bodySmall, color = muted)
                PrimaryButton(stringResource(R.string.chat_deletion_ok), onClick = { open = false })
            }
        }
    }
}

@Composable
private fun CallButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    VaultIconButton(icon, description, onClick, container = MaterialTheme.colorScheme.background, size = 40.dp)
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

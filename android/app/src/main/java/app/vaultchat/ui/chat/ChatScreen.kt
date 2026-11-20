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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.vaultchat.domain.chat.DISAPPEAR_CHOICES
import app.vaultchat.ui.theme.Ref
import app.vaultchat.R
import app.vaultchat.domain.chat.EmojiEffects
import kotlinx.coroutines.delay
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

    // Emoji effects (owner request 2026-11-20, like Apple Messages): a new heart, 🎉, 🔥… plays once over the
    // chat on both phones. Messages already there when the chat opens do not replay.
    var burst by remember { mutableStateOf<Pair<String, EmojiEffects.Effect>?>(null) }
    var lastSeenSeq by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        delay(1_500)
        if (lastSeenSeq == null) lastSeenSeq = state.messages.maxOfOrNull { it.seq } ?: 0L
    }
    LaunchedEffect(state.messages) {
        val top = state.messages.maxOfOrNull { it.seq } ?: return@LaunchedEffect
        val seen = lastSeenSeq
        if (seen == null) {
            lastSeenSeq = top
            return@LaunchedEffect
        }
        val fresh = state.messages.lastOrNull { it.seq > seen && it.senderId != myUserId && it.kind == MessageKind.TEXT }
        fresh?.let { m -> EmojiEffects.effectFor(m.text)?.let { burst = m.id to it } }
        lastSeenSeq = maxOf(seen, top)
    }

    // The disappearing-messages screen (owner's reference, 2026-11-20) opens over the chat, so the chat stays
    // open (no exit, FLAG_SECURE kept) while the timer is chosen.
    var showExpiry by remember { mutableStateOf(false) }
    if (showExpiry) {
        DisappearingScreen(
            peerUsername = peerUsername,
            current = state.disappearSeconds,
            onBack = { showExpiry = false },
            onSet = { seconds ->
                controller.setDisappearing(seconds)
                showExpiry = false
            },
        )
        return
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Ref.Surface, contentColor = Ref.OnSurface) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            ChatHeader(
                peerUsername = peerUsername,
                typing = state.peerTyping,
                onBack = onBack,
                onVoice = { onCall(false) },
                onVideo = { onCall(true) },
                onTalk = onTalk,
                onDisappearing = { showExpiry = true },
            )
            StatusStrip(disappearSeconds = state.disappearSeconds, onExpiry = { showExpiry = true })
            if (waitingSnaps > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Ref.PrimaryContainer)
                        .clickable(onClick = onOpenSnaps)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(AppIcons.PhotoCamera, contentDescription = null, tint = Ref.Primary, modifier = Modifier.size(18.dp))
                    Text(
                        if (waitingSnaps == 1) stringResource(R.string.snap_waiting, peerUsername) else stringResource(R.string.snap_waiting_more, waitingSnaps, peerUsername),
                        color = Ref.OnPrimaryContainer,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
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
                    peerReadSeq = state.peerReadSeq,
                    disappearing = state.disappearSeconds != null,
                    onRetry = controller::retry,
                    onDiscard = controller::discard,
                    modifier = Modifier.weight(1f),
                )
            }

            val tooLong = ChatText.tooLong(draft.trim())
            if (tooLong) {
                Text(
                    stringResource(R.string.chat_too_long, ChatText.MAX_CODE_POINTS),
                    fontSize = 12.sp,
                    color = Ref.Error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            replyTo?.let { target ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Ref.ContainerLow)
                        .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).height(32.dp).background(Ref.Primary, RoundedCornerShape(2.dp)))
                    Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(
                            stringResource(R.string.chat_replying_to, if (target.senderId == myUserId) stringResource(R.string.chat_you) else peerUsername),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Ref.Primary,
                        )
                        Text(target.text, fontSize = 12.sp, color = Ref.OnSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { replyTo = null }) { Text(stringResource(R.string.action_cancel), color = Ref.Primary) }
                }
            }
            // Composer: text only. The round button on the left takes a snap (the reference's place for it).
            Column(modifier = Modifier.fillMaxWidth().background(Ref.Surface)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ref.OutlineVariant))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RoundAction(
                        icon = AppIcons.PhotoCamera,
                        description = stringResource(R.string.snap_button),
                        container = Ref.Container,
                        tint = Ref.OnSurfaceVariant,
                        onClick = onSnap,
                    )
                    PrivateTextInput(
                        value = draft,
                        onValueChange = {
                            draft = it
                            if (it.isNotBlank()) controller.typing()
                        },
                        hint = stringResource(R.string.chat_message_hint, peerUsername),
                        enabled = state.phase == ChatPhase.OPEN,
                        modifier = Modifier.weight(1f),
                    )
                    val canSend = state.phase == ChatPhase.OPEN && draft.isNotBlank() && !tooLong
                    RoundAction(
                        icon = AppIcons.ArrowUpward,
                        description = stringResource(R.string.chat_send),
                        container = if (canSend) Ref.Primary else Ref.Primary.copy(alpha = 0.4f),
                        tint = Ref.OnPrimary,
                        enabled = canSend,
                        onClick = {
                            val effect = EmojiEffects.effectFor(draft)
                            if (controller.send(draft, replyTo?.id)) {
                                // The sender sees the effect at once, as in Apple Messages.
                                effect?.let { burst = "sent-${System.nanoTime()}" to it }
                                draft = ""
                                replyTo = null
                            }
                        },
                    )
                }
            }
        }
        burst?.let { (key, effect) -> EmojiBurst(effect, key) { if (burst?.first == key) burst = null } }
    }
}

/**
 * Message input as a platform EditText, because Compose text fields cannot ask the keyboard not to
 * learn from what is typed (docs/07 §4 rule 6). The keyboard may still ignore the request. The text
 * is never put in saved instance state. Styled as the reference: a grey pill that turns white with a
 * blue outline while typing.
 */
@Composable
private fun PrivateTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val textColor = Ref.OnSurface.toArgb()
    val hintColor = Ref.OnSurfaceVariant.toArgb()
    val onChange by rememberUpdatedState(onValueChange)
    var focused by remember { mutableStateOf(false) }
    // Not named "background": inside the EditText's apply {} below that would hide View.background.
    val fieldFill by animateColorAsState(if (focused) Ref.Surface else Ref.Container, tween(120), label = "inputBg")
    val fieldOutline by animateColorAsState(if (focused) Ref.Primary else Color.Transparent, tween(120), label = "inputBorder")
    AndroidView(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(50))
            .background(fieldFill)
            .border(1.dp, fieldOutline, RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 2.dp),
        factory = { context ->
            EditText(context).apply {
                setOnFocusChangeListener { _, has -> focused = has }
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                maxLines = 5
                textSize = 14f
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

/** Round 44 dp button of the reference (composer and header). */
@Composable
private fun RoundAction(
    icon: ImageVector,
    description: String,
    container: Color,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** Two letters for the header avatar ("EV" for "Elena Vance", "AL" for "alice"). */
private fun initialsOf(name: String): String {
    val words = name.split(' ', '.', '_', '-').filter { it.isNotBlank() }
    val letters = if (words.size >= 2) "${words[0].first()}${words[1].first()}" else name.filter { it.isLetterOrDigit() }.take(2)
    return letters.uppercase().ifEmpty { "?" }
}

/** Header of the reference: back, avatar and name, then voice, video and the options menu. */
@Composable
private fun ChatHeader(
    peerUsername: String,
    typing: Boolean,
    onBack: () -> Unit,
    onVoice: () -> Unit,
    onVideo: () -> Unit,
    onTalk: () -> Unit,
    onDisappearing: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(Ref.Surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RoundAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), Color.Transparent, Ref.OnSurface, onBack)
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(40.dp).background(Ref.PrimaryContainer, CircleShape).clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(initialsOf(peerUsername), color = Ref.OnPrimaryContainer, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        peerUsername,
                        color = Ref.OnSurface,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    // The reference's status line; Vault only knows when the other person is typing (no presence).
                    if (typing) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.chat_typing), color = Ref.Teal, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp, fontWeight = FontWeight.Medium)
                            TypingDots(color = Ref.Teal)
                        }
                    }
                }
            }
            RoundAction(Icons.Filled.Call, stringResource(R.string.home_voice_call), Color.Transparent, Ref.OnSurface, onVoice)
            RoundAction(AppIcons.Videocam, stringResource(R.string.home_video_call), Color.Transparent, Ref.OnSurface, onVideo)
            var menu by remember { mutableStateOf(false) }
            Box {
                RoundAction(Icons.Filled.MoreVert, stringResource(R.string.chat_options), Color.Transparent, Ref.OnSurface, { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.expiry_title)) },
                        leadingIcon = { Icon(AppIcons.Timer, contentDescription = null) },
                        onClick = { menu = false; onDisappearing() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.talk_button)) },
                        leadingIcon = { Icon(AppIcons.Mic, contentDescription = null) },
                        onClick = { menu = false; onTalk() },
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ref.OutlineVariant))
    }
}

/** "5 min expiry", "24h expiry" or "Expiry off" for the strip's pill. */
@Composable
private fun expiryLabel(seconds: Int?): String = when (seconds) {
    null -> stringResource(R.string.expiry_pill_off)
    60 -> stringResource(R.string.expiry_pill, "1 min")
    300 -> stringResource(R.string.expiry_pill, "5 min")
    3600 -> stringResource(R.string.expiry_pill, "1h")
    else -> stringResource(R.string.expiry_pill, "24h")
}

/**
 * The strip under the header: what protects the chat (left) and the disappearing timer (right, opens its
 * screen). The reference's "End-to-end encrypted" is not used: the app makes no encryption claim.
 */
@Composable
private fun StatusStrip(disappearSeconds: Int?, onExpiry: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(Ref.ContainerLow)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f, fill = false)) {
                Icon(AppIcons.VerifiedUser, contentDescription = null, tint = Ref.Teal, modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.chat_strip_private), color = Ref.Teal, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Ref.Surface)
                    .border(1.dp, Ref.OutlineVariant, RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClick = onExpiry)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(AppIcons.Timer, contentDescription = null, tint = Ref.OnSurfaceVariant, modifier = Modifier.size(12.dp))
                Text(expiryLabel(disappearSeconds), color = Ref.OnSurfaceVariant, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp, fontWeight = FontWeight.Medium)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ref.OutlineVariant))
    }
}

/** "Today", "Yesterday" or a date, as a small grey chip between days. */
@Composable
private fun DayChip(epochMs: Long) {
    val context = LocalContext.current
    val label = when {
        DateUtils.isToday(epochMs) -> stringResource(R.string.chat_today)
        DateUtils.isToday(epochMs + DateUtils.DAY_IN_MILLIS) -> stringResource(R.string.home_yesterday)
        else -> DateUtils.formatDateTime(context, epochMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            label,
            color = Ref.OnSurfaceVariant,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            letterSpacing = 0.3.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Ref.Container).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) && ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}

/** A centred line saying who changed the disappearing timer (D-30). */
@Composable
private fun DisappearLine(m: ChatMessage, mine: Boolean, peerUsername: String) {
    val who = if (mine) stringResource(R.string.chat_you) else peerUsername
    val text = when (m.text) {
        "off" -> stringResource(R.string.expiry_line_off, who)
        "60" -> stringResource(R.string.expiry_line_set, who, stringResource(R.string.expiry_1m))
        "300" -> stringResource(R.string.expiry_line_set, who, stringResource(R.string.expiry_5m))
        "3600" -> stringResource(R.string.expiry_line_set, who, stringResource(R.string.expiry_1h))
        else -> stringResource(R.string.expiry_line_set, who, stringResource(R.string.expiry_24h))
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Ref.ContainerLow).border(1.dp, Ref.OutlineVariant, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(AppIcons.Timer, contentDescription = null, tint = Ref.OnSurfaceVariant, modifier = Modifier.size(14.dp))
            Text(text, color = Ref.OnSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
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
    peerReadSeq: Long,
    disappearing: Boolean,
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
        Column(
            modifier = modifier.fillMaxWidth().background(Ref.Surface).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.size(64.dp).background(Ref.PrimaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Text(initialsOf(peerUsername), color = Ref.OnPrimaryContainer, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(peerUsername, color = Ref.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.chat_empty), color = Ref.OnSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().background(Ref.Surface),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(messages, key = { it.id }) { m ->
            val index = messages.indexOf(m)
            val prev = messages.getOrNull(index - 1)
            Column(modifier = Modifier.fillMaxWidth().animateItem(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (prev == null || !sameDay(prev.createdAtEpochMs, m.createdAtEpochMs)) DayChip(m.createdAtEpochMs)
                val mine = m.senderId == myUserId
                when (m.kind) {
                    MessageKind.MISSED_CALL -> MissedCallLine(m, mine = mine, onCallBack = onCallBack)
                    MessageKind.DISAPPEAR -> DisappearLine(m, mine = mine, peerUsername = peerUsername)
                    MessageKind.TEXT -> {
                        val onThis = reactions[m.id].orEmpty()
                        val quoted = m.replyToId?.let { id -> messages.find { it.id == id } }
                        var picking by remember { mutableStateOf(false) }
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (quoted != null) {
                                QuotedMessage(
                                    who = if (quoted.senderId == myUserId) stringResource(R.string.chat_you) else peerUsername,
                                    text = if (quoted.kind == MessageKind.MISSED_CALL) stringResource(R.string.chat_missed_call_short) else quoted.text,
                                )
                            }
                            Box {
                                Bubble(m.text, mine = mine, onLongPress = { picking = true })
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
                            MetaRow(
                                time = DateUtils.formatDateTime(LocalContext.current, m.createdAtEpochMs, DateUtils.FORMAT_SHOW_TIME),
                                status = when {
                                    !mine -> if (disappearing) MetaStatus.DISAPPEARING else null
                                    m.seq <= peerReadSeq -> MetaStatus.READ
                                    else -> MetaStatus.SENT
                                },
                            )
                        }
                    }
                }
            }
        }
        items(outgoing, key = { it.clientMessageId }) { o ->
            val sending = o.status == OutgoingStatus.SENDING
            Column(
                modifier = Modifier.fillMaxWidth().animateItem(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Bubble(o.text, mine = true, faded = sending, failed = !sending)
                if (sending) {
                    Text(stringResource(R.string.chat_sending), color = Ref.OnSurfaceVariant, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(horizontal = 4.dp))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = Ref.Error, modifier = Modifier.size(14.dp))
                        Text(stringResource(R.string.chat_not_sent), color = Ref.Error, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
                        TextButton(onClick = { onRetry(o.clientMessageId) }) { Text(stringResource(R.string.action_retry), color = Ref.Primary) }
                        TextButton(onClick = { onDiscard(o.clientMessageId) }) { Text(stringResource(R.string.chat_discard), color = Ref.Primary) }
                    }
                }
            }
        }
    }
}

private enum class MetaStatus { SENT, READ, DISAPPEARING }

/** Time under a message, with ✓ (sent), ✓✓ in blue (read) or ⏱ (will disappear). */
@Composable
private fun MetaRow(time: String, status: MetaStatus?) {
    Row(
        modifier = Modifier.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(time, color = Ref.OnSurfaceVariant, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp, fontWeight = FontWeight.Medium)
        when (status) {
            MetaStatus.SENT -> Icon(AppIcons.Done, contentDescription = stringResource(R.string.chat_status_sent), tint = Ref.OnSurfaceVariant, modifier = Modifier.size(14.dp))
            MetaStatus.READ -> Icon(AppIcons.DoneAll, contentDescription = stringResource(R.string.chat_status_read), tint = Ref.Primary, modifier = Modifier.size(14.dp))
            MetaStatus.DISAPPEARING -> Icon(AppIcons.Timer, contentDescription = stringResource(R.string.chat_status_disappearing), tint = Ref.OnSurfaceVariant, modifier = Modifier.size(12.dp))
            null -> Unit
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
            .background(Ref.Container)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(who, style = MaterialTheme.typography.labelSmall, color = Ref.Primary)
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Ref.OnSurfaceVariant)
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
                .background(Ref.Container)
                .then(if (mine) Modifier else Modifier.clickable(onClickLabel = stringResource(R.string.chat_call_back)) { onCallBack(video) })
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(
                if (video) AppIcons.Videocam else Icons.Filled.Call,
                contentDescription = null,
                tint = if (mine) Ref.OnSurfaceVariant else Ref.Error,
                modifier = Modifier.size(16.dp),
            )
            Text(label, style = MaterialTheme.typography.labelMedium)
            if (!mine) Text(stringResource(R.string.chat_call_back), style = MaterialTheme.typography.labelMedium, color = Ref.Primary)
        }
    }
}

/**
 * Message bubbles of the reference: theirs on the left in light grey, mine on the right in blue, 16 dp
 * corners with a 4 dp corner next to the sender, at most 82 % of the width.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun Bubble(
    text: String,
    mine: Boolean,
    faded: Boolean = false,
    failed: Boolean = false,
    onLongPress: (() -> Unit)? = null,
) {
    val shape = if (mine) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
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
            color = Ref.OnSurface.copy(alpha = alpha),
            modifier = Modifier.clip(shape).then(press).padding(horizontal = 4.dp),
        )
        return
    }
    val container = when {
        failed -> Ref.ErrorContainer
        mine -> Ref.Primary
        else -> Ref.Container
    }
    val content = when {
        failed -> Ref.OnErrorContainer
        mine -> Ref.OnPrimary
        else -> Ref.OnSurface
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = content.copy(alpha = alpha),
            modifier = Modifier
                .widthIn(max = maxWidth * 0.82f)
                .clip(shape)
                .background(container.copy(alpha = if (faded) 0.8f else 1f))
                .then(press)
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
        Surface(shape = RoundedCornerShape(28.dp), color = Ref.Surface, shadowElevation = 6.dp) {
            Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                QUICK_REACTIONS.forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (emoji == chosen) Ref.Container else Color.Transparent)
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
        color = Ref.Surface,
        border = BorderStroke(1.dp, Ref.OutlineVariant),
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
 * Disappearing messages (owner's reference message_expiry_settings.html, 2026-11-20, without "7 Days"; D-30):
 * the explanation, the choices as one bordered radio group with the current one marked "Currently active",
 * and a pill button that sets the chosen timer for both people.
 */
@Composable
private fun DisappearingScreen(peerUsername: String, current: Int?, onBack: () -> Unit, onSet: (Int?) -> Unit) {
    BackHandler(onBack = onBack)
    var chosen by remember(current) { mutableStateOf(current) }
    Surface(modifier = Modifier.fillMaxSize(), color = Ref.Surface, contentColor = Ref.OnSurface) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RoundAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), Color.Transparent, Ref.OnSurface, onBack)
                Text(
                    stringResource(R.string.expiry_title),
                    color = Ref.OnSurface,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ref.OutlineVariant))
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Ref.ContainerLow)
                        .border(1.dp, Ref.OutlineVariant, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(AppIcons.Timer, contentDescription = null, tint = Ref.Primary, modifier = Modifier.size(24.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.expiry_how_title), color = Ref.OnSurface, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.expiry_how_body, peerUsername), color = Ref.OnSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.expiry_duration),
                        color = Ref.OnSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        letterSpacing = 0.2.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, Ref.OutlineVariant, RoundedCornerShape(12.dp))
                            .background(Ref.Surface)
                            .selectableGroup(),
                    ) {
                        DISAPPEAR_CHOICES.forEachIndexed { i, seconds ->
                            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Ref.OutlineVariant))
                            val active = seconds == current
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (active) Ref.PrimaryContainer.copy(alpha = 0.4f) else Color.Transparent)
                                    .selectable(selected = chosen == seconds, role = Role.RadioButton, onClick = { chosen = seconds })
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                val label = expiryChoiceLabel(seconds)
                                if (active) {
                                    Column {
                                        Text(label, color = Ref.Primary, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                                        Text(stringResource(R.string.expiry_active), color = Ref.OnSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
                                    }
                                } else {
                                    Text(label, color = Ref.OnSurface, fontSize = 16.sp, lineHeight = 24.sp)
                                }
                                RadioButton(
                                    selected = chosen == seconds,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = Ref.Primary, unselectedColor = Ref.Outline),
                                )
                            }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ref.OutlineVariant))
            Box(modifier = Modifier.fillMaxWidth().background(Ref.Surface).padding(16.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Ref.Primary)
                        .clickable(role = Role.Button, onClick = { onSet(chosen) })
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (chosen == null) stringResource(R.string.expiry_button_off) else stringResource(R.string.expiry_button_set, expiryChoiceLabel(chosen)),
                        color = Ref.OnPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        letterSpacing = 0.1.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun expiryChoiceLabel(seconds: Int?): String = stringResource(
    when (seconds) {
        null -> R.string.expiry_off
        60 -> R.string.expiry_1m
        300 -> R.string.expiry_5m
        3600 -> R.string.expiry_1h
        else -> R.string.expiry_24h
    },
)

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

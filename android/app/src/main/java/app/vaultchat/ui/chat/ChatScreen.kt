package app.vaultchat.ui.chat

import androidx.compose.material.icons.filled.MoreVert
import app.vaultchat.ui.components.popOnChange
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.MutableState
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
import app.vaultchat.ui.theme.panel
import app.vaultchat.ui.theme.screenBackground
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.vaultchat.domain.chat.DISAPPEAR_CHOICES
import app.vaultchat.ui.theme.Ref
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import app.vaultchat.ui.components.pressScale
import app.vaultchat.ui.components.staggeredAppear
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import app.vaultchat.R
import androidx.compose.runtime.saveable.rememberSaveable
import app.vaultchat.ui.profile.NicknameDialog
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import androidx.compose.material.icons.filled.Edit
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
    /** The person's built-in avatar (D-32). */
    peerAvatar: String? = null,
    /** The person's real username and the user's nickname for them (D-32); [peerUsername] is what is shown. */
    realUsername: String = peerUsername,
    nickname: String? = null,
    /** Saves a nickname (null removes it); true when the server took it. */
    onSetNickname: suspend (String?) -> Boolean = { false },
) {
    // The shown name follows a nickname set from this screen at once.
    var currentNickname by rememberSaveable { mutableStateOf(nickname) }
    val shownName = currentNickname?.takeIf { it.isNotBlank() } ?: realUsername.ifEmpty { peerUsername }
    var editingNickname by rememberSaveable { mutableStateOf(false) }
    var savingNickname by remember { mutableStateOf(false) }
    val nicknameScope = rememberCoroutineScope()
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

    // Held in memory only: not rememberSaveable, so a draft never lands in saved instance state. Only the
    // composer reads it, so typing a letter redraws the composer, not the whole chat (owner 2026-11-21: lag).
    val draftState = remember { mutableStateOf("") }
    // The message being replied to (owner request 2026-11-13); dropped if it disappears from the chat.
    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    LaunchedEffect(state.messages) { if (replyTo != null && state.messages.none { it.id == replyTo?.id }) replyTo = null }
    LaunchedEffect(state.restoredDraft) {
        state.restoredDraft?.let {
            if (draftState.value.isBlank()) draftState.value = it
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
            peerUsername = shownName,
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
        Column(modifier = Modifier.fillMaxSize().screenBackground().safeDrawingPadding().imePadding()) {
            ChatHeader(
                peerUsername = shownName,
                peerAvatar = peerAvatar,
                typing = state.peerTyping,
                onBack = onBack,
                onVoice = { onCall(false) },
                onVideo = { onCall(true) },
                onTalk = onTalk,
                onDisappearing = { showExpiry = true },
                onNickname = { editingNickname = true },
            )
            StatusStrip(disappearSeconds = state.disappearSeconds, onExpiry = { showExpiry = true })
            if (waitingSnaps > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .panel(RoundedCornerShape(18.dp))
                        .background(Ref.PrimaryContainer.copy(alpha = 0.55f))
                        .clickable(onClick = onOpenSnaps)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(AppIcons.PhotoCamera, contentDescription = null, tint = Ref.Primary, modifier = Modifier.size(18.dp))
                    Text(
                        if (waitingSnaps == 1) stringResource(R.string.snap_waiting, shownName) else stringResource(R.string.snap_waiting_more, waitingSnaps, shownName),
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
                    peerUsername = shownName,
                    myUserId = myUserId,
                    peerReadSeq = state.peerReadSeq,
                    disappearing = state.disappearSeconds != null,
                    peerTyping = state.peerTyping,
                    onRetry = controller::retry,
                    onDiscard = controller::discard,
                    modifier = Modifier.weight(1f),
                )
            }

            ChatComposer(
                draftState = draftState,
                replyTo = replyTo,
                onCancelReply = { replyTo = null },
                open = state.phase == ChatPhase.OPEN,
                myUserId = myUserId,
                peerUsername = shownName,
                onSnap = onSnap,
                onTyping = controller::typing,
                onSend = { text ->
                    val effect = EmojiEffects.effectFor(text)
                    val sent = controller.send(text, replyTo?.id)
                    if (sent) {
                        // The sender sees the effect at once, as in Apple Messages.
                        effect?.let { burst = "sent-${System.nanoTime()}" to it }
                        replyTo = null
                    }
                    sent
                },
            )
        }
        burst?.let { (key, effect) -> EmojiBurst(effect, key) { if (burst?.first == key) burst = null } }
    }
    if (editingNickname) {
        NicknameDialog(
            username = realUsername.ifEmpty { peerUsername },
            current = currentNickname,
            saving = savingNickname,
            onSave = { name ->
                savingNickname = true
                nicknameScope.launch {
                    if (onSetNickname(name)) {
                        currentNickname = name
                        editingNickname = false
                    }
                    savingNickname = false
                }
            },
            onDismiss = { editingNickname = false },
        )
    }
}

/**
 * The bottom of the chat: characters left, the reply bar and the input with the snap and send buttons. It
 * alone reads the draft, so each letter typed redraws only this part (owner 2026-11-21: typing lagged
 * because every letter redrew the whole chat).
 */
@Composable
private fun ChatComposer(
    draftState: MutableState<String>,
    replyTo: ChatMessage?,
    onCancelReply: () -> Unit,
    open: Boolean,
    myUserId: String,
    peerUsername: String,
    onSnap: () -> Unit,
    onTyping: () -> Unit,
    onSend: (String) -> Boolean,
) {
    val draft = draftState.value
    val tooLong = ChatText.tooLong(draft.trim())
    // Near the limit, how many characters are left (owner 2026-11-20: a better typing experience).
    val left = ChatText.MAX_CODE_POINTS - ChatText.codePoints(draft.trim())
    if (tooLong) {
        Text(
            stringResource(R.string.chat_too_long, ChatText.MAX_CODE_POINTS),
            fontSize = 12.sp,
            color = Ref.Error,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    } else if (left <= 200) {
        Text(
            stringResource(R.string.chat_chars_left, left),
            fontSize = 12.sp,
            color = Ref.OnSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            textAlign = TextAlign.End,
        )
    }
    // The reply bar slides open above the input and folds away again (owner 2026-11-22: premium motion).
    AnimatedContent(
        targetState = replyTo,
        transitionSpec = {
            (fadeIn() + expandVertically(spring(dampingRatio = 0.85f, stiffness = 450f))) togetherWith
                (fadeOut() + shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)))
        },
        contentKey = { it?.id },
        label = "replyBar",
    ) { target ->
        if (target != null) Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .panel(RoundedCornerShape(18.dp))
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
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
            TextButton(onClick = onCancelReply) { Text(stringResource(R.string.action_cancel), color = Ref.Primary) }
        }
    }
    // Composer: text only. The round button on the left takes a snap (the reference's place for it).
    // Material 3 composer (owner 2026-11-22): a camera button, a tonal pill to type in, and a filled send button.
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RoundAction(
                icon = AppIcons.PhotoCamera,
                description = stringResource(R.string.snap_button),
                container = PLAIN,
                tint = Ref.OnSurface,
                onClick = onSnap,
            )
            PrivateTextInput(
                value = draft,
                onValueChange = {
                    draftState.value = it
                    if (it.isNotBlank()) onTyping()
                },
                hint = stringResource(R.string.chat_message_hint, peerUsername),
                enabled = open,
                modifier = Modifier.weight(1f),
            )
            val canSend = open && draft.isNotBlank() && !tooLong
            val haptics = LocalHapticFeedback.current
            // The send button grows in with a bounce once there is something to send (owner 2026-11-22).
            val ready by animateFloatAsState(if (canSend) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 520f), label = "sendReady")
            RoundAction(
                icon = AppIcons.ArrowUpward,
                description = stringResource(R.string.chat_send),
                container = if (canSend) Ref.Primary else Ref.Primary.copy(alpha = 0.4f),
                tint = Ref.OnPrimary,
                enabled = canSend,
                // A soft mint light around it once it can send.
                modifier = Modifier.graphicsLayer {
                    val scale = 0.82f + 0.18f * ready
                    scaleX = scale
                    scaleY = scale
                    rotationZ = (1f - ready) * -35f
                },
                onClick = {
                    if (onSend(draft)) {
                        // A light tap under the finger: the message is on its way.
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        draftState.value = ""
                    }
                },
            )
        }
    }
}

/**
 * Message input as a platform EditText, because Compose text fields cannot ask the keyboard not to
 * learn from what is typed (docs/07 §4 rule 6). The keyboard may still ignore the request. The text
 * is never put in saved instance state. A tonal pill with a primary outline while typing.
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
    val fieldOutline by animateColorAsState(if (focused) Ref.Primary.copy(alpha = 0.6f) else Color.Transparent, tween(120), label = "inputBorder")
    AndroidView(
        modifier = modifier
            .heightIn(min = 44.dp)
            .panel(RoundedCornerShape(22.dp))
            .border(1.dp, fieldOutline, RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 2.dp),
        factory = { context ->
            EditText(context).apply {
                setOnFocusChangeListener { _, has -> focused = has }
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                // Autocorrect and suggestions on (typing is easier); the keyboard is still asked not to learn.
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                maxLines = 5
                textSize = 16f
                setLineSpacing(0f, 1.1f)
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
            // Only what changed: setting the hint or colours again re-measures the field, on every letter.
            if (field.isEnabled != enabled) field.isEnabled = enabled
            if (field.hint?.toString() != hint) field.hint = hint
            if (field.currentTextColor != textColor) field.setTextColor(textColor)
            if (field.currentHintTextColor != hintColor) field.setHintTextColor(hintColor)
        },
    )
}

/** [RoundAction]'s container for a plain icon button with no fill, as in a Material top app bar. */
private val PLAIN = Color.Unspecified

/** Round button (composer and header): a plain icon button for [PLAIN], a filled circle otherwise. */
@Composable
private fun RoundAction(
    icon: ImageVector,
    description: String,
    container: Color,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val plain = container == PLAIN
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(if (plain) 48.dp else 44.dp)
            .pressScale(interaction, 0.9f)
            .clip(CircleShape)
            .then(if (plain) Modifier else Modifier.background(container))
            .clickable(interactionSource = interaction, indication = ripple(), enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(if (plain) 24.dp else 20.dp))
    }
}

/**
 * The chat's top bar (Material 3, in the spirit of Google Messages, owner 2026-11-22): back, the person's avatar and
 * name, then voice call, video call and the ⋮ menu (disappearing messages, walkie-talkie).
 */
@Composable
private fun ChatHeader(
    peerUsername: String,
    peerAvatar: String?,
    typing: Boolean,
    onBack: () -> Unit,
    onVoice: () -> Unit,
    onVideo: () -> Unit,
    onTalk: () -> Unit,
    onDisappearing: () -> Unit,
    onNickname: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), PLAIN, Ref.OnSurface, onBack)
        Row(
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InitialsAvatar(peerUsername, size = 40.dp, avatar = peerAvatar)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    peerUsername,
                    color = Ref.OnSurface,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                // Vault only knows when the other person is typing (no presence). The line slides open softly.
                AnimatedVisibility(
                    visible = typing,
                    enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 500f)) + fadeIn(),
                    exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(),
                ) {
                    Text(stringResource(R.string.chat_typing), color = Ref.Primary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        RoundAction(Icons.Filled.Call, stringResource(R.string.home_voice_call), PLAIN, Ref.OnSurfaceVariant, onVoice)
        RoundAction(AppIcons.Videocam, stringResource(R.string.home_video_call), PLAIN, Ref.OnSurfaceVariant, onVideo)
        var menu by remember { mutableStateOf(false) }
        Box {
            RoundAction(Icons.Filled.MoreVert, stringResource(R.string.chat_options), PLAIN, Ref.OnSurfaceVariant, { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(16.dp)) {
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
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.nickname_menu)) },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = { menu = false; onNickname() },
                )
            }
        }
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

/** The disappearing timer under the header; tapping it opens its screen. */
@Composable
private fun StatusStrip(disappearSeconds: Int?, onExpiry: () -> Unit) {
    // Only the timer pill, centred under the header, as in the mockup (owner 2026-11-22, "must be same").
    val pillPress = remember { MutableInteractionSource() }
    Box(modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .pressScale(pillPress, 0.92f)
                .panel(RoundedCornerShape(50))
                .clickable(interactionSource = pillPress, indication = ripple(), role = Role.Button, onClick = onExpiry)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(AppIcons.Timer, contentDescription = null, tint = Ref.OnSurface.copy(alpha = 0.85f), modifier = Modifier.size(14.dp))
            Text(expiryLabel(disappearSeconds), color = Ref.OnSurface.copy(alpha = 0.85f), fontSize = 12.sp, lineHeight = 16.sp)
        }
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
            modifier = Modifier.panel(RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
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
            modifier = Modifier.panel(RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(AppIcons.Timer, contentDescription = null, tint = Ref.OnSurfaceVariant, modifier = Modifier.size(14.dp))
            Text(text, color = Ref.OnSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

/** "X changed your name from A to B" (D-32); the text is JSON {"from","to"} written by the server. */
@Composable
private fun NicknameLine(m: ChatMessage, mine: Boolean, peerUsername: String) {
    val names = remember(m.text) {
        runCatching {
            val o = Json.parseToJsonElement(m.text).jsonObject
            (o["from"]?.jsonPrimitive?.content ?: "") to (o["to"]?.jsonPrimitive?.content ?: "")
        }.getOrDefault("" to "")
    }
    val text = if (mine) {
        stringResource(R.string.chat_nickname_line_mine, names.first, names.second)
    } else {
        stringResource(R.string.chat_nickname_line_theirs, peerUsername, names.first, names.second)
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.panel(RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(Icons.Filled.Edit, contentDescription = null, tint = Ref.OnSurfaceVariant, modifier = Modifier.size(14.dp))
            Text(text, color = Ref.OnSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
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
    peerTyping: Boolean,
    onRetry: (String) -> Unit,
    onDiscard: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    // Swipe a message to the right to reply (owner 2026-11-21, like WhatsApp): it follows the finger up to
    // [SWIPE_MAX], and past [SWIPE_REPLY] a light tick says letting go will reply.
    val swipeReplyPx = with(density) { SWIPE_REPLY.toPx() }
    val swipeMaxPx = with(density) { SWIPE_MAX.toPx() }
    // The message a tapped quote jumped to, lit up for a moment.
    var highlighted by remember { mutableStateOf<String?>(null) }
    val total = messages.size + outgoing.size
    // The list's last row: the "typing" bubble when it shows, else the newest message.
    val lastIndex = total - 1 + if (peerTyping) 1 else 0
    // "At the bottom" = the last row is on screen; then new messages and the typing bubble follow along.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= info.totalItemsCount - 2
        }
    }
    var unseen by remember { mutableIntStateOf(0) }
    var lastTotal by remember { mutableIntStateOf(total) }
    var lastOutgoing by remember { mutableIntStateOf(outgoing.size) }
    // Opening a chat jumps straight to the newest message. After that, a new message scrolls into view when
    // you are at the bottom or it is yours; while you read older ones it is counted on the ↓ button instead.
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(total, peerTyping) {
        if (total == 0) return@LaunchedEffect
        val mineAdded = outgoing.size > lastOutgoing
        val added = (total - lastTotal).coerceAtLeast(0)
        lastTotal = total
        lastOutgoing = outgoing.size
        when {
            !placed -> listState.scrollToItem(lastIndex)
            atBottom || mineAdded -> {
                unseen = 0
                listState.animateScrollToItem(lastIndex)
            }
            else -> unseen += added
        }
        placed = true
    }
    LaunchedEffect(atBottom) { if (atBottom) unseen = 0 }
    // The keyboard opening shrinks the list: keep the newest message in view above it.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible && placed && total > 0) listState.animateScrollToItem(lastIndex)
    }
    if (total == 0) {
        Column(
            modifier = modifier.fillMaxWidth().chatWallpaper().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            InitialsAvatar(peerUsername, size = 64.dp)
            Text(peerUsername, color = Ref.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.chat_empty), color = Ref.OnSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
        return
    }
    Box(modifier = modifier.fillMaxWidth()) {
    LazyColumn(
        state = listState,
        // The doodle background (owner 2026-11-23: "whatsapp chat ki trh"), drawn once and repeated.
        modifier = Modifier.fillMaxSize().chatWallpaper(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
    ) {
        // Indexed items: each row knows its place without searching the whole list (audit 2026-11-21, Q-2).
        itemsIndexed(messages, key = { _, it -> it.id }) { index, m ->
            val prev = messages.getOrNull(index - 1)
            // Messages one person sends close together sit together, and only the last shows the time
            // (owner 2026-11-21, like iMessage).
            val joinsPrev = joinsGroup(prev, m)
            val joinsNext = joinsGroup(m, messages.getOrNull(index + 1))
            val mine = m.senderId == myUserId
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (index == 0) 0.dp else if (joinsPrev) GROUPED_GAP else MESSAGE_GAP)
                    .animateItem()
                    // A message that arrives while the chat is open rises gently into place.
                    .entrance(play = placed && !mine, mine = mine),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (prev == null || !sameDay(prev.createdAtEpochMs, m.createdAtEpochMs)) DayChip(m.createdAtEpochMs)
                when (m.kind) {
                    MessageKind.MISSED_CALL -> MissedCallLine(m, mine = mine, onCallBack = onCallBack)
                    MessageKind.DISAPPEAR -> DisappearLine(m, mine = mine, peerUsername = peerUsername)
                    MessageKind.NICKNAME -> NicknameLine(m, mine = mine, peerUsername = peerUsername)
                    MessageKind.TEXT -> {
                        val onThis = reactions[m.id].orEmpty()
                        val quoted = m.replyToId?.let { id -> messages.find { it.id == id } }
                        var picking by remember { mutableStateOf(false) }
                        val swipe = remember { Animatable(0f) }
                        val glow by animateColorAsState(
                            if (highlighted == m.id) Ref.PrimaryContainer else Color.Transparent,
                            animationSpec = tween(400),
                            label = "highlight",
                        )
                        Box(modifier = Modifier.fillMaxWidth()) {
                        // The reply arrow that appears behind a message as it is swiped.
                        Icon(
                            AppIcons.Reply,
                            contentDescription = null,
                            tint = Ref.Primary,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .size(22.dp)
                                .graphicsLayer { alpha = (swipe.value / swipeReplyPx).coerceIn(0f, 1f) },
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset { IntOffset(swipe.value.roundToInt(), 0) }
                                .pointerInput(m.id) {
                                    var ticked = false
                                    detectHorizontalDragGestures(
                                        onDragStart = { ticked = false },
                                        onDragEnd = {
                                            if (swipe.value >= swipeReplyPx) onReply(m)
                                            scope.launch { swipe.animateTo(0f, tween(180)) }
                                        },
                                        onDragCancel = { scope.launch { swipe.animateTo(0f, tween(180)) } },
                                    ) { change, amount ->
                                        val next = (swipe.value + amount).coerceIn(0f, swipeMaxPx)
                                        if (next > 0f || swipe.value > 0f) change.consume()
                                        scope.launch { swipe.snapTo(next) }
                                        if (!ticked && next >= swipeReplyPx) {
                                            ticked = true
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                }
                                .background(glow, RoundedCornerShape(12.dp)),
                            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (quoted != null) {
                                QuotedMessage(
                                    who = if (quoted.senderId == myUserId) stringResource(R.string.chat_you) else peerUsername,
                                    text = if (quoted.kind == MessageKind.MISSED_CALL) stringResource(R.string.chat_missed_call_short) else quoted.text,
                                    onClick = {
                                        // Tap a quote to go to the message it answers, lit up for a moment.
                                        val target = messages.indexOfFirst { it.id == quoted.id }
                                        if (target >= 0) {
                                            scope.launch {
                                                listState.animateScrollToItem(target)
                                                highlighted = quoted.id
                                                delay(1_600)
                                                if (highlighted == quoted.id) highlighted = null
                                            }
                                        }
                                    },
                                )
                            }
                            Box {
                                Bubble(
                                    m.text,
                                    mine = mine,
                                    tail = !joinsNext,
                                    onLongPress = { picking = true },
                                    // Double-tap for a quick ❤️ (owner 2026-11-21); again takes it away.
                                    onDoubleTap = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onReact(m.id, QUICK_REACTIONS.first())
                                    },
                                )
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
                            if (!joinsNext) MetaRow(
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
        }
        itemsIndexed(outgoing, key = { _, it -> it.clientMessageId }) { oIndex, o ->
            val sending = o.status == OutgoingStatus.SENDING
            val afterMine = oIndex > 0 || messages.lastOrNull()?.senderId == myUserId
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (total == 1) 0.dp else if (afterMine) GROUPED_GAP else MESSAGE_GAP)
                    .animateItem()
                    // A message you send pops up from the box you typed it in.
                    .entrance(play = placed, mine = true),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Bubble(o.text, mine = true, faded = sending, failed = !sending, tail = oIndex == outgoing.lastIndex)
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
        if (peerTyping) {
            item(key = "typing") {
                // The other person is typing: three dots in a bubble, as in Apple Messages.
                Box(Modifier.fillMaxWidth().padding(top = MESSAGE_GAP).animateItem(), contentAlignment = Alignment.CenterStart) {
                    Box(
                        modifier = Modifier
                            .panel(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp))
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) { TypingDots(color = Ref.OnSurfaceVariant) }
                }
            }
        }
    }
    // Reading older messages: a round ↓ button brings you back to the newest, with how many came meanwhile.
    AnimatedVisibility(
        visible = !atBottom,
        enter = fadeIn(tween(Motion.FAST)) + scaleIn(tween(Motion.FAST), initialScale = 0.8f),
        exit = fadeOut(tween(Motion.FAST)) + scaleOut(tween(Motion.FAST), targetScale = 0.8f),
        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
    ) {
        Box {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .panel(CircleShape, strong = true, elevation = 6.dp)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.chat_to_newest)) {
                        unseen = 0
                        scope.launch { listState.animateScrollToItem(lastIndex) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.chat_to_newest), tint = Ref.Primary)
            }
            if (unseen > 0) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp)
                        .size(18.dp).background(Ref.Primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text(if (unseen > 9) "9+" else "$unseen", color = Ref.OnPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
    }
}

private enum class MetaStatus { SENT, READ, DISAPPEARING }

/** Time under a message, with ✓ (sent), "· Read" (read) or ⏱ (will disappear). */
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
            // "7:03 pm · Read", as in the mockup (owner 2026-11-22).
            MetaStatus.READ -> Text("· " + stringResource(R.string.chat_status_read), color = Ref.OnSurfaceVariant, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp, fontWeight = FontWeight.Medium)
            MetaStatus.DISAPPEARING -> Icon(AppIcons.Timer, contentDescription = stringResource(R.string.chat_status_disappearing), tint = Ref.OnSurfaceVariant, modifier = Modifier.size(12.dp))
            null -> Unit
        }
    }
}

/** The message a reply answers, shown small above its bubble: who wrote it and its first line. */
@Composable
private fun QuotedMessage(who: String, text: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .widthIn(max = 290.dp)
            .padding(bottom = 2.dp)
            .panel(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = stringResource(R.string.chat_go_to_message), onClick = onClick)
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
                .panel(RoundedCornerShape(50))
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
 * Message bubbles: theirs on the left in light grey, mine on the right in the primary container colour, with a
 * 6 dp corner next to the sender, at most 82 % of the width.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun Bubble(
    text: String,
    mine: Boolean,
    faded: Boolean = false,
    failed: Boolean = false,
    tail: Boolean = true,
    onLongPress: (() -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null,
) {
    // Only the last bubble of a group has the small corner next to the sender.
    val shape = when {
        !tail -> RoundedCornerShape(20.dp)
        mine -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
        else -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)
    }
    val press = if (onLongPress != null) {
        Modifier.combinedClickable(
            onClick = {},
            onLongClickLabel = stringResource(R.string.chat_react),
            onLongClick = onLongPress,
            onDoubleClick = onDoubleTap,
        )
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
    // Material 3, in the spirit of Google Messages (owner 2026-11-22): mine in the primary container colour, theirs
    // in a light grey tonal surface, a failed one in the error container colour.
    val container = when {
        failed -> Ref.ErrorContainer
        mine -> Ref.PrimaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val look = Modifier.clip(shape).background(container.copy(alpha = if (faded) 0.8f else 1f))
    val content = when {
        failed -> Ref.OnErrorContainer
        mine -> Ref.OnPrimaryContainer
        else -> Ref.OnSurface
    }
    // A plain Box with a layout limit instead of BoxWithConstraints: that one composes each bubble a second
    // time, which made scrolling long chats heavier (owner 2026-11-21: lag).
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            color = content.copy(alpha = alpha),
            modifier = Modifier
                .layout { measurable, constraints ->
                    // At most 82 % of the chat's width.
                    val limit = (constraints.maxWidth * 0.82f).roundToInt()
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = limit))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .then(look)
                .then(press)
                .padding(horizontal = 15.dp, vertical = 11.dp),
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
        // The picker rises in, and its emoji follow one after another (owner 2026-11-22: iOS-like motion).
        Box(modifier = Modifier.staggeredAppear(0).panel(RoundedCornerShape(28.dp), strong = true, elevation = 8.dp)) {
            Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                QUICK_REACTIONS.forEachIndexed { i, emoji ->
                    Box(
                        modifier = Modifier
                            .staggeredAppear(i + 1)
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
    Box(
        // It rises in when the first reaction lands, and pops when one is added or changed.
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .offset(y = (-6).dp)
            .staggeredAppear(0)
            .popOnChange(reactions.map { it.emoji })
            .panel(RoundedCornerShape(12.dp), strong = true)
            .clickable(onClick = onClick),
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
        Column(modifier = Modifier.fillMaxSize().screenBackground().safeDrawingPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RoundAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), PLAIN, Ref.OnSurface, onBack)
                Text(
                    stringResource(R.string.expiry_title),
                    color = Ref.OnSurface,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .panel(RoundedCornerShape(Radii.card))
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
                            .panel(RoundedCornerShape(Radii.card))
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
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
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

/** How far a message is swiped before letting go replies to it, and how far it follows the finger. */
private val SWIPE_REPLY = 56.dp
private val SWIPE_MAX = 80.dp

/** Space between groups of messages, and between messages of one group. */
private val MESSAGE_GAP = 12.dp
private val GROUPED_GAP = 3.dp

/** Messages within this time of each other from the same person form one group. */
private const val GROUP_WINDOW_MS = 3 * 60_000L

/** Whether [b] follows [a] in the same group: both text, same person, same day, close in time. */
private fun joinsGroup(a: ChatMessage?, b: ChatMessage?): Boolean =
    a != null && b != null &&
        a.kind == MessageKind.TEXT && b.kind == MessageKind.TEXT &&
        a.senderId == b.senderId &&
        sameDay(a.createdAtEpochMs, b.createdAtEpochMs) &&
        kotlin.math.abs(b.createdAtEpochMs - a.createdAtEpochMs) <= GROUP_WINDOW_MS

/**
 * A new message's way in (owner 2026-11-21): it rises a little, grows from its sender's side and fades in,
 * with a soft spring. Messages already there when the chat opens do not move.
 */
@Composable
private fun Modifier.entrance(play: Boolean, mine: Boolean): Modifier {
    val progress = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (play) progress.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
    }
    return graphicsLayer {
        val v = progress.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * 28.dp.toPx()
        val scale = 0.86f + 0.14f * v
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin(if (mine) 1f else 0f, 1f)
    }
}

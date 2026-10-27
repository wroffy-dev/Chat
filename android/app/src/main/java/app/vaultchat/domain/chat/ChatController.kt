package app.vaultchat.domain.chat

import app.vaultchat.domain.realtime.RealtimeSignal
import app.vaultchat.domain.realtime.RealtimeSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class ChatPhase { ENTERING, OPEN, LEFT, SESSION_ENDED, UNAVAILABLE }

enum class ChatNotice {
    /** Messages disappeared because one of you left the chat (or they reached 24 hours). */
    CLEARED,
    /** The chat was cleared while a message was being sent; its text was put back in the input. */
    CLEARED_NOT_SENT,
    /** The app was away long enough that the server counted it as leaving the chat (D-02). */
    LEFT_WHILE_AWAY,
    TOO_LONG,
    RATE_LIMITED,
    NETWORK,
}

enum class OutgoingStatus { SENDING, FAILED }

/** A message on its way to the server. [epoch] is the one it was composed in, kept for retries. */
data class Outgoing(val clientMessageId: String, val text: String, val epoch: Long, val status: OutgoingStatus)

data class ChatState(
    val phase: ChatPhase = ChatPhase.ENTERING,
    val messages: List<ChatMessage> = emptyList(),
    val outgoing: List<Outgoing> = emptyList(),
    val notice: ChatNotice? = null,
    /** Text the server refused because the chat was cleared; the UI puts it back into the input once. */
    val restoredDraft: String? = null,
)

/**
 * Chat on the device (docs/07-chat-and-disappearing-messages.md §4), independent of Android so it
 * can be tested on a plain JVM. Messages are held only in this object's memory. The server is the
 * source of truth: the list is re-read on open and polled while the chat is visible, so a stale copy
 * can never bring deleted messages back.
 *
 * Exits (D-01 option D, D-02): [leave] on Back; otherwise the presence lease lapses about a minute
 * after heartbeats stop (app in the background, killed, or offline) and the server exits for us.
 *
 * Two signals drive it: [setForeground] (the app is in front: the lease is renewed, the list polled)
 * and [setOnScreen] (the chat itself is displayed: only then do messages count as seen, because seen
 * messages are what an exit deletes). A call shown on top of the chat keeps the chat open but unseen.
 */
class ChatController(
    private val repo: ChatRepository,
    private val pendingLeaves: PendingLeaveStore,
    private val conversationId: String,
    private val myUserId: String,
    /** The screen's scope: polling and sending stop with it. */
    private val scope: CoroutineScope,
    /** Outlives the screen, so the exit request is still sent after Back closes it. */
    private val outliveScope: CoroutineScope,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** Live "chat changed" signals; with them connected the list is re-read at once and polled slowly. */
    private val signals: RealtimeSignals? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val POLL_MS = 2_000L
        /** Fallback polling while live signals are connected. */
        const val SIGNALLED_POLL_MS = 10_000L
    }

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val foreground = MutableStateFlow(true)
    @Volatile private var onScreen = true
    private val syncLock = Mutex()
    private var loop: Job? = null
    private var signalJob: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // Owned by the loop and by sync() under syncLock.
    private var leaseId: String? = null
    private var heartbeatMs = 20_000L
    private var lastBeatMs = 0L
    private var beatNow = false
    private var epoch = 0L
    private var syncedSeq = 0L
    private var readSeq = 0L

    fun start() {
        if (loop != null) return
        signalJob = signals?.let { live ->
            scope.launch {
                live.signals.collect { if (it is RealtimeSignal.ChatChanged && it.conversationId == conversationId) wake.trySend(Unit) }
            }
        }
        loop = scope.launch {
            try {
                // An earlier exit that never reached the server must land before this entry.
                flushPendingLeave(repo, pendingLeaves)
                while (isActive) {
                    if (!foreground.value) {
                        foreground.first { it }
                        beatNow = true // renew (or learn we left) straight away
                    }
                    if (leaseId == null && !enter()) {
                        if (_state.value.phase.isFinal()) return@launch
                        delay(POLL_MS)
                        continue
                    }
                    if ((beatNow || nowMs() - lastBeatMs >= heartbeatMs) && !heartbeat()) continue
                    sync()
                    if (_state.value.phase.isFinal()) return@launch
                    // Sleep until the next poll or heartbeat is due, or a signal says something changed.
                    val pollMs = if (signals?.connected?.value == true) SIGNALLED_POLL_MS else POLL_MS
                    val untilBeat = lastBeatMs + heartbeatMs - nowMs()
                    withTimeoutOrNull(minOf(pollMs, untilBeat).coerceAtLeast(1)) { wake.receive() }
                }
            } finally {
                signalJob?.cancel()
            }
        }
    }

    /** The app is (not) in the foreground. In the background nothing is polled or renewed (D-02). */
    fun setForeground(inForeground: Boolean) {
        foreground.value = inForeground
    }

    /** The chat is (not) what the user is looking at. Coming back marks what is displayed as seen. */
    fun setOnScreen(displayed: Boolean) {
        val was = onScreen
        onScreen = displayed
        if (displayed && !was && _state.value.phase == ChatPhase.OPEN) scope.launch { syncLock.withLock { markReadIfVisible() } }
    }

    /** Queues [raw] for sending. Returns true when accepted, so the UI can clear its input. */
    fun send(raw: String): Boolean {
        val text = ChatText.normalize(raw) ?: return false
        if (ChatText.tooLong(text)) {
            _state.update { it.copy(notice = ChatNotice.TOO_LONG) }
            return false
        }
        if (_state.value.phase != ChatPhase.OPEN) return false
        val out = Outgoing(newId(), text, epoch, OutgoingStatus.SENDING)
        _state.update { it.copy(outgoing = it.outgoing + out) }
        scope.launch { deliver(out) }
        return true
    }

    /** Retries a failed send with the same id and epoch, so it is never duplicated or resurrected. */
    fun retry(clientMessageId: String) {
        val out = _state.value.outgoing.find { it.clientMessageId == clientMessageId && it.status == OutgoingStatus.FAILED } ?: return
        val again = out.copy(status = OutgoingStatus.SENDING)
        _state.update { s -> s.copy(outgoing = s.outgoing.map { if (it.clientMessageId == clientMessageId) again else it }) }
        scope.launch { deliver(again) }
    }

    fun discard(clientMessageId: String) {
        _state.update { s -> s.copy(outgoing = s.outgoing.filterNot { it.clientMessageId == clientMessageId }) }
    }

    fun draftRestored() = _state.update { it.copy(restoredDraft = null) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /**
     * Explicit exit (Back, toolbar, closing the screen). Drops every message from memory at once;
     * the server request is stored first so it is retried if it cannot be sent now.
     */
    fun leave() {
        val phase = _state.value.phase
        if (phase == ChatPhase.LEFT) return
        loop?.cancel()
        _state.value = ChatState(phase = ChatPhase.LEFT)
        if (phase == ChatPhase.SESSION_ENDED || phase == ChatPhase.UNAVAILABLE) return
        pendingLeaves.save(PendingLeave(conversationId, newId()))
        outliveScope.launch { flushPendingLeave(repo, pendingLeaves) }
    }

    // ---------------------------------------------------------------------------------------------

    private suspend fun enter(): Boolean = when (val r = repo.enter(conversationId)) {
        is ChatResult.Ok -> {
            leaseId = r.value.leaseId
            heartbeatMs = r.value.heartbeatSeconds * 1000L
            lastBeatMs = nowMs()
            syncLock.withLock {
                epoch = r.value.epoch
                syncedSeq = 0
                _state.update { it.copy(messages = emptyList()) }
            }
            sync()
            if (!_state.value.phase.isFinal()) _state.update { it.copy(phase = ChatPhase.OPEN) }
            true
        }
        is ChatResult.Failure -> {
            failed(r.reason)
            false
        }
    }

    /** False when the lease is gone: the server has already applied the exit, so start over. */
    private suspend fun heartbeat(): Boolean {
        val id = leaseId ?: return false
        beatNow = false
        lastBeatMs = nowMs()
        return when (val r = repo.heartbeat(conversationId, id)) {
            is ChatResult.Ok -> true
            is ChatResult.Failure -> when (r.reason) {
                ChatFailure.LEASE_EXPIRED -> {
                    leaseId = null
                    _state.update { it.copy(messages = emptyList(), outgoing = emptyList(), notice = ChatNotice.LEFT_WHILE_AWAY) }
                    false
                }
                ChatFailure.NETWORK, ChatFailure.UNEXPECTED, ChatFailure.RATE_LIMITED -> true // try again next beat
                else -> {
                    failed(r.reason)
                    false
                }
            }
        }
    }

    private suspend fun sync() = syncLock.withLock {
        while (true) {
            val page = when (val r = repo.history(conversationId, syncedSeq, epoch)) {
                is ChatResult.Ok -> r.value
                is ChatResult.Failure -> {
                    if (r.reason == ChatFailure.SESSION_ENDED || r.reason == ChatFailure.NOT_FOUND) failed(r.reason)
                    return@withLock
                }
            }
            if (page.cleared) {
                // Our epoch is stale: replace everything with what the server still has.
                epoch = page.epoch
                val kept = page.items.map { it.id }.toSet()
                _state.update { s ->
                    val lost = s.messages.any { it.id !in kept }
                    s.copy(messages = page.items.sortedBy { it.seq }, notice = if (lost) ChatNotice.CLEARED else s.notice)
                }
            } else {
                _state.update { s -> s.copy(messages = merge(s.messages, page.items)) }
            }
            syncedSeq = maxOf(if (page.cleared) 0 else syncedSeq, page.items.maxOfOrNull { it.seq } ?: 0)
            if (!page.hasMore) break
        }
        markReadIfVisible()
    }

    /** Only messages actually on screen count as seen: seen messages are the ones an exit deletes. */
    private suspend fun markReadIfVisible() {
        if (!foreground.value || !onScreen) return
        val top = _state.value.messages.maxOfOrNull { it.seq } ?: return
        if (top <= readSeq) return
        if (repo.markRead(conversationId, top) is ChatResult.Ok) readSeq = top
    }

    private suspend fun deliver(out: Outgoing) {
        when (val r = repo.send(conversationId, out.clientMessageId, out.epoch, out.text)) {
            is ChatResult.Ok -> _state.update { s ->
                val sent = ChatMessage(r.value.id, r.value.seq, myUserId, out.text, r.value.createdAtEpochMs)
                s.copy(outgoing = s.outgoing.without(out), messages = merge(s.messages, listOf(sent)))
            }
            is ChatResult.Failure -> when (r.reason) {
                ChatFailure.CLEARED -> {
                    _state.update { s -> s.copy(outgoing = s.outgoing.without(out), restoredDraft = out.text, notice = ChatNotice.CLEARED_NOT_SENT) }
                    sync()
                }
                ChatFailure.TOO_LONG, ChatFailure.INVALID ->
                    _state.update { s -> s.copy(outgoing = s.outgoing.without(out), notice = ChatNotice.TOO_LONG) }
                ChatFailure.RATE_LIMITED -> markFailed(out, ChatNotice.RATE_LIMITED)
                ChatFailure.SESSION_ENDED, ChatFailure.NOT_FOUND -> failed(r.reason)
                else -> markFailed(out, ChatNotice.NETWORK)
            }
        }
    }

    private fun markFailed(out: Outgoing, notice: ChatNotice) = _state.update { s ->
        s.copy(
            outgoing = s.outgoing.map { if (it.clientMessageId == out.clientMessageId) it.copy(status = OutgoingStatus.FAILED) else it },
            notice = notice,
        )
    }

    private fun failed(reason: ChatFailure) {
        when (reason) {
            ChatFailure.SESSION_ENDED -> _state.value = ChatState(phase = ChatPhase.SESSION_ENDED)
            ChatFailure.NOT_FOUND -> _state.value = ChatState(phase = ChatPhase.UNAVAILABLE)
            else -> _state.update { it.copy(notice = ChatNotice.NETWORK) }
        }
    }

    private fun merge(current: List<ChatMessage>, incoming: List<ChatMessage>): List<ChatMessage> {
        if (incoming.isEmpty()) return current
        val bySeq = current.associateBy { it.seq }.toMutableMap()
        incoming.forEach { bySeq[it.seq] = it }
        return bySeq.values.sortedBy { it.seq }
    }

    private fun List<Outgoing>.without(out: Outgoing) = filterNot { it.clientMessageId == out.clientMessageId }

    private fun ChatPhase.isFinal() = this == ChatPhase.LEFT || this == ChatPhase.SESSION_ENDED || this == ChatPhase.UNAVAILABLE
}

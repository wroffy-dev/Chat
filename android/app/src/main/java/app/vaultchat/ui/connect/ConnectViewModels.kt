package app.vaultchat.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vaultchat.domain.connection.ConnResult
import app.vaultchat.domain.connection.Connection
import app.vaultchat.domain.connection.ConnectionFailure
import app.vaultchat.domain.connection.ConnectionRepository
import app.vaultchat.domain.connection.ConnectionRequest
import app.vaultchat.domain.connection.QrCode
import app.vaultchat.domain.connection.QrPayload
import app.vaultchat.domain.connection.RequestDirection
import app.vaultchat.domain.connection.RequestStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polling interval while a pairing screen is visible. Live push (WebSocket) arrives in a later
 * phase; until then screens poll only while in the foreground (started/stopped by the UI).
 */
private const val POLL_MS = 3_000L

// ------------------------------------------------------------------------------------------------
// Inbox
// ------------------------------------------------------------------------------------------------

data class InboxUiState(
    val loading: Boolean = true,
    val connection: Connection? = null,
    val incoming: List<ConnectionRequest> = emptyList(),
    val outgoing: List<ConnectionRequest> = emptyList(),
    val busyRequestId: String? = null,
    val failure: ConnectionFailure? = null,
    val sessionEnded: Boolean = false,
)

class InboxViewModel(private val repo: ConnectionRepository) : ViewModel() {
    private val _state = MutableStateFlow(InboxUiState())
    val state: StateFlow<InboxUiState> = _state.asStateFlow()
    private var poller: Job? = null

    fun startPolling() {
        if (poller?.isActive == true) return
        poller = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(POLL_MS)
            }
        }
    }

    fun stopPolling() {
        poller?.cancel()
    }

    private suspend fun refresh() {
        val connection = repo.connection()
        val requests = repo.requests(status = RequestStatus.PENDING)
        _state.update { s ->
            val failure = (connection as? ConnResult.Failure)?.reason ?: (requests as? ConnResult.Failure)?.reason
            val list = (requests as? ConnResult.Ok)?.value
            s.copy(
                loading = false,
                connection = (connection as? ConnResult.Ok)?.value ?: s.connection,
                incoming = list?.filter { it.direction == RequestDirection.INCOMING } ?: s.incoming,
                outgoing = list?.filter { it.direction == RequestDirection.OUTGOING } ?: s.outgoing,
                failure = failure,
                sessionEnded = failure == ConnectionFailure.SESSION_ENDED,
            )
        }
    }

    fun accept(id: String) = act(id) { repo.accept(id) }
    fun reject(id: String) = act(id) { repo.reject(id) }
    fun cancel(id: String) = act(id) { repo.cancel(id) }

    private fun act(id: String, block: suspend () -> ConnResult<*>) {
        if (_state.value.busyRequestId != null) return
        _state.update { it.copy(busyRequestId = id, failure = null) }
        viewModelScope.launch {
            val result = block()
            _state.update {
                it.copy(
                    busyRequestId = null,
                    failure = (result as? ConnResult.Failure)?.reason,
                    sessionEnded = (result as? ConnResult.Failure)?.reason == ConnectionFailure.SESSION_ENDED,
                )
            }
            refresh()
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Show my QR
// ------------------------------------------------------------------------------------------------

data class ShowQrUiState(
    val qr: QrCode? = null,
    val secondsLeft: Int = 0,
    val incoming: ConnectionRequest? = null,
    val deciding: Boolean = false,
    val failure: ConnectionFailure? = null,
    val connected: Boolean = false,
    val sessionEnded: Boolean = false,
)

class ShowQrViewModel(
    private val repo: ConnectionRepository,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(ShowQrUiState())
    val state: StateFlow<ShowQrUiState> = _state.asStateFlow()
    private var loop: Job? = null

    /** Refreshes ~15 s before expiry so the code on screen is always usable (docs/06 §2). */
    private val refreshBeforeExpiryMs = 15_000L

    fun start() {
        if (loop?.isActive == true) return
        loop = viewModelScope.launch {
            var lastPoll = 0L
            while (isActive) {
                val s = _state.value
                if (s.connected || s.sessionEnded) return@launch
                val qr = s.qr
                if (qr == null || qr.expiresAtEpochMs - nowMs() <= refreshBeforeExpiryMs) {
                    // While a request is waiting for an answer there is no need for a new code.
                    if (s.incoming == null) generate()
                }
                if (nowMs() - lastPoll >= POLL_MS) {
                    lastPoll = nowMs()
                    pollIncoming()
                }
                _state.update { st ->
                    st.copy(secondsLeft = st.qr?.let { ((it.expiresAtEpochMs - nowMs()) / 1000).toInt().coerceAtLeast(0) } ?: 0)
                }
                delay(1_000)
            }
        }
    }

    fun stop() {
        loop?.cancel()
    }

    private suspend fun generate() {
        when (val r = repo.generateQr()) {
            is ConnResult.Ok -> _state.update { it.copy(qr = r.value, failure = null) }
            is ConnResult.Failure -> _state.update {
                it.copy(
                    failure = r.reason,
                    connected = r.reason == ConnectionFailure.ALREADY_CONNECTED,
                    sessionEnded = r.reason == ConnectionFailure.SESSION_ENDED,
                )
            }
        }
    }

    private suspend fun pollIncoming() {
        val r = repo.requests(RequestDirection.INCOMING, RequestStatus.PENDING)
        if (r is ConnResult.Ok) _state.update { it.copy(incoming = r.value.firstOrNull()) }
    }

    fun accept() = decide(connectsOnSuccess = true) { id -> repo.accept(id).failureOrNull() }

    fun reject() = decide(connectsOnSuccess = false) { id -> repo.reject(id).failureOrNull() }

    private fun decide(connectsOnSuccess: Boolean, block: suspend (String) -> ConnectionFailure?) {
        val req = _state.value.incoming ?: return
        if (_state.value.deciding) return
        _state.update { it.copy(deciding = true, failure = null) }
        viewModelScope.launch {
            val failure = block(req.id)
            _state.update {
                it.copy(
                    deciding = false,
                    incoming = null,
                    failure = failure,
                    connected = it.connected || (connectsOnSuccess && failure == null),
                    sessionEnded = failure == ConnectionFailure.SESSION_ENDED,
                )
            }
        }
    }
}

private fun ConnResult<*>.failureOrNull(): ConnectionFailure? = (this as? ConnResult.Failure)?.reason

// ------------------------------------------------------------------------------------------------
// Scan QR
// ------------------------------------------------------------------------------------------------

sealed interface ScanStage {
    data object Scanning : ScanStage
    data object Submitting : ScanStage
    data class Waiting(val request: ConnectionRequest) : ScanStage
    data object Connected : ScanStage
    data class Failed(val reason: ScanFailure) : ScanStage
}

enum class ScanFailure { NOT_OUR_CODE, REJECTED, EXPIRED, CANCELLED, SERVER }

data class ScanUiState(
    val stage: ScanStage = ScanStage.Scanning,
    val serverFailure: ConnectionFailure? = null,
    val sessionEnded: Boolean = false,
)

class ScanQrViewModel(private val repo: ConnectionRepository) : ViewModel() {
    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()
    private var poller: Job? = null
    private var lastRejectedText: String? = null

    /** Called for every decoded QR text; only the first valid code while scanning is used. */
    fun onDecoded(text: String) {
        if (_state.value.stage != ScanStage.Scanning) return
        val token = QrPayload.tokenFrom(text)
        if (token == null) {
            if (text != lastRejectedText) {
                lastRejectedText = text
                _state.update { it.copy(stage = ScanStage.Failed(ScanFailure.NOT_OUR_CODE)) }
            }
            return
        }
        _state.update { it.copy(stage = ScanStage.Submitting) }
        viewModelScope.launch {
            when (val r = repo.redeem(token)) {
                is ConnResult.Ok -> {
                    _state.update { it.copy(stage = ScanStage.Waiting(r.value)) }
                    startPolling()
                }
                is ConnResult.Failure -> _state.update {
                    it.copy(
                        stage = if (r.reason == ConnectionFailure.ALREADY_CONNECTED) ScanStage.Connected else ScanStage.Failed(ScanFailure.SERVER),
                        serverFailure = r.reason,
                        sessionEnded = r.reason == ConnectionFailure.SESSION_ENDED,
                    )
                }
            }
        }
    }

    fun startPolling() {
        if (poller?.isActive == true || _state.value.stage !is ScanStage.Waiting) return
        poller = viewModelScope.launch {
            while (isActive) {
                delay(POLL_MS)
                val waiting = _state.value.stage as? ScanStage.Waiting ?: return@launch
                val r = repo.requests(RequestDirection.OUTGOING)
                val current = (r as? ConnResult.Ok)?.value?.firstOrNull { it.id == waiting.request.id } ?: continue
                val next = when (current.status) {
                    RequestStatus.PENDING -> null
                    RequestStatus.ACCEPTED -> ScanStage.Connected
                    RequestStatus.REJECTED -> ScanStage.Failed(ScanFailure.REJECTED)
                    RequestStatus.EXPIRED -> ScanStage.Failed(ScanFailure.EXPIRED)
                    RequestStatus.CANCELLED -> ScanStage.Failed(ScanFailure.CANCELLED)
                }
                if (next != null) {
                    _state.update { it.copy(stage = next) }
                    return@launch
                }
            }
        }
    }

    fun stopPolling() {
        poller?.cancel()
    }

    fun cancel() {
        val waiting = _state.value.stage as? ScanStage.Waiting ?: return
        viewModelScope.launch {
            repo.cancel(waiting.request.id)
            poller?.cancel()
            _state.update { it.copy(stage = ScanStage.Failed(ScanFailure.CANCELLED)) }
        }
    }

    fun scanAgain() {
        _state.update { ScanUiState() }
    }
}

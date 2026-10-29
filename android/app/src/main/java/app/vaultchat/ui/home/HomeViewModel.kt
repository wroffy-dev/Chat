package app.vaultchat.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vaultchat.domain.auth.AuthRepository
import app.vaultchat.domain.auth.MeResult
import app.vaultchat.domain.connection.ConnResult
import app.vaultchat.domain.connection.Connection
import app.vaultchat.domain.connection.ConnectionFailure
import app.vaultchat.domain.connection.ConnectionRepository
import app.vaultchat.domain.realtime.RealtimeSignal
import app.vaultchat.domain.realtime.RealtimeSignals
import app.vaultchat.domain.realtime.pollWithSignals
import app.vaultchat.platform.push.Push
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HomeNavigation { LOGIN, CHANGE_PASSWORD }

enum class ChatFilter { ALL, UNREAD }

data class HomeUiState(
    val username: String? = null,
    /** Every connection, newest activity first (D-21). */
    val chats: List<Connection> = emptyList(),
    val loading: Boolean = true,
    /** Shown when the server could not be reached; the last list is still displayed. */
    val offline: Boolean = false,
    val navigation: HomeNavigation? = null,
    /** Filters this user's own chat list by name; there is no user search (docs/01). */
    val query: String = "",
    val filter: ChatFilter = ChatFilter.ALL,
) {
    val visibleChats: List<Connection>
        get() = chats
            .filter { filter == ChatFilter.ALL || it.unreadCount > 0 }
            .filter { query.isBlank() || it.peerUsername.contains(query.trim(), ignoreCase = true) }
}

/** Fallback refresh of the chat list when live signals are down, and while they are up. */
private const val POLL_MS = 10_000L
private const val SIGNALLED_POLL_MS = 60_000L

class HomeViewModel(
    private val auth: AuthRepository,
    private val connections: ConnectionRepository,
    private val signals: RealtimeSignals? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState(username = auth.currentUser()?.username))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()
    private var poller: Job? = null

    /** Confirms the session, then keeps the chat list fresh while the screen is shown. */
    fun start() {
        refresh()
        if (poller?.isActive == true) return
        poller = viewModelScope.launch {
            pollWithSignals(signals, this::changesList, POLL_MS, SIGNALLED_POLL_MS) {
                loadChats()
                _state.value.navigation == null
            }
        }
    }

    fun stop() {
        poller?.cancel()
    }

    /** Confirms the session with the server; a revoked or disabled session sends the user to login. */
    fun refresh() {
        _state.update { it.copy(offline = false) }
        viewModelScope.launch {
            val result = auth.me()
            _state.update {
                when (result) {
                    is MeResult.Success -> it.copy(username = result.user.username)
                    MeResult.PasswordChangeRequired -> it.copy(loading = false, navigation = HomeNavigation.CHANGE_PASSWORD)
                    MeResult.SessionEnded -> it.copy(loading = false, navigation = HomeNavigation.LOGIN)
                    MeResult.Network, MeResult.Unexpected -> it.copy(loading = false, offline = true)
                }
            }
            if (result is MeResult.Success) loadChats()
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query.take(32)) }

    fun setFilter(filter: ChatFilter) = _state.update { it.copy(filter = filter) }

    fun logout() {
        viewModelScope.launch {
            Push.forgetOnDevice()
            auth.logout()
            _state.update { it.copy(navigation = HomeNavigation.LOGIN) }
        }
    }

    private suspend fun loadChats() {
        when (val r = connections.connections()) {
            is ConnResult.Ok -> _state.update { s ->
                s.copy(
                    loading = false,
                    offline = false,
                    chats = r.value.sortedWith(compareByDescending<Connection> { it.lastMessageAtEpochMs ?: it.sinceEpochMs }),
                )
            }
            is ConnResult.Failure -> _state.update {
                when (r.reason) {
                    ConnectionFailure.SESSION_ENDED -> it.copy(loading = false, navigation = HomeNavigation.LOGIN)
                    else -> it.copy(loading = false, offline = true)
                }
            }
        }
    }

    private fun changesList(signal: RealtimeSignal): Boolean =
        signal is RealtimeSignal.ChatChanged || signal is RealtimeSignal.ConnectionChanged
}

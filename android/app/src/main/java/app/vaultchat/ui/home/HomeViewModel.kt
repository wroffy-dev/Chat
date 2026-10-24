package app.vaultchat.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vaultchat.domain.auth.AuthRepository
import app.vaultchat.domain.auth.MeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HomeNavigation { LOGIN, CHANGE_PASSWORD }

data class HomeUiState(
    val username: String? = null,
    val loading: Boolean = true,
    /** Shown when the server could not be reached; the cached username is still displayed. */
    val offline: Boolean = false,
    val navigation: HomeNavigation? = null,
)

class HomeViewModel(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState(username = auth.currentUser()?.username))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Confirms the session with the server; a revoked or disabled session sends the user to login. */
    fun refresh() {
        _state.update { it.copy(loading = true, offline = false) }
        viewModelScope.launch {
            val result = auth.me()
            _state.update {
                when (result) {
                    is MeResult.Success -> it.copy(username = result.user.username, loading = false)
                    MeResult.PasswordChangeRequired -> it.copy(loading = false, navigation = HomeNavigation.CHANGE_PASSWORD)
                    MeResult.SessionEnded -> it.copy(loading = false, navigation = HomeNavigation.LOGIN)
                    MeResult.Network, MeResult.Unexpected -> it.copy(loading = false, offline = true)
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            auth.logout()
            _state.update { it.copy(navigation = HomeNavigation.LOGIN) }
        }
    }
}

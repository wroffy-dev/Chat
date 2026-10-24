package app.vaultchat.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vaultchat.data.network.ApiClient
import app.vaultchat.data.network.ServerHealth
import app.vaultchat.domain.auth.AuthRepository
import app.vaultchat.domain.auth.LoginFailure
import app.vaultchat.domain.auth.LoginInput
import app.vaultchat.domain.auth.LoginResult
import app.vaultchat.domain.auth.PasswordError
import app.vaultchat.domain.auth.UsernameError
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ServerStatus { CHECKING, REACHABLE, UNHEALTHY, UNREACHABLE }

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val usernameError: UsernameError? = null,
    val passwordError: PasswordError? = null,
    val submitting: Boolean = false,
    val failure: LoginFailure? = null,
    val server: ServerStatus = ServerStatus.CHECKING,
    /** Set once sign-in succeeds; the screen navigates and the ViewModel is discarded. */
    val outcome: LoginOutcome? = null,
)

enum class LoginOutcome { HOME, CHANGE_PASSWORD }

class LoginViewModel(
    private val authRepository: AuthRepository,
    private val apiClient: ApiClient,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private var serverCheck: Job? = null

    init {
        checkServer()
    }

    fun onUsernameChange(value: String) =
        _state.update { it.copy(username = value, usernameError = null, failure = null) }

    fun onPasswordChange(value: String) =
        _state.update { it.copy(password = value, passwordError = null, failure = null) }

    fun togglePasswordVisibility() = _state.update { it.copy(passwordVisible = !it.passwordVisible) }

    fun checkServer() {
        serverCheck?.cancel()
        _state.update { it.copy(server = ServerStatus.CHECKING) }
        serverCheck = viewModelScope.launch {
            val status = when (apiClient.checkHealth()) {
                ServerHealth.Reachable -> ServerStatus.REACHABLE
                is ServerHealth.Unhealthy -> ServerStatus.UNHEALTHY
                ServerHealth.Unreachable -> ServerStatus.UNREACHABLE
            }
            _state.update { it.copy(server = status) }
        }
    }

    fun submit() {
        val current = _state.value
        if (current.submitting) return
        val errors = LoginInput.validate(current.username, current.password)
        if (!errors.isValid) {
            _state.update { it.copy(usernameError = errors.username, passwordError = errors.password) }
            return
        }
        _state.update { it.copy(submitting = true, failure = null) }
        viewModelScope.launch {
            val result = authRepository.login(LoginInput.normalizeUsername(current.username), current.password)
            _state.update {
                when (result) {
                    // The password is dropped from memory as soon as it is no longer needed.
                    is LoginResult.Success -> it.copy(
                        submitting = false,
                        password = "",
                        outcome = if (result.mustChangePassword) LoginOutcome.CHANGE_PASSWORD else LoginOutcome.HOME,
                    )
                    is LoginResult.Failure -> it.copy(submitting = false, failure = result.reason)
                }
            }
        }
    }
}

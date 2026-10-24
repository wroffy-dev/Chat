package app.vaultchat.ui.password

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vaultchat.domain.auth.AuthRepository
import app.vaultchat.domain.auth.ChangePasswordFailure
import app.vaultchat.domain.auth.ChangePasswordResult
import app.vaultchat.domain.auth.NewPasswordError
import app.vaultchat.domain.auth.NewPasswordInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ChangePasswordOutcome { CHANGED, SESSION_ENDED, LOGGED_OUT }

data class ChangePasswordUiState(
    val current: String = "",
    val new: String = "",
    val confirm: String = "",
    val visible: Boolean = false,
    val inputError: NewPasswordError? = null,
    val failure: ChangePasswordFailure? = null,
    val submitting: Boolean = false,
    val outcome: ChangePasswordOutcome? = null,
)

class ChangePasswordViewModel(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(ChangePasswordUiState())
    val state: StateFlow<ChangePasswordUiState> = _state.asStateFlow()

    fun onCurrentChange(v: String) = _state.update { it.copy(current = v, inputError = null, failure = null) }
    fun onNewChange(v: String) = _state.update { it.copy(new = v, inputError = null, failure = null) }
    fun onConfirmChange(v: String) = _state.update { it.copy(confirm = v, inputError = null, failure = null) }
    fun toggleVisible() = _state.update { it.copy(visible = !it.visible) }

    fun submit() {
        val s = _state.value
        if (s.submitting) return
        NewPasswordInput.validate(s.current, s.new, s.confirm)?.let { error ->
            _state.update { it.copy(inputError = error) }
            return
        }
        _state.update { it.copy(submitting = true, failure = null) }
        viewModelScope.launch {
            val result = auth.changePassword(s.current, s.new)
            _state.update {
                when (result) {
                    ChangePasswordResult.Success ->
                        it.copy(submitting = false, current = "", new = "", confirm = "", outcome = ChangePasswordOutcome.CHANGED)
                    is ChangePasswordResult.Failure -> if (result.reason == ChangePasswordFailure.SESSION_ENDED) {
                        it.copy(submitting = false, outcome = ChangePasswordOutcome.SESSION_ENDED)
                    } else {
                        it.copy(submitting = false, failure = result.reason)
                    }
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            auth.logout()
            _state.update { it.copy(outcome = ChangePasswordOutcome.LOGGED_OUT) }
        }
    }
}

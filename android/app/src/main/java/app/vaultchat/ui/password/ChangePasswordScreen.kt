package app.vaultchat.ui.password

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vaultchat.R
import app.vaultchat.domain.auth.ChangePasswordFailure
import app.vaultchat.domain.auth.NewPasswordError
import app.vaultchat.ui.components.BannerKind
import app.vaultchat.ui.components.PasswordField
import app.vaultchat.ui.components.PrimaryButton
import app.vaultchat.ui.components.StatusBanner
import app.vaultchat.ui.components.TopBar

/**
 * Change password. When [forced] (after an admin reset or first login), the user cannot go back to
 * the app until it is done; they can only log out.
 */
@Composable
fun ChangePasswordScreen(
    viewModel: ChangePasswordViewModel,
    forced: Boolean,
    onDone: () -> Unit,
    onSessionEnded: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    LaunchedEffect(state.outcome) {
        when (state.outcome) {
            ChangePasswordOutcome.CHANGED -> onDone()
            ChangePasswordOutcome.SESSION_ENDED, ChangePasswordOutcome.LOGGED_OUT -> onSessionEnded()
            null -> Unit
        }
    }
    // A forced change cannot be skipped with the back button.
    BackHandler(enabled = forced) {}

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            TopBar(stringResource(R.string.password_title), onBack = if (forced) null else onBack)
            Box(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (forced) {
                        StatusBanner(BannerKind.INFO, stringResource(R.string.password_forced_explanation))
                    }

                    val inputError = state.inputError
                    PasswordField(
                        value = state.current,
                        onValueChange = viewModel::onCurrentChange,
                        label = stringResource(if (forced) R.string.password_current_temporary else R.string.password_current),
                        visible = state.visible,
                        onToggleVisibility = viewModel::toggleVisible,
                        error = if (inputError == NewPasswordError.CURRENT_REQUIRED) stringResource(R.string.password_error_current_required) else null,
                        enabled = !state.submitting,
                        imeAction = ImeAction.Next,
                        onImeAction = { focus.moveFocus(FocusDirection.Down) },
                    )
                    PasswordField(
                        value = state.new,
                        onValueChange = viewModel::onNewChange,
                        label = stringResource(R.string.password_new),
                        visible = state.visible,
                        onToggleVisibility = viewModel::toggleVisible,
                        error = when (inputError) {
                            NewPasswordError.TOO_SHORT -> stringResource(R.string.password_error_too_short)
                            NewPasswordError.TOO_LONG -> stringResource(R.string.password_error_too_long)
                            NewPasswordError.SAME_AS_CURRENT -> stringResource(R.string.password_error_same)
                            else -> null
                        },
                        enabled = !state.submitting,
                        imeAction = ImeAction.Next,
                        onImeAction = { focus.moveFocus(FocusDirection.Down) },
                    )
                    PasswordField(
                        value = state.confirm,
                        onValueChange = viewModel::onConfirmChange,
                        label = stringResource(R.string.password_confirm),
                        visible = state.visible,
                        onToggleVisibility = viewModel::toggleVisible,
                        error = if (inputError == NewPasswordError.MISMATCH) stringResource(R.string.password_error_mismatch) else null,
                        enabled = !state.submitting,
                        imeAction = ImeAction.Done,
                        onImeAction = {
                            focus.clearFocus()
                            viewModel.submit()
                        },
                    )
                    Text(
                        stringResource(R.string.password_rules),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    state.failure?.let { StatusBanner(BannerKind.ERROR, failureText(it)) }

                    PrimaryButton(
                        text = stringResource(R.string.password_submit),
                        onClick = {
                            focus.clearFocus()
                            viewModel.submit()
                        },
                        loading = state.submitting,
                    )
                    // A forced change has no Back arrow, so logging out is the only other way off this screen.
                    if (forced) {
                        TextButton(onClick = viewModel::logout, enabled = !state.submitting) {
                            Text(stringResource(R.string.action_log_out))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun failureText(failure: ChangePasswordFailure): String = stringResource(
    when (failure) {
        ChangePasswordFailure.WRONG_CURRENT_PASSWORD -> R.string.password_error_wrong_current
        ChangePasswordFailure.WEAK_PASSWORD -> R.string.password_error_weak
        ChangePasswordFailure.RATE_LIMITED -> R.string.login_error_rate_limited
        ChangePasswordFailure.SESSION_ENDED -> R.string.session_ended
        ChangePasswordFailure.NETWORK -> R.string.login_error_network
        ChangePasswordFailure.UNEXPECTED -> R.string.login_error_unexpected
    },
)

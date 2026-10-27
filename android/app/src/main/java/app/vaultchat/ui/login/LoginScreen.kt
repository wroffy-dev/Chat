package app.vaultchat.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vaultchat.R
import app.vaultchat.domain.auth.LoginFailure
import app.vaultchat.domain.auth.PasswordError
import app.vaultchat.domain.auth.UsernameError
import app.vaultchat.ui.components.BannerKind
import app.vaultchat.ui.components.PasswordField
import app.vaultchat.ui.components.PrimaryButton
import app.vaultchat.ui.components.StatusBanner

@Composable
fun LoginScreen(viewModel: LoginViewModel, onLoggedIn: (LoginOutcome) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.outcome) {
        state.outcome?.let(onLoggedIn)
    }

    LoginContent(
        state = state,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onTogglePassword = viewModel::togglePasswordVisibility,
        onSubmit = viewModel::submit,
        onRetryServer = viewModel::checkServer,
    )
}

@Composable
private fun LoginContent(
    state: LoginUiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePassword: () -> Unit,
    onSubmit: () -> Unit,
    onRetryServer: () -> Unit,
) {
    val focusManager = LocalFocusManager.current

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(R.string.login_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                ServerStatusBanner(state.server, onRetryServer)

                val usernameError = state.usernameError
                OutlinedTextField(
                    value = state.username,
                    onValueChange = onUsernameChange,
                    label = { Text(stringResource(R.string.login_username)) },
                    singleLine = true,
                    enabled = !state.submitting,
                    isError = usernameError != null,
                    supportingText = if (usernameError != null) { { Text(usernameErrorText(usernameError)) } } else null,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth(),
                )

                PasswordField(
                    value = state.password,
                    onValueChange = onPasswordChange,
                    label = stringResource(R.string.login_password),
                    visible = state.passwordVisible,
                    onToggleVisibility = onTogglePassword,
                    error = state.passwordError?.let { passwordErrorText(it) },
                    enabled = !state.submitting,
                    imeAction = ImeAction.Done,
                    onImeAction = {
                        focusManager.clearFocus()
                        onSubmit()
                    },
                )

                state.failure?.let { failure ->
                    StatusBanner(
                        kind = BannerKind.ERROR,
                        title = failureText(failure),
                    )
                }

                PrimaryButton(
                    text = stringResource(R.string.login_submit),
                    onClick = {
                        focusManager.clearFocus()
                        onSubmit()
                    },
                    loading = state.submitting,
                )

                Text(
                    stringResource(R.string.login_forgot_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ServerStatusBanner(status: ServerStatus, onRetry: () -> Unit) {
    when (status) {
        ServerStatus.REACHABLE -> Unit // Nothing to report; don't add noise to the happy path.
        ServerStatus.CHECKING -> StatusBanner(BannerKind.INFO, stringResource(R.string.server_checking))
        ServerStatus.UNHEALTHY, ServerStatus.UNREACHABLE -> StatusBanner(
            kind = BannerKind.WARNING,
            title = stringResource(R.string.server_unreachable),
            body = stringResource(R.string.server_unreachable_detail),
            actionLabel = stringResource(R.string.action_retry),
            onAction = onRetry,
        )
    }
}

@Composable
private fun usernameErrorText(error: UsernameError): String = stringResource(
    when (error) {
        UsernameError.REQUIRED -> R.string.login_error_username_required
        UsernameError.INVALID_FORMAT -> R.string.login_error_username_format
    },
)

@Composable
private fun passwordErrorText(error: PasswordError): String = stringResource(
    when (error) {
        PasswordError.REQUIRED -> R.string.login_error_password_required
        PasswordError.TOO_LONG -> R.string.login_error_password_too_long
    },
)

@Composable
private fun failureText(failure: LoginFailure): String = stringResource(
    when (failure) {
        LoginFailure.ACCOUNT_DISABLED -> R.string.login_error_disabled
        LoginFailure.INVALID_CREDENTIALS -> R.string.login_error_invalid_credentials
        LoginFailure.RATE_LIMITED -> R.string.login_error_rate_limited
        LoginFailure.NETWORK -> R.string.login_error_network
        LoginFailure.UNEXPECTED -> R.string.login_error_unexpected
    },
)

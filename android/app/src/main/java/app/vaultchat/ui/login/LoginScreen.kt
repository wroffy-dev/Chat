package app.vaultchat.ui.login

import app.vaultchat.ui.theme.glass
import app.vaultchat.ui.theme.glassBackdrop
import app.vaultchat.ui.theme.glossy
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import app.vaultchat.BuildConfig
import app.vaultchat.ui.components.AppIcons
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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

// The reference login layout (login.html, 2026-11-20) in the app's green theme (owner, 2026-11-20), so it
// follows light and dark mode like the rest of the app.
private val RefPrimary: Color @Composable get() = MaterialTheme.colorScheme.primary
private val RefOnPrimary: Color @Composable get() = MaterialTheme.colorScheme.onPrimary
private val RefPrimaryContainer: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
private val RefSurface: Color @Composable get() = MaterialTheme.colorScheme.background
private val RefOnSurface: Color @Composable get() = MaterialTheme.colorScheme.onBackground
private val RefOnSurfaceVariant: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val RefContainerLow: Color @Composable get() = MaterialTheme.colorScheme.surface
private val RefContainer: Color @Composable get() = MaterialTheme.colorScheme.surface
private val RefOutlineVariant: Color @Composable get() = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
private val RefTeal: Color @Composable get() = MaterialTheme.colorScheme.secondary
private val RefError: Color @Composable get() = MaterialTheme.colorScheme.error

/**
 * Sign-in in the owner's reference design: brand mark and "Private Access" at the top, the heading, two
 * labelled fields and a full-width pill button in the middle, and a short notice with the version at the
 * bottom. The reference's biometric button and its "device keys are verified locally" line are left out:
 * the app has neither feature.
 */
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
    fun submit() {
        focusManager.clearFocus()
        onSubmit()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = RefSurface, contentColor = RefOnSurface) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().glassBackdrop().safeDrawingPadding().imePadding()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 16.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Top: brand and privacy.
                Row(
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier.size(36.dp).background(RefPrimary, RoundedCornerShape(12.dp)).glossy(RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Lock, contentDescription = null, tint = RefOnPrimary, modifier = Modifier.size(20.dp)) }
                        Text(stringResource(R.string.login_brand), fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, color = RefOnSurface)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.glass(RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Icon(AppIcons.VerifiedUser, contentDescription = null, tint = RefTeal, modifier = Modifier.size(12.dp))
                        Text(stringResource(R.string.login_private_access), fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp, fontWeight = FontWeight.Medium, color = RefOnSurfaceVariant)
                    }
                }

                // Middle: heading and form.
                Column(
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.login_heading),
                            fontSize = 28.sp,
                            lineHeight = 34.sp,
                            letterSpacing = (-0.4).sp,
                            fontWeight = FontWeight.Bold,
                            color = RefOnSurface,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(stringResource(R.string.login_intro), fontSize = 14.sp, lineHeight = 20.sp, color = RefOnSurfaceVariant)
                    }

                    ServerStatusBanner(state.server, onRetryServer)

                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        RefField(
                            label = stringResource(R.string.login_username),
                            value = state.username,
                            onValueChange = onUsernameChange,
                            placeholder = stringResource(R.string.login_username),
                            hint = stringResource(R.string.login_username_hint),
                            error = state.usernameError?.let { usernameErrorText(it) },
                            enabled = !state.submitting,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Next,
                            ),
                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                        )
                        RefField(
                            label = stringResource(R.string.login_passphrase),
                            value = state.password,
                            onValueChange = onPasswordChange,
                            placeholder = stringResource(R.string.login_passphrase_placeholder),
                            hint = null,
                            error = state.passwordError?.let { passwordErrorText(it) },
                            enabled = !state.submitting,
                            visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onAny = { submit() }),
                            trailing = {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .clickable(enabled = !state.submitting, role = Role.Button, onClick = onTogglePassword),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        if (state.passwordVisible) AppIcons.VisibilityOff else AppIcons.Visibility,
                                        contentDescription = stringResource(if (state.passwordVisible) R.string.login_hide_passphrase else R.string.login_show_passphrase),
                                        tint = RefOnSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            },
                        )

                        state.failure?.let { failure ->
                            Text(
                                failureText(failure),
                                color = RefError,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }

                        Box(modifier = Modifier.padding(top = 8.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(if (state.submitting) RefPrimary.copy(alpha = 0.8f) else RefPrimary)
                                    .glossy(RoundedCornerShape(50))
                                    .clickable(enabled = !state.submitting, role = Role.Button, onClick = { submit() })
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (state.submitting) {
                                    val description = stringResource(R.string.loading)
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp).semantics { contentDescription = description },
                                        strokeWidth = 2.dp,
                                        color = RefOnPrimary,
                                    )
                                } else {
                                    Text(stringResource(R.string.login_sign_in), color = RefOnPrimary, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                // Bottom: notice and version.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    Text(
                        stringResource(R.string.login_no_registration) + " " + stringResource(R.string.login_forgot_hint),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        letterSpacing = 0.1.sp,
                        color = RefOnSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(R.string.login_version, BuildConfig.VERSION_NAME),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        letterSpacing = 0.3.sp,
                        fontWeight = FontWeight.Medium,
                        color = RefOnSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A labelled field as in the reference: label above, a 48 dp box with a thin border, a hint or error below. */
@Composable
private fun RefField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    hint: String?,
    error: String?,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        error != null -> RefError
        focused -> RefPrimary
        else -> RefOutlineVariant
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp, fontWeight = FontWeight.SemiBold, color = RefOnSurface)
        // The focus ring of the reference: the primary-container glow around the focused field.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(if (focused) 2.dp else 0.dp, if (focused) RefPrimaryContainer else Color.Transparent, RoundedCornerShape(14.dp))
                .padding(if (focused) 2.dp else 0.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = RefOnSurface),
                cursorBrush = SolidColor(RefPrimary),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                visualTransformation = visualTransformation,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = label },
                decorationBox = { inner ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .glass(RoundedCornerShape(16.dp))
                            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                            .padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (value.isEmpty()) Text(placeholder, fontSize = 14.sp, lineHeight = 20.sp, color = RefOnSurfaceVariant)
                            inner()
                        }
                        trailing?.invoke()
                    }
                },
            )
        }
        when {
            error != null -> Text(error, fontSize = 12.sp, lineHeight = 16.sp, color = RefError)
            hint != null -> Text(hint, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp, color = RefOnSurfaceVariant)
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

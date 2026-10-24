package app.vaultchat.ui.connect

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.vaultchat.R
import app.vaultchat.domain.connection.ConnectionFailure

@Composable
fun connectionFailureText(failure: ConnectionFailure): String = stringResource(
    when (failure) {
        ConnectionFailure.ALREADY_CONNECTED -> R.string.connect_error_already_connected
        ConnectionFailure.QR_INVALID -> R.string.connect_error_qr_invalid
        ConnectionFailure.ALREADY_PENDING -> R.string.connect_error_already_pending
        ConnectionFailure.NOT_PENDING -> R.string.connect_error_not_pending
        ConnectionFailure.NOT_FOUND -> R.string.connect_error_not_pending
        ConnectionFailure.RATE_LIMITED -> R.string.login_error_rate_limited
        ConnectionFailure.SESSION_ENDED -> R.string.session_ended
        ConnectionFailure.NETWORK -> R.string.login_error_network
        ConnectionFailure.UNEXPECTED -> R.string.login_error_unexpected
    },
)

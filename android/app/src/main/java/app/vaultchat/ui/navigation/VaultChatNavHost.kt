package app.vaultchat.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.vaultchat.AppContainer
import app.vaultchat.ui.home.HomeScreen
import app.vaultchat.ui.home.HomeViewModel
import app.vaultchat.ui.login.LoginOutcome
import app.vaultchat.ui.login.LoginScreen
import app.vaultchat.ui.login.LoginViewModel
import app.vaultchat.ui.password.ChangePasswordScreen
import app.vaultchat.ui.password.ChangePasswordViewModel
import kotlinx.serialization.Serializable

@Serializable
data object LoginRoute

@Serializable
data object HomeRoute

@Serializable
data class ChangePasswordRoute(val forced: Boolean)

/** Clears the whole back stack, so Back never returns to a screen from the previous state. */
private fun NavHostController.replaceAll(route: Any) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

@Composable
fun VaultChatNavHost(container: AppContainer) {
    val navController = rememberNavController()
    // Decided once from the stored session; the Home screen then confirms it with the server.
    val start: Any = remember {
        val user = container.authRepository.currentUser()
        when {
            user == null -> LoginRoute
            user.mustChangePassword -> ChangePasswordRoute(forced = true)
            else -> HomeRoute
        }
    }

    NavHost(navController = navController, startDestination = start) {
        composable<LoginRoute> {
            val viewModel: LoginViewModel = viewModel(
                factory = viewModelFactory { initializer { LoginViewModel(container.authRepository, container.apiClient) } },
            )
            LoginScreen(
                viewModel = viewModel,
                onLoggedIn = { outcome ->
                    navController.replaceAll(
                        if (outcome == LoginOutcome.CHANGE_PASSWORD) ChangePasswordRoute(forced = true) else HomeRoute,
                    )
                },
            )
        }
        composable<ChangePasswordRoute> { entry ->
            val route = entry.toRoute<ChangePasswordRoute>()
            val viewModel: ChangePasswordViewModel = viewModel(
                factory = viewModelFactory { initializer { ChangePasswordViewModel(container.authRepository) } },
            )
            ChangePasswordScreen(
                viewModel = viewModel,
                forced = route.forced,
                onDone = { navController.replaceAll(HomeRoute) },
                onSessionEnded = { navController.replaceAll(LoginRoute) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<HomeRoute> {
            val viewModel: HomeViewModel = viewModel(
                factory = viewModelFactory { initializer { HomeViewModel(container.authRepository) } },
            )
            HomeScreen(
                viewModel = viewModel,
                onChangePassword = { navController.navigate(ChangePasswordRoute(forced = false)) },
                onSignedOut = { navController.replaceAll(LoginRoute) },
                onPasswordChangeRequired = { navController.replaceAll(ChangePasswordRoute(forced = true)) },
            )
        }
    }
}

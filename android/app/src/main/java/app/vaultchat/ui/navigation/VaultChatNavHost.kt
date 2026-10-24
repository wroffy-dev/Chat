package app.vaultchat.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.vaultchat.AppContainer
import app.vaultchat.ui.home.HomeScreen
import app.vaultchat.ui.login.LoginScreen
import app.vaultchat.ui.login.LoginViewModel
import kotlinx.serialization.Serializable

@Serializable
data object LoginRoute

@Serializable
data object HomeRoute

@Composable
fun VaultChatNavHost(container: AppContainer) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = LoginRoute) {
        composable<LoginRoute> {
            val viewModel: LoginViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { LoginViewModel(container.authRepository, container.apiClient) }
                },
            )
            LoginScreen(
                viewModel = viewModel,
                onLoggedIn = {
                    navController.navigate(HomeRoute) {
                        // Back from Home must not return to the login form.
                        popUpTo<LoginRoute> { inclusive = true }
                    }
                },
            )
        }
        composable<HomeRoute> {
            HomeScreen()
        }
    }
}

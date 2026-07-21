package dev.jtiisto.pulsebridge.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.jtiisto.pulsebridge.feature.capture.ui.CaptureScreen
import dev.jtiisto.pulsebridge.settings.SettingsScreen

@Composable
fun PulseBridgeNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = "capture",
        modifier = modifier,
    ) {
        composable("capture") {
            CaptureScreen(
                onNavigateToSettings = { navController.navigate("settings") },
            )
        }
        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}

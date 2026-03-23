package dev.jtiisto.wellnesssync.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.jtiisto.wellnesssync.feature.capture.ui.CaptureScreen
import dev.jtiisto.wellnesssync.settings.SettingsScreen

@Composable
fun WellnessSyncNavHost(
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

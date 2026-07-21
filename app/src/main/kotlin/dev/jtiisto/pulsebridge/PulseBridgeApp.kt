package dev.jtiisto.pulsebridge

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import dev.jtiisto.pulsebridge.core.ui.theme.PulseBridgeTheme
import dev.jtiisto.pulsebridge.navigation.PulseBridgeNavHost
import org.koin.compose.KoinContext

@Composable
fun PulseBridgeApp() {
    KoinContext {
        PulseBridgeTheme {
            val navController = rememberNavController()
            Scaffold { innerPadding ->
                PulseBridgeNavHost(
                    navController = navController,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

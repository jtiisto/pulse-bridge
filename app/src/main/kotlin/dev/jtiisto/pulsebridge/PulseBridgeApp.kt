package dev.jtiisto.pulsebridge

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import dev.jtiisto.pulsebridge.core.ble.di.bleCaptureStateQualifier
import dev.jtiisto.pulsebridge.core.ble.service.BleCaptureServiceState
import dev.jtiisto.pulsebridge.core.ui.theme.PulseBridgeTheme
import dev.jtiisto.pulsebridge.navigation.PulseBridgeNavHost
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.compose.KoinContext
import org.koin.compose.koinInject

@Composable
fun PulseBridgeApp() {
    KoinContext {
        PulseBridgeTheme {
            KeepScreenOnWhileCapturing()
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

// Mirrors the capture service's isRunning onto FLAG_KEEP_SCREEN_ON so the
// live tachogram stays visible through a workout; normal dimming resumes
// when capture stops. Spec: specs/keep_screen_on.md
@Composable
private fun KeepScreenOnWhileCapturing() {
    val captureState =
        koinInject<MutableStateFlow<BleCaptureServiceState>>(bleCaptureStateQualifier)
    val state by captureState.collectAsStateWithLifecycle()
    val view = LocalView.current
    DisposableEffect(view, state.isRunning) {
        view.keepScreenOn = state.isRunning
        onDispose { view.keepScreenOn = false }
    }
}

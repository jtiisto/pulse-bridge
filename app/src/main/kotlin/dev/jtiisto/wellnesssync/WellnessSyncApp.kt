package dev.jtiisto.wellnesssync

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import dev.jtiisto.wellnesssync.core.ui.theme.WellnessSyncTheme
import dev.jtiisto.wellnesssync.navigation.WellnessSyncNavHost
import org.koin.compose.KoinContext

@Composable
fun WellnessSyncApp() {
    KoinContext {
        WellnessSyncTheme {
            val navController = rememberNavController()
            Scaffold { innerPadding ->
                WellnessSyncNavHost(
                    navController = navController,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

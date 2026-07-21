package dev.jtiisto.pulsebridge.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.pulsebridge.core.common.SyncEnvironment
import dev.jtiisto.pulsebridge.core.ui.theme.CardBackground
import dev.jtiisto.pulsebridge.core.ui.theme.Warning
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.diagnosticUploadResult) {
        state.diagnosticUploadResult?.let { result ->
            snackbarHostState.showSnackbar(result)
            viewModel.onEvent(SettingsEvent.DismissDiagnosticResult)
        }
    }

    LaunchedEffect(state.clearDataSuccess) {
        if (state.clearDataSuccess) {
            snackbarHostState.showSnackbar("Local data cleared")
            viewModel.onEvent(SettingsEvent.DismissClearDataSuccess)
        }
    }

    SettingsScreenContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onEvent = viewModel::onEvent,
        onNavigateBack = onNavigateBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreenContent(
    state: SettingsState,
    snackbarHostState: SnackbarHostState,
    onEvent: (SettingsEvent) -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            EnvironmentCard(
                currentEnvironment = state.environment,
                onSelect = { onEvent(SettingsEvent.SetEnvironment(it)) },
            )

            ClearDataCard(
                onClear = { onEvent(SettingsEvent.RequestClearData) },
            )

            DiagnosticsCard(
                entryCount = state.diagnosticCount,
                uploadInProgress = state.diagnosticUploadInProgress,
                onUpload = { onEvent(SettingsEvent.UploadDiagnostics) },
            )
        }
    }

    if (state.showClearDataDialog) {
        ClearDataConfirmDialog(
            onConfirm = { onEvent(SettingsEvent.ConfirmClearData) },
            onDismiss = { onEvent(SettingsEvent.DismissClearDataDialog) },
        )
    }
}

@Composable
private fun EnvironmentCard(
    currentEnvironment: SyncEnvironment,
    onSelect: (SyncEnvironment) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Sync Environment",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Controls which server database receives synced data",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EnvironmentOption(
                    label = "Production",
                    isSelected = currentEnvironment == SyncEnvironment.PRODUCTION,
                    onClick = { onSelect(SyncEnvironment.PRODUCTION) },
                    modifier = Modifier.weight(1f),
                )
                EnvironmentOption(
                    label = "Test",
                    isSelected = currentEnvironment == SyncEnvironment.TEST,
                    onClick = { onSelect(SyncEnvironment.TEST) },
                    modifier = Modifier.weight(1f),
                    accentColor = Warning,
                )
            }
        }
    }
}

@Composable
private fun EnvironmentOption(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: androidx.compose.ui.graphics.Color? = null,
) {
    val borderColor = if (isSelected) {
        accentColor ?: MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    val backgroundColor = if (isSelected) {
        (accentColor ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    }
    val textColor = if (isSelected) {
        accentColor ?: MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
        )
    }
}

@Composable
private fun ClearDataCard(onClear: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Local Data",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Delete locally stored data that has already been synced to the server. Unsynced data is kept.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onClear,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteForever,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text("Clear Synced Data")
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(
    entryCount: Int,
    uploadInProgress: Boolean,
    onUpload: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Diagnostics",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$entryCount buffered log entries (BLE, capture, sync). Upload to the server for remote debugging.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onUpload,
                enabled = !uploadInProgress,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(if (uploadInProgress) "Uploading…" else "Upload Diagnostics Log")
            }
        }
    }
}

@Composable
private fun ClearDataConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear synced data?") },
        text = {
            Text("This will delete locally stored intervals and accelerometer summaries that were already synced to the server. Unsynced data is kept. This cannot be undone.")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Clear", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

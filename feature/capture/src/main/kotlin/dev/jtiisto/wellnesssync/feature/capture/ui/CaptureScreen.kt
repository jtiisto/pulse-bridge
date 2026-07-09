package dev.jtiisto.wellnesssync.feature.capture.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellnesssync.core.ble.device.KnownDevice
import dev.jtiisto.wellnesssync.core.ble.model.ConnectionState
import dev.jtiisto.wellnesssync.core.ble.polar.PolarDevice
import dev.jtiisto.wellnesssync.core.ble.polar.PolarSyncServiceState
import dev.jtiisto.wellnesssync.core.ble.scanner.DiscoveredDevice
import dev.jtiisto.wellnesssync.core.network.ServerStatus
import dev.jtiisto.wellnesssync.core.ui.theme.CardBackground
import dev.jtiisto.wellnesssync.core.ui.theme.Success
import dev.jtiisto.wellnesssync.core.ui.theme.Warning
import dev.jtiisto.wellnesssync.feature.capture.domain.model.CaptureEffect
import dev.jtiisto.wellnesssync.feature.capture.domain.model.CaptureEvent
import dev.jtiisto.wellnesssync.feature.capture.domain.model.CaptureState
import org.koin.androidx.compose.koinViewModel

@Composable
fun CaptureScreen(
    onNavigateToSettings: () -> Unit = {},
    viewModel: CaptureViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CaptureEffect.ShowError -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            viewModel.onEvent(CaptureEvent.PermissionsGranted)
        }
    }

    LaunchedEffect(Unit) {
        val permissions = buildList {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    CaptureScreenContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onEvent = viewModel::onEvent,
        onNavigateToSettings = onNavigateToSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureScreenContent(
    state: CaptureState,
    snackbarHostState: SnackbarHostState,
    onEvent: (CaptureEvent) -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wellness Sync") },
                actions = {
                    ServerStatusDot(state.serverStatus)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = state.serverStatus.label(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurface,
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            // Status card — shown when capturing
            if (state.isCapturing) {
                item {
                    StatusCard(state)
                }
                item {
                    TachogramChart(points = state.chartPoints)
                }
            }

            // Device section — shown when not capturing
            if (!state.isCapturing) {
                item {
                    DeviceSection(
                        knownDevices = state.knownDevices,
                        discoveredDevices = state.discoveredDevices,
                        isScanning = state.isScanning,
                        onStartScan = { onEvent(CaptureEvent.StartScan) },
                        onStopScan = { onEvent(CaptureEvent.StopScan) },
                        onSelectDevice = { address, name ->
                            onEvent(CaptureEvent.StartCapture(address, name))
                        },
                        onRemoveKnown = { onEvent(CaptureEvent.RemoveKnownDevice(it)) },
                    )
                }
            }

            // Polar devices section
            item {
                PolarDevicesSection(
                    polarDevices = state.polarDevices,
                    discoveredPolarDevices = state.discoveredPolarDevices,
                    isPolarScanning = state.isPolarScanning,
                    polarSyncState = state.polarSyncState,
                    onStartScan = { onEvent(CaptureEvent.StartPolarScan) },
                    onStopScan = { onEvent(CaptureEvent.StopPolarScan) },
                    onAddDevice = { address, name ->
                        onEvent(CaptureEvent.AddPolarDevice(address, name))
                    },
                    onRemoveDevice = { onEvent(CaptureEvent.RemovePolarDevice(it)) },
                    onSyncNow = { onEvent(CaptureEvent.SyncPolarNow(it)) },
                )
            }

            // Sync status card
            item {
                SyncStatusCard(
                    unsyncedCount = state.unsyncedCount,
                    lastSyncTime = state.lastSyncTime,
                    onSyncNow = { onEvent(CaptureEvent.SyncNow) },
                )
            }

            // Data summary card — shown when capturing
            if (state.isCapturing) {
                item {
                    DataSummaryCard(intervalCount = state.intervalCount)
                }
            }

            // Start/Stop button
            item {
                if (state.isCapturing) {
                    Button(
                        onClick = { onEvent(CaptureEvent.StopCapture) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Stop Capture", modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun StatusCard(state: CaptureState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ),
                    shape = RoundedCornerShape(12.dp),
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConnectionDot(state.connectionState)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = state.deviceName ?: "Unknown Device",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = state.connectionState.label(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Live HR display
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Icon(
                        imageVector = Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = state.currentHr?.toString() ?: "--",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontSize = 48.sp,
                        ),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "bpm",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceSection(
    knownDevices: List<KnownDevice>,
    discoveredDevices: List<DiscoveredDevice>,
    isScanning: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onSelectDevice: (address: String, name: String?) -> Unit,
    onRemoveKnown: (address: String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Devices",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(Modifier.height(12.dp))

            // Known devices
            if (knownDevices.isNotEmpty()) {
                Text(
                    text = "Previously Connected",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                knownDevices.forEach { device ->
                    KnownDeviceRow(
                        device = device,
                        onConnect = { onSelectDevice(device.address, device.name) },
                        onRemove = { onRemoveKnown(device.address) },
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(12.dp))
            }

            // Discovered devices
            if (discoveredDevices.isNotEmpty()) {
                Text(
                    text = "Nearby Devices",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                discoveredDevices.forEach { device ->
                    DiscoveredDeviceRow(
                        device = device,
                        onConnect = { onSelectDevice(device.address, device.name) },
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(12.dp))
            }

            // Scan button
            OutlinedButton(
                onClick = { if (isScanning) onStopScan() else onStartScan() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isScanning) "Stop Scan" else "Scan for Devices")
            }
        }
    }
}

@Composable
private fun KnownDeviceRow(
    device: KnownDevice,
    onConnect: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = onConnect,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text("Connect")
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove device",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun DiscoveredDeviceRow(
    device: DiscoveredDevice,
    onConnect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name ?: "Unknown",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = onConnect,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text("Connect")
        }
    }
}

@Composable
private fun SyncStatusCard(
    unsyncedCount: Int,
    lastSyncTime: Long?,
    onSyncNow: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Sync Status",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (unsyncedCount == 0) Success else Warning,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (unsyncedCount == 0) "All synced" else "$unsyncedCount intervals pending",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (unsyncedCount == 0) Success else Warning,
                )
            }

            if (lastSyncTime != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Last sync: ${formatTimestamp(lastSyncTime)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onSyncNow,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Sync Now")
            }
        }
    }
}

@Composable
private fun DataSummaryCard(intervalCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Data Summary",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "$intervalCount intervals captured",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun PolarDevicesSection(
    polarDevices: List<PolarDevice>,
    discoveredPolarDevices: List<DiscoveredDevice>,
    isPolarScanning: Boolean,
    polarSyncState: PolarSyncServiceState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onAddDevice: (deviceId: String, name: String) -> Unit,
    onRemoveDevice: (deviceId: String) -> Unit,
    onSyncNow: (deviceId: String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Polar Devices",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(Modifier.height(12.dp))

            // Known Polar devices
            if (polarDevices.isNotEmpty()) {
                polarDevices.forEach { device ->
                    PolarDeviceRow(
                        device = device,
                        isSyncing = polarSyncState.isRunning && polarSyncState.deviceId == device.deviceId,
                        onSyncNow = { onSyncNow(device.deviceId) },
                        onRemove = { onRemoveDevice(device.deviceId) },
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(12.dp))
            }

            // Sync status when running
            if (polarSyncState.isRunning) {
                Text(
                    text = when (polarSyncState.status) {
                        PolarSyncServiceState.Status.CONNECTING -> "Connecting..."
                        PolarSyncServiceState.Status.FETCHING ->
                            "Fetching ${polarSyncState.recordingsProcessed}/${polarSyncState.recordingsFound} recordings"
                        PolarSyncServiceState.Status.COMPLETE -> "Sync complete"
                        else -> "Syncing..."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
            }

            // Discovered Polar devices
            if (discoveredPolarDevices.isNotEmpty()) {
                Text(
                    text = "Nearby Polar Devices",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                discoveredPolarDevices.forEach { device ->
                    DiscoveredDeviceRow(
                        device = device,
                        onConnect = { onAddDevice(device.address, device.name ?: "Polar Device") },
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(12.dp))
            }

            // Scan button
            OutlinedButton(
                onClick = { if (isPolarScanning) onStopScan() else onStartScan() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isPolarScanning) "Stop Scan" else "Add Polar Device")
            }
        }
    }
}

@Composable
private fun PolarDeviceRow(
    device: PolarDevice,
    isSyncing: Boolean,
    onSyncNow: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            val lastSync = device.lastSyncTime
            Text(
                text = if (lastSync != null) {
                    "Last sync: ${formatTimestamp(lastSync)}"
                } else {
                    "Never synced"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = onSyncNow,
            enabled = !isSyncing,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(if (isSyncing) "Syncing..." else "Sync Now")
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove device",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ConnectionDot(connectionState: ConnectionState) {
    val color = when (connectionState) {
        ConnectionState.CONNECTED -> Success
        ConnectionState.CONNECTING, ConnectionState.SCANNING -> Warning
        ConnectionState.RECONNECTING -> Warning
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

private fun ConnectionState.label(): String = when (this) {
    ConnectionState.CONNECTED -> "Connected"
    ConnectionState.CONNECTING -> "Connecting"
    ConnectionState.SCANNING -> "Scanning"
    ConnectionState.RECONNECTING -> "Reconnecting"
    ConnectionState.DISCONNECTED -> "Disconnected"
}

@Composable
private fun ServerStatusDot(serverStatus: ServerStatus) {
    val color = when (serverStatus) {
        ServerStatus.CONNECTED -> Success
        ServerStatus.CHECKING -> Warning
        ServerStatus.UNREACHABLE -> MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

private fun ServerStatus.label(): String = when (this) {
    ServerStatus.CONNECTED -> "Server Connected"
    ServerStatus.CHECKING -> "Checking..."
    ServerStatus.UNREACHABLE -> "Server Unreachable"
}

private fun formatTimestamp(epochMs: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(epochMs))
}

package dev.jtiisto.wellnesssync.core.ble.polar

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.jtiisto.wellnesssync.core.ble.di.polarSyncStateQualifier
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class PolarScanReceiver : BroadcastReceiver(), KoinComponent {

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "polar_device_address"
        private const val TAG = "PolarScanReceiver"
    }

    private val syncServiceState: MutableStateFlow<PolarSyncServiceState> by inject(polarSyncStateQualifier)

    override fun onReceive(context: Context, intent: Intent) {
        // Check if PolarSyncService is already running — debounce
        if (syncServiceState.value.isRunning) {
            Log.d(TAG, "PolarSyncService already running, ignoring scan result")
            return
        }

        // Extract scan results from the PendingIntent callback
        val results = intent.getParcelableArrayListExtra<ScanResult>(
            BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT
        )

        if (results.isNullOrEmpty()) {
            Log.d(TAG, "No scan results in PendingIntent")
            return
        }

        // Use the device address from the scan result
        val deviceAddress = results.first().device.address
        Log.i(TAG, "Polar device detected: $deviceAddress")

        // The Polar SDK uses device IDs, not MAC addresses
        // For PendingIntent scan, we pass the address and let the service resolve the device ID
        val serviceIntent = PolarSyncService.startIntent(context, deviceAddress)
        context.startForegroundService(serviceIntent)
    }
}

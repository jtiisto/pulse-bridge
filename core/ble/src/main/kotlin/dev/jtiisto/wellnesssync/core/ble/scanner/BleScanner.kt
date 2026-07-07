package dev.jtiisto.wellnesssync.core.ble.scanner

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
)

class BleScanner(private val context: Context) {

    companion object {
        val HRM_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HRM_NAME_PREFIXES = listOf("HRM", "Garmin", "Polar")
    }

    @SuppressLint("MissingPermission")
    fun scan(): Flow<DiscoveredDevice> = callbackFlow {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = bluetoothManager.adapter?.bluetoothLeScanner

        if (scanner == null) {
            close(IllegalStateException("Bluetooth LE scanner not available"))
            return@callbackFlow
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName
                val hasHrmService = result.scanRecord?.serviceUuids
                    ?.any { it.uuid == HRM_SERVICE_UUID } == true
                val nameMatches = name != null &&
                    HRM_NAME_PREFIXES.any { name.startsWith(it, ignoreCase = true) }
                if (!nameMatches && !hasHrmService) return
                trySend(
                    DiscoveredDevice(
                        address = result.device.address,
                        name = name,
                        rssi = result.rssi,
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed with error code: $errorCode"))
            }
        }

        scanner.startScan(null, settings, callback)

        awaitClose {
            scanner.stopScan(callback)
        }
    }
}

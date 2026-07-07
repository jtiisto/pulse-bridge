package dev.jtiisto.wellnesssync.core.ble.polar

import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.util.Log

class PolarDeviceDetector(private val context: Context) {

    companion object {
        private const val TAG = "PolarDeviceDetector"
        private const val REQUEST_CODE_BASE = 2000
    }

    fun registerScanFilter(deviceAddress: String) {
        val scanner = getScanner() ?: return

        val filter = ScanFilter.Builder()
            .setDeviceAddress(deviceAddress)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setReportDelay(0)
            .build()

        val pendingIntent = createPendingIntent(deviceAddress)

        try {
            // A scan with this PendingIntent may already be registered from a
            // previous process — clear it first so re-registration is idempotent
            scanner.stopScan(pendingIntent)
            val result = scanner.startScan(listOf(filter), settings, pendingIntent)
            if (result == 0) {
                Log.i(TAG, "Registered PendingIntent scan for $deviceAddress")
            } else {
                Log.e(TAG, "startScan failed with code $result for $deviceAddress")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLUETOOTH_SCAN permission: ${e.message}")
        }
    }

    fun unregisterScanFilter(deviceAddress: String) {
        val scanner = getScanner() ?: return
        val pendingIntent = createPendingIntent(deviceAddress)

        try {
            scanner.stopScan(pendingIntent)
            Log.i(TAG, "Unregistered PendingIntent scan for $deviceAddress")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLUETOOTH_SCAN permission: ${e.message}")
        }
    }

    fun registerAllKnownDevices(deviceAddresses: List<String>) {
        for (address in deviceAddresses) {
            registerScanFilter(address)
        }
    }

    private fun getScanner() = try {
        val btManager = context.getSystemService(BluetoothManager::class.java)
        btManager.adapter?.bluetoothLeScanner
    } catch (e: SecurityException) {
        Log.e(TAG, "Missing Bluetooth permission: ${e.message}")
        null
    }

    private fun createPendingIntent(deviceAddress: String): PendingIntent {
        val intent = Intent(context, PolarScanReceiver::class.java).apply {
            putExtra(PolarScanReceiver.EXTRA_DEVICE_ADDRESS, deviceAddress)
        }
        val requestCode = REQUEST_CODE_BASE + deviceAddress.hashCode()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}

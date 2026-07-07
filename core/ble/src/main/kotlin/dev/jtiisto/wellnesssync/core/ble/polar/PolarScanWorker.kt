package dev.jtiisto.wellnesssync.core.ble.polar

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.jtiisto.wellnesssync.core.ble.device.KnownDeviceStore
import dev.jtiisto.wellnesssync.core.ble.di.polarSyncStateQualifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

class PolarScanWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val knownDeviceStore: KnownDeviceStore by inject()
    private val syncServiceState: MutableStateFlow<PolarSyncServiceState> by inject(polarSyncStateQualifier)

    override suspend fun doWork(): Result {
        // Skip if sync is already running
        if (syncServiceState.value.isRunning) {
            Log.d(TAG, "PolarSyncService already running, skipping scan")
            return Result.success()
        }

        // Get known Polar device addresses
        val polarDevices = knownDeviceStore.getAll()
            .filter { it.name.contains("Polar", ignoreCase = true) }

        if (polarDevices.isEmpty()) {
            Log.d(TAG, "No known Polar devices, skipping scan")
            return Result.success()
        }

        val btManager = applicationContext.getSystemService(BluetoothManager::class.java)
        val scanner = btManager.adapter?.bluetoothLeScanner ?: return Result.success()

        val filters = polarDevices.map { device ->
            ScanFilter.Builder()
                .setDeviceAddress(device.address)
                .build()
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val foundDevice = CompletableDeferred<ScanResult>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                foundDevice.complete(result)
            }
        }

        return try {
            scanner.startScan(filters, settings, callback)

            val result = withTimeoutOrNull(SCAN_DURATION_MS) {
                foundDevice.await()
            }

            scanner.stopScan(callback)

            if (result != null) {
                Log.i(TAG, "Polar device found: ${result.device.address}")
                val serviceIntent = PolarSyncService.startIntent(
                    applicationContext,
                    result.device.address,
                )
                applicationContext.startForegroundService(serviceIntent)
            }

            Result.success()
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLE permission: ${e.message}")
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "PolarScanWorker"
        const val WORK_NAME = "polar_scan_periodic"
        private const val SCAN_DURATION_MS = 10_000L

        fun enqueuePeriodicScan(context: Context) {
            val request = PeriodicWorkRequestBuilder<PolarScanWorker>(
                15, TimeUnit.MINUTES,
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}

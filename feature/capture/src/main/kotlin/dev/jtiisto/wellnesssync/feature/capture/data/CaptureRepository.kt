package dev.jtiisto.wellnesssync.feature.capture.data

import android.content.Context
import dev.jtiisto.wellnesssync.core.ble.connection.PriorityMultiplexer
import dev.jtiisto.wellnesssync.core.ble.device.KnownDevice
import dev.jtiisto.wellnesssync.core.ble.device.KnownDeviceStore
import dev.jtiisto.wellnesssync.core.ble.model.HeartRateSample
import dev.jtiisto.wellnesssync.core.ble.polar.PolarDevice
import dev.jtiisto.wellnesssync.core.ble.polar.PolarDeviceDetector
import dev.jtiisto.wellnesssync.core.ble.polar.PolarDeviceStore
import dev.jtiisto.wellnesssync.core.ble.polar.PolarSyncService
import dev.jtiisto.wellnesssync.core.ble.polar.PolarSyncServiceState
import dev.jtiisto.wellnesssync.core.ble.scanner.BleScanner
import dev.jtiisto.wellnesssync.core.ble.scanner.DiscoveredDevice
import dev.jtiisto.wellnesssync.core.ble.service.BleCaptureService
import dev.jtiisto.wellnesssync.core.ble.service.BleCaptureServiceState
import dev.jtiisto.wellnesssync.core.database.dao.IntervalDao
import dev.jtiisto.wellnesssync.core.database.dao.SyncStatusDao
import dev.jtiisto.wellnesssync.core.database.entity.SyncStatusEntity
import dev.jtiisto.wellnesssync.core.sync.SyncWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class CaptureRepository(
    private val serviceState: MutableStateFlow<BleCaptureServiceState>,
    private val polarSyncState: MutableStateFlow<PolarSyncServiceState>,
    private val intervalDao: IntervalDao,
    private val syncStatusDao: SyncStatusDao,
    private val bleScanner: BleScanner,
    private val knownDeviceStore: KnownDeviceStore,
    private val polarDeviceStore: PolarDeviceStore,
    private val polarDeviceDetector: PolarDeviceDetector,
    private val multiplexer: PriorityMultiplexer,
) {
    val serviceStateFlow: Flow<BleCaptureServiceState> = serviceState

    val polarSyncStateFlow: Flow<PolarSyncServiceState> = polarSyncState

    // Hot stream (SharedFlow-backed) — safe to collect alongside the capture
    // service; the UI sees exactly the beats being recorded
    val beatStream: Flow<HeartRateSample> = multiplexer.authoritativeStream

    val unsyncedCount: Flow<Int> = intervalDao.getUnsyncedCount()

    val syncStatus: Flow<SyncStatusEntity?> = syncStatusDao.observe()

    fun scanForDevices(): Flow<DiscoveredDevice> = bleScanner.scan()

    fun getKnownDevices(): List<KnownDevice> = knownDeviceStore.getAll()

    fun isKnownDevice(address: String): Boolean = knownDeviceStore.isKnown(address)

    fun removeKnownDevice(address: String) = knownDeviceStore.remove(address)

    fun startCapture(context: Context, deviceAddress: String, deviceName: String?) {
        val intent = BleCaptureService.startIntent(context, deviceAddress, deviceName)
        context.startForegroundService(intent)
    }

    fun stopCapture(context: Context) {
        val intent = BleCaptureService.stopIntent(context)
        context.startService(intent)
    }

    fun syncNow(context: Context) {
        SyncWorker.enqueueSyncNow(context)
    }

    fun startPeriodicSync(context: Context) {
        SyncWorker.enqueuePeriodicSync(context)
    }

    // Polar device management
    fun getPolarDevices(): List<PolarDevice> = polarDeviceStore.getAll()

    fun addPolarDevice(deviceId: String, name: String) {
        polarDeviceStore.save(deviceId, name)
        polarDeviceDetector.registerScanFilter(deviceId)
    }

    fun removePolarDevice(deviceId: String) {
        polarDeviceDetector.unregisterScanFilter(deviceId)
        polarDeviceStore.remove(deviceId)
    }

    fun syncPolarNow(context: Context, deviceId: String) {
        val intent = PolarSyncService.startIntent(context, deviceId)
        context.startForegroundService(intent)
    }
}

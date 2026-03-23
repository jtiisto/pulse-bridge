package dev.jtiisto.wellnesssync.core.ble.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import dev.jtiisto.wellnesssync.core.ble.buffer.IntervalBuffer
import dev.jtiisto.wellnesssync.core.ble.connection.GarminHrmConnection
import dev.jtiisto.wellnesssync.core.ble.connection.PriorityMultiplexer
import dev.jtiisto.wellnesssync.core.ble.device.KnownDeviceStore
import dev.jtiisto.wellnesssync.core.ble.model.ConnectionState
import dev.jtiisto.wellnesssync.core.ble.model.SensorPriority
import dev.jtiisto.wellnesssync.core.database.dao.DeviceSessionDao
import dev.jtiisto.wellnesssync.core.database.entity.DeviceSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.UUID

class BleCaptureService : Service() {

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val ACTION_STOP = "dev.jtiisto.wellnesssync.STOP_CAPTURE"
        private const val WAKE_LOCK_TAG = "WellnessSync:BleCapture"

        fun startIntent(context: Context, deviceAddress: String, deviceName: String?): Intent {
            return Intent(context, BleCaptureService::class.java).apply {
                putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
                putExtra(EXTRA_DEVICE_NAME, deviceName ?: deviceAddress)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, BleCaptureService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }

    private val serviceState: MutableStateFlow<BleCaptureServiceState> by inject()
    private val intervalBuffer: IntervalBuffer by inject()
    private val multiplexer: PriorityMultiplexer by inject()
    private val sessionDao: DeviceSessionDao by inject()
    private val knownDeviceStore: KnownDeviceStore by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var connection: GarminHrmConnection? = null
    private var collectJob: Job? = null
    private var stateObserveJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentSessionId: String? = null
    private var intervalCount = 0

    private val notification by lazy { BleCaptureNotification(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notification.createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_STICKY
        }

        val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
        val name = intent?.getStringExtra(EXTRA_DEVICE_NAME)

        if (address != null) {
            startCapture(address, name ?: address)
        } else {
            // START_STICKY restart with null intent — try to resume from Room
            resumeFromOpenSession()
        }

        return START_STICKY
    }

    private fun startCapture(address: String, name: String) {
        // Avoid double-start
        if (connection != null) return

        val initialState = BleCaptureServiceState(
            isRunning = true,
            connectionState = ConnectionState.SCANNING,
            deviceName = name,
            deviceAddress = address,
        )
        serviceState.value = initialState

        startForeground(BleCaptureNotification.NOTIFICATION_ID, notification.build(initialState))
        acquireWakeLock()

        // Save as known device
        knownDeviceStore.save(address, name)

        serviceScope.launch {
            // Create session
            val sessionId = UUID.randomUUID().toString()
            currentSessionId = sessionId
            val session = DeviceSessionEntity(
                sessionId = sessionId,
                deviceId = address,
                sensorType = "garmin_hrm",
                startTime = System.currentTimeMillis(),
            )
            sessionDao.insert(session)
            serviceState.value = serviceState.value.copy(sessionId = sessionId)

            // Create and start connection
            val conn = GarminHrmConnection(
                context = this@BleCaptureService,
                address = address,
                scope = serviceScope,
            )
            connection = conn

            // Register with multiplexer
            multiplexer.register(address, SensorPriority.GARMIN_ECG, conn.heartRateData)

            // Observe connection state
            stateObserveJob = serviceScope.launch {
                conn.connectionState.collect { connState ->
                    val updated = serviceState.value.copy(connectionState = connState)
                    serviceState.value = updated
                    updateNotification(updated)
                }
            }

            // Collect heart rate data and feed to buffer
            intervalBuffer.start()
            collectJob = serviceScope.launch {
                multiplexer.authoritativeStream.collect { sample ->
                    intervalCount++
                    val updated = serviceState.value.copy(
                        currentHr = sample.heartRateBpm,
                        intervalCount = intervalCount,
                    )
                    serviceState.value = updated
                    updateNotification(updated)
                    intervalBuffer.add(sample, currentSessionId)
                }
            }

            // Connect
            conn.connect()
        }
    }

    private fun resumeFromOpenSession() {
        serviceScope.launch {
            // Find any open session (no endTime) to resume
            // We need to try known devices — pick the most recent open session
            val knownDevices = knownDeviceStore.getAll()
            for (device in knownDevices) {
                val openSession = sessionDao.getActiveSession(device.address)
                if (openSession != null) {
                    currentSessionId = openSession.sessionId
                    startCapture(device.address, device.name)
                    return@launch
                }
            }
            // No open session to resume — stop self
            stopSelf()
        }
    }

    private fun stopCapture() {
        serviceScope.launch {
            // Flush remaining buffer
            intervalBuffer.flush()
            intervalBuffer.stop()

            // Close session
            currentSessionId?.let { sessionId ->
                sessionDao.getById(sessionId)?.let { session ->
                    sessionDao.update(
                        session.copy(
                            endTime = System.currentTimeMillis(),
                            totalIntervals = intervalCount,
                        )
                    )
                }
            }

            // Disconnect BLE
            connection?.disconnect()
            multiplexer.unregister(connection?.deviceId ?: "")

            // Clean up
            collectJob?.cancel()
            stateObserveJob?.cancel()
            connection = null
            currentSessionId = null
            intervalCount = 0

            releaseWakeLock()

            serviceState.value = BleCaptureServiceState()

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun updateNotification(state: BleCaptureServiceState) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(BleCaptureNotification.NOTIFICATION_ID, notification.build(state))
    }

    override fun onDestroy() {
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }
}

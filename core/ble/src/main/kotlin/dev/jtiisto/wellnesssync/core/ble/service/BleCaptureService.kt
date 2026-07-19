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
import dev.jtiisto.wellnesssync.core.ble.di.bleCaptureStateQualifier
import dev.jtiisto.wellnesssync.core.ble.model.ConnectionState
import dev.jtiisto.wellnesssync.core.ble.model.SensorPriority
import dev.jtiisto.wellnesssync.core.common.DiagnosticLog
import dev.jtiisto.wellnesssync.core.database.dao.DeviceSessionDao
import dev.jtiisto.wellnesssync.core.database.dao.IntervalDao
import dev.jtiisto.wellnesssync.core.database.entity.DeviceSessionEntity
import dev.jtiisto.wellnesssync.core.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject
import java.util.UUID

class BleCaptureService : Service() {

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val ACTION_STOP = "dev.jtiisto.wellnesssync.STOP_CAPTURE"
        private const val WAKE_LOCK_TAG = "WellnessSync:BleCapture"
        private const val INACTIVITY_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
        private const val FINAL_FLUSH_ATTEMPTS = 3
        private const val FINAL_FLUSH_RETRY_MS = 2_000L

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

    private val serviceState: MutableStateFlow<BleCaptureServiceState> by inject(bleCaptureStateQualifier)
    private val intervalBuffer: IntervalBuffer by inject()
    private val multiplexer: PriorityMultiplexer by inject()
    private val sessionDao: DeviceSessionDao by inject()
    private val intervalDao: IntervalDao by inject()
    private val knownDeviceStore: KnownDeviceStore by inject()
    private val diagnosticLog: DiagnosticLog by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var connection: GarminHrmConnection? = null
    private var collectJob: Job? = null
    private var stateObserveJob: Job? = null
    private var detailObserveJob: Job? = null
    private var inactivityJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentSessionId: String? = null
    private var intervalCount = 0
    private var startRequested = false

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

    private fun startCapture(address: String, name: String, resumeSessionId: String? = null) {
        // onStartCommand runs on the main thread, so this synchronous flag
        // closes the double-start window before any async work is launched
        if (startRequested || connection != null) return
        startRequested = true

        diagnosticLog.log("capture", "startCapture $name ($address) resume=${resumeSessionId != null}")

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
            try {
                startCaptureAsync(address, name, resumeSessionId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Foreground state and the wake lock are already held — a
                // startup failure must roll everything back or later starts
                // are rejected by a guard nobody will ever reset
                diagnosticLog.log("capture", "startup FAILED: ${e.javaClass.simpleName}: ${e.message}")
                connection?.let { conn -> runCatching { conn.disconnect() } }
                releaseCaptureResources()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun startCaptureAsync(address: String, name: String, resumeSessionId: String?) {
        // Create a new session, or reattach to the open one on resume
        val sessionId = resumeSessionId ?: UUID.randomUUID().toString()
        currentSessionId = sessionId
        if (resumeSessionId == null) {
            sessionDao.insert(
                DeviceSessionEntity(
                    sessionId = sessionId,
                    deviceId = address,
                    sensorType = "garmin_hrm",
                    startTime = System.currentTimeMillis(),
                )
            )
        } else {
            intervalCount = intervalDao.countBySession(sessionId)
        }
        serviceState.value = serviceState.value.copy(
            sessionId = sessionId,
            intervalCount = intervalCount,
        )

        // Create and start connection
        val conn = GarminHrmConnection(
            context = this@BleCaptureService,
            address = address,
            scope = serviceScope,
            diagnosticLog = diagnosticLog,
        )
        connection = conn

        // Register with multiplexer
        multiplexer.register(address, SensorPriority.GARMIN_ECG, conn.heartRateData)

        // Observe connection state and manage inactivity timeout
        stateObserveJob = serviceScope.launch {
            conn.connectionState.collect { connState ->
                val updated = serviceState.value.copy(connectionState = connState)
                serviceState.value = updated
                updateNotification(updated)

                when (connState) {
                    ConnectionState.DISCONNECTED, ConnectionState.RECONNECTING ->
                        startInactivityTimer()
                    ConnectionState.CONNECTED ->
                        cancelInactivityTimer()
                    else -> {} // SCANNING, CONNECTING — no action
                }
            }
        }

        // Surface connect retry progress/failures in the UI
        detailObserveJob = serviceScope.launch {
            conn.connectionDetail.collect { detail ->
                val updated = serviceState.value.copy(error = detail)
                serviceState.value = updated
                updateNotification(updated)
            }
        }

        // Collect heart rate data and feed to buffer
        intervalBuffer.start()
        collectJob = serviceScope.launch {
            multiplexer.authoritativeStream.collect { sample ->
                cancelInactivityTimer()
                // Count stored rows, not notifications — one notification
                // can carry several RR intervals
                intervalCount += intervalBuffer.add(sample, currentSessionId)
                val updated = serviceState.value.copy(
                    currentHr = sample.heartRateBpm,
                    intervalCount = intervalCount,
                )
                serviceState.value = updated
                updateNotification(updated)
            }
        }

        // Connect
        conn.connect()
    }

    private fun resumeFromOpenSession() {
        serviceScope.launch {
            val openSession = sessionDao.getMostRecentActiveSession()
            if (openSession != null) {
                val name = knownDeviceStore.getAll()
                    .firstOrNull { it.address == openSession.deviceId }?.name
                    ?: openSession.deviceId
                diagnosticLog.log("capture", "resuming open session ${openSession.sessionId}")
                startCapture(openSession.deviceId, name, resumeSessionId = openSession.sessionId)
            } else {
                stopSelf()
            }
        }
    }

    private fun stopCapture() {
        diagnosticLog.log("capture", "stopCapture (intervals=$intervalCount)")
        serviceScope.launch {
            // Persist the remaining buffer, retrying briefly on failure
            var flushed = intervalBuffer.flush()
            var attempts = 1
            while (!flushed && attempts < FINAL_FLUSH_ATTEMPTS) {
                delay(FINAL_FLUSH_RETRY_MS)
                flushed = intervalBuffer.flush()
                attempts++
            }

            if (flushed) {
                intervalBuffer.stop()
                // Finalize the session with Room-derived totals — they now
                // provably reflect everything that was captured
                currentSessionId?.let { sessionId ->
                    sessionDao.getById(sessionId)?.let { session ->
                        sessionDao.update(
                            session.copy(
                                endTime = System.currentTimeMillis(),
                                totalIntervals = intervalDao.countBySession(sessionId),
                                averageHr = intervalDao.averageHrBySession(sessionId)?.toInt()
                                    ?: session.averageHr,
                            )
                        )
                    }
                }
            } else {
                // Rows exist only in the singleton buffer, whose own-scoped
                // retry loop stays running as the durable owner. The session
                // stays OPEN so totals are never finalized against stale data;
                // the START_STICKY resume path can reattach and close it later.
                diagnosticLog.log(
                    "capture",
                    "final flush failed after $attempts attempts — buffer retry loop " +
                        "left running, session left open (${intervalBuffer.size} rows pending)",
                )
            }

            // Disconnect BLE
            connection?.disconnect()

            // Trigger sync to push captured data
            SyncWorker.enqueueSyncNow(this@BleCaptureService)

            releaseCaptureResources()

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // Idempotent teardown shared by the normal stop path and system-initiated
    // destruction — shared singletons (state flow, multiplexer) must never be
    // left holding a dead service's state
    private fun releaseCaptureResources() {
        collectJob?.cancel()
        collectJob = null
        stateObserveJob?.cancel()
        stateObserveJob = null
        detailObserveJob?.cancel()
        detailObserveJob = null
        inactivityJob?.cancel()
        inactivityJob = null
        connection?.let { multiplexer.unregister(it.deviceId) }
        connection = null
        currentSessionId = null
        intervalCount = 0
        startRequested = false
        releaseWakeLock()
        serviceState.value = BleCaptureServiceState()
    }

    private fun startInactivityTimer() {
        if (inactivityJob?.isActive == true) return
        inactivityJob = serviceScope.launch {
            delay(INACTIVITY_TIMEOUT_MS)
            diagnosticLog.log("capture", "inactivity timeout (${INACTIVITY_TIMEOUT_MS}ms) — stopping")
            stopCapture()
        }
    }

    private fun cancelInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = null
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
        // System-initiated destruction can bypass stopCapture entirely. The
        // buffer is NOT stopped: it runs on its own scope and keeps retrying
        // to persist whatever rows the dead service left behind.
        if (connection != null) {
            diagnosticLog.log(
                "capture",
                "onDestroy with active capture — emergency teardown (${intervalBuffer.size} rows stay buffered)",
            )
            connection?.let { conn ->
                // disconnect() suspends but does no blocking work — it cancels
                // jobs and closes the GATT handle
                runBlocking { conn.disconnect() }
            }
        }
        releaseCaptureResources()
        serviceScope.cancel()
        super.onDestroy()
    }
}

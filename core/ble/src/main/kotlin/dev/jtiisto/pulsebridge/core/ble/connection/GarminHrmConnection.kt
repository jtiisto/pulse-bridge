package dev.jtiisto.pulsebridge.core.ble.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import dev.jtiisto.pulsebridge.core.common.DiagnosticLog
import dev.jtiisto.pulsebridge.core.ble.model.BleDevice
import dev.jtiisto.pulsebridge.core.ble.model.ConnectionState
import dev.jtiisto.pulsebridge.core.ble.model.HeartRateSample
import dev.jtiisto.pulsebridge.core.ble.model.SensorPriority
import dev.jtiisto.pulsebridge.core.ble.reconnect.ReconnectionStrategy
import dev.jtiisto.pulsebridge.core.ble.scanner.BleScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class GarminHrmConnection(
    private val context: Context,
    private val address: String,
    private val scope: CoroutineScope,
    private val diagnosticLog: DiagnosticLog,
    private val reconnectionStrategy: ReconnectionStrategy = ReconnectionStrategy(),
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val firstSampleTimeoutMs: Long = DEFAULT_FIRST_SAMPLE_TIMEOUT_MS,
    // Address-filtered advertisement scan run alongside each connect attempt
    // so a watchdog abort can say WHICH failure happened (null = don't probe).
    // Spec: specs/advertising_probe.md
    private val advertisementProbe: ((String) -> Flow<Int>)? = null,
) : BleDeviceConnection {

    companion object {
        val HRM_SERVICE_UUID: UUID = BleScanner.HRM_SERVICE_UUID
        val HRM_MEASUREMENT_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val GAP_THRESHOLD_MS = 3000L

        // Android takes ~30 s to report a failed direct connect (and sometimes
        // never calls back at all) — abort sooner so retries stay responsive
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L

        // A link is only proven healthy once real data flows: discovery, CCCD
        // write, and notification delivery can all fail after CONNECTED
        const val DEFAULT_FIRST_SAMPLE_TIMEOUT_MS = 10_000L
    }

    override val deviceId: String = address

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // ~17 min of beats at 1 Hz — a DB stall that outlasts this has bigger
    // problems, and any overflow is gap-marked in the data plus counted below
    private val _heartRateData = MutableSharedFlow<HeartRateSample>(extraBufferCapacity = 1024)
    override val heartRateData: Flow<HeartRateSample> = _heartRateData.asSharedFlow()

    private val droppedSamples = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var dropGapPending = false

    /** Human-readable connect progress/failure detail for the UI; null when healthy. */
    private val _connectionDetail = MutableStateFlow<String?>(null)
    val connectionDetail: StateFlow<String?> = _connectionDetail.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private val lastSampleTimestamp = AtomicLong(0L)
    private var reconnectJob: Job? = null
    private var connectWatchdogJob: Job? = null
    private var firstSampleWatchdogJob: Job? = null

    private enum class ProbeState { INACTIVE, LISTENING, HEARD, UNAVAILABLE }

    private var probeJob: Job? = null

    @Volatile
    private var probeState = ProbeState.INACTIVE

    @Volatile
    private var probeRssi: Int? = null

    /** Probe verdict from the most recent watchdog abort, for retry/give-up messages. */
    @Volatile
    private var lastFailureDiagnosis: String? = null

    @Volatile
    private var awaitingFirstSample = false

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            diagnosticLog.log("garmin", "onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    cancelConnectWatchdog()
                    stopAdvertisementProbe()
                    _connectionState.value = ConnectionState.CONNECTED
                    // Retry budget resets on the FIRST SAMPLE, not here —
                    // resetting on a bare link would let repeated post-connect
                    // failures defeat the attempt bound
                    startFirstSampleWatchdog()
                    if (!gatt.discoverServices()) {
                        // Stack busy — a CONNECTED link with no services never
                        // produces data, so force the disconnect/retry path
                        gatt.disconnect()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    cancelConnectWatchdog()
                    cancelFirstSampleWatchdog()
                    _connectionState.value = ConnectionState.DISCONNECTED
                    gatt.close()
                    this@GarminHrmConnection.gatt = null
                    attemptReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val hrmCharacteristic = if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.getService(HRM_SERVICE_UUID)?.getCharacteristic(HRM_MEASUREMENT_UUID)
            } else {
                null
            }
            diagnosticLog.log(
                "garmin",
                "onServicesDiscovered status=$status hrmCharacteristicFound=${hrmCharacteristic != null}",
            )
            if (hrmCharacteristic == null) {
                // Returning here would leave a silent CONNECTED-without-data
                // stall; disconnecting routes through the retry path instead
                gatt.disconnect()
                return
            }

            if (!gatt.setCharacteristicNotification(hrmCharacteristic, true)) {
                diagnosticLog.log("garmin", "setCharacteristicNotification failed — disconnecting")
                gatt.disconnect()
                return
            }

            val descriptor = hrmCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
            val writeResult = if (descriptor == null) {
                null
            } else {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
            if (writeResult != BluetoothStatusCodes.SUCCESS) {
                diagnosticLog.log("garmin", "CCCD write failed (result=$writeResult) — disconnecting")
                gatt.disconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG) {
                diagnosticLog.log("garmin", "onDescriptorWrite status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // Notifications never actually enabled — without this check
                    // the link would sit "connected" with no data forever
                    gatt.disconnect()
                }
            }
        }

        @Deprecated("Deprecated in API 33, but needed for backward compat with minSdk < 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == HRM_MEASUREMENT_UUID) {
                handleHrmData(characteristic.value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == HRM_MEASUREMENT_UUID) {
                handleHrmData(value)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect() {
        // Close any existing GATT to prevent resource leaks
        gatt?.close()
        gatt = null

        _connectionState.value = ConnectionState.CONNECTING
        // Each attempt owns its probe verdict — a stale one must not label a
        // later failure that the probe never observed
        lastFailureDiagnosis = null
        diagnosticLog.log("garmin", "connect() to $address")

        val newGatt = try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val device: BluetoothDevice = bluetoothManager.adapter.getRemoteDevice(address)
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            diagnosticLog.log("garmin", "connectGatt threw ${e.javaClass.simpleName}: ${e.message}")
            null
        }

        if (newGatt == null) {
            // Adapter off or invalid address — no callback will ever arrive,
            // so this must not be left sitting in CONNECTING
            diagnosticLog.log("garmin", "connectGatt unavailable — scheduling retry")
            _connectionState.value = ConnectionState.DISCONNECTED
            attemptReconnect()
            return
        }

        gatt = newGatt
        startAdvertisementProbe()
        startConnectWatchdog()
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect() {
        diagnosticLog.log("garmin", "disconnect() requested")
        reconnectJob?.cancel()
        reconnectJob = null
        cancelConnectWatchdog()
        cancelFirstSampleWatchdog()
        stopAdvertisementProbe()
        lastFailureDiagnosis = null
        reconnectionStrategy.reset()
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectionDetail.value = null
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private fun handleHrmData(value: ByteArray) {
        val parsed = HrmCharacteristicParser.parse(value) ?: return
        val now = System.currentTimeMillis()
        val previous = lastSampleTimestamp.getAndSet(now)
        val isGap = previous > 0 && (now - previous) > GAP_THRESHOLD_MS

        val gapFromDrop = dropGapPending
        val sample = HeartRateSample(
            deviceId = address,
            timestampDevice = now,
            heartRateBpm = parsed.heartRateBpm,
            rrIntervalsMs = parsed.rrIntervalsMs,
            sensorPriority = SensorPriority.GARMIN_ECG,
            sensorType = BleDevice.SENSOR_TYPE_GARMIN_HRM,
            isGapBefore = isGap || gapFromDrop,
        )

        if (awaitingFirstSample) {
            awaitingFirstSample = false
            cancelFirstSampleWatchdog()
            // Data flowing is the real definition of a healthy link
            reconnectionStrategy.reset()
            _connectionDetail.value = null
            lastFailureDiagnosis = null
            diagnosticLog.log("garmin", "first sample received — link healthy")
        }

        if (_heartRateData.tryEmit(sample)) {
            if (gapFromDrop) dropGapPending = false
        } else {
            // The measurement is lost — record it in the DATA (gap marker on
            // the next stored sample) and in a cumulative counter, not just logs
            dropGapPending = true
            val total = droppedSamples.incrementAndGet()
            diagnosticLog.log(
                "garmin",
                "sample DROPPED (total=$total) — flow buffer full; next stored sample carries a gap marker",
            )
        }
    }

    private fun attemptReconnect() {
        // "(strap not advertising — ...)" or "(strap is advertising — ...)"
        // from the last watchdog abort; empty for non-watchdog failures
        val diagnosis = lastFailureDiagnosis?.let { " ($it)" } ?: ""
        if (!reconnectionStrategy.hasAttemptsRemaining) {
            diagnosticLog.log("garmin", "giving up after ${reconnectionStrategy.currentAttempt} attempts$diagnosis")
            _connectionDetail.value =
                "Unable to connect after ${reconnectionStrategy.currentAttempt} attempts$diagnosis"
            return
        }
        _connectionState.value = ConnectionState.RECONNECTING

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayDuration = reconnectionStrategy.nextDelay()
            diagnosticLog.log(
                "garmin",
                "attempt ${reconnectionStrategy.currentAttempt} failed$diagnosis — retrying in $delayDuration",
            )
            _connectionDetail.value =
                "Connect attempt ${reconnectionStrategy.currentAttempt} failed$diagnosis — retrying"
            delay(delayDuration)
            if (_connectionState.value == ConnectionState.RECONNECTING) {
                connect()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startConnectWatchdog() {
        connectWatchdogJob?.cancel()
        connectWatchdogJob = scope.launch {
            delay(connectTimeoutMs)
            if (_connectionState.value == ConnectionState.CONNECTING) {
                // Read the probe verdict BEFORE tearing the probe down
                lastFailureDiagnosis = when (probeState) {
                    ProbeState.HEARD ->
                        "strap is advertising, rssi=$probeRssi — connection failed or rejected"
                    ProbeState.LISTENING ->
                        "strap not advertising — likely held by another device (watch/ANT+) or asleep"
                    // UNAVAILABLE/INACTIVE: probe told us nothing — never
                    // claim "not advertising" without having listened
                    else -> null
                }
                stopAdvertisementProbe()
                val diagnosis = lastFailureDiagnosis?.let { " ($it)" } ?: ""
                diagnosticLog.log(
                    "garmin",
                    "watchdog fired after ${connectTimeoutMs}ms — aborting connect$diagnosis",
                )
                gatt?.close()
                gatt = null
                _connectionState.value = ConnectionState.DISCONNECTED
                attemptReconnect()
            }
        }
    }

    private fun startAdvertisementProbe() {
        val probe = advertisementProbe ?: return
        probeJob?.cancel()
        probeState = ProbeState.LISTENING
        probeRssi = null
        probeJob = scope.launch {
            try {
                // first() cancels the underlying scan after one advertisement —
                // one confirmation per attempt is all the verdict needs
                val rssi = probe(address).first()
                probeState = ProbeState.HEARD
                probeRssi = rssi
                diagnosticLog.log("garmin", "probe: advertisement heard rssi=$rssi")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Scan failed or completed empty — the verdict must stay
                // agnostic, not read as "strap silent"
                if (probeState == ProbeState.LISTENING) {
                    probeState = ProbeState.UNAVAILABLE
                    diagnosticLog.log("garmin", "probe unavailable: ${e.message}")
                }
            }
        }
    }

    private fun stopAdvertisementProbe() {
        probeJob?.cancel()
        probeJob = null
        probeState = ProbeState.INACTIVE
    }

    private fun cancelConnectWatchdog() {
        connectWatchdogJob?.cancel()
        connectWatchdogJob = null
    }

    @SuppressLint("MissingPermission")
    private fun startFirstSampleWatchdog() {
        awaitingFirstSample = true
        firstSampleWatchdogJob?.cancel()
        firstSampleWatchdogJob = scope.launch {
            delay(firstSampleTimeoutMs)
            if (awaitingFirstSample && _connectionState.value == ConnectionState.CONNECTED) {
                diagnosticLog.log(
                    "garmin",
                    "no data ${firstSampleTimeoutMs}ms after connect — disconnecting to retry",
                )
                gatt?.disconnect()
            }
        }
    }

    private fun cancelFirstSampleWatchdog() {
        awaitingFirstSample = false
        firstSampleWatchdogJob?.cancel()
        firstSampleWatchdogJob = null
    }
}

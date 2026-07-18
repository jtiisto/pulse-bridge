package dev.jtiisto.wellnesssync.core.ble.connection

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
import dev.jtiisto.wellnesssync.core.common.DiagnosticLog
import dev.jtiisto.wellnesssync.core.ble.model.BleDevice
import dev.jtiisto.wellnesssync.core.ble.model.ConnectionState
import dev.jtiisto.wellnesssync.core.ble.model.HeartRateSample
import dev.jtiisto.wellnesssync.core.ble.model.SensorPriority
import dev.jtiisto.wellnesssync.core.ble.reconnect.ReconnectionStrategy
import dev.jtiisto.wellnesssync.core.ble.scanner.BleScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : BleDeviceConnection {

    companion object {
        val HRM_SERVICE_UUID: UUID = BleScanner.HRM_SERVICE_UUID
        val HRM_MEASUREMENT_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val GAP_THRESHOLD_MS = 3000L

        // Android takes ~30 s to report a failed direct connect (and sometimes
        // never calls back at all) — abort sooner so retries stay responsive
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L
    }

    override val deviceId: String = address

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _heartRateData = MutableSharedFlow<HeartRateSample>(extraBufferCapacity = 64)
    override val heartRateData: Flow<HeartRateSample> = _heartRateData.asSharedFlow()

    /** Human-readable connect progress/failure detail for the UI; null when healthy. */
    private val _connectionDetail = MutableStateFlow<String?>(null)
    val connectionDetail: StateFlow<String?> = _connectionDetail.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private val lastSampleTimestamp = AtomicLong(0L)
    private var reconnectJob: Job? = null
    private var connectWatchdogJob: Job? = null

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            diagnosticLog.log("garmin", "onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    cancelConnectWatchdog()
                    _connectionState.value = ConnectionState.CONNECTED
                    _connectionDetail.value = null
                    reconnectionStrategy.reset()
                    if (!gatt.discoverServices()) {
                        // Stack busy — a CONNECTED link with no services never
                        // produces data, so force the disconnect/retry path
                        gatt.disconnect()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    cancelConnectWatchdog()
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

            gatt.setCharacteristicNotification(hrmCharacteristic, true)

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
        startConnectWatchdog()
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect() {
        diagnosticLog.log("garmin", "disconnect() requested")
        reconnectJob?.cancel()
        reconnectJob = null
        cancelConnectWatchdog()
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

        val sample = HeartRateSample(
            deviceId = address,
            timestampDevice = now,
            heartRateBpm = parsed.heartRateBpm,
            rrIntervalsMs = parsed.rrIntervalsMs,
            sensorPriority = SensorPriority.GARMIN_ECG,
            sensorType = BleDevice.SENSOR_TYPE_GARMIN_HRM,
            isGapBefore = isGap,
        )
        _heartRateData.tryEmit(sample)
    }

    private fun attemptReconnect() {
        if (!reconnectionStrategy.hasAttemptsRemaining) {
            diagnosticLog.log("garmin", "giving up after ${reconnectionStrategy.currentAttempt} attempts")
            _connectionDetail.value =
                "Unable to connect after ${reconnectionStrategy.currentAttempt} attempts"
            return
        }
        _connectionState.value = ConnectionState.RECONNECTING

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayDuration = reconnectionStrategy.nextDelay()
            diagnosticLog.log(
                "garmin",
                "attempt ${reconnectionStrategy.currentAttempt} failed — retrying in $delayDuration",
            )
            _connectionDetail.value =
                "Connect attempt ${reconnectionStrategy.currentAttempt} failed — retrying"
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
                diagnosticLog.log("garmin", "watchdog fired after ${connectTimeoutMs}ms — aborting connect")
                gatt?.close()
                gatt = null
                _connectionState.value = ConnectionState.DISCONNECTED
                attemptReconnect()
            }
        }
    }

    private fun cancelConnectWatchdog() {
        connectWatchdogJob?.cancel()
        connectWatchdogJob = null
    }
}

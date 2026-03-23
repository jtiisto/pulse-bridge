package dev.jtiisto.wellnesssync.core.ble.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
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
    private val reconnectionStrategy: ReconnectionStrategy = ReconnectionStrategy(),
) : BleDeviceConnection {

    companion object {
        val HRM_SERVICE_UUID: UUID = BleScanner.HRM_SERVICE_UUID
        val HRM_MEASUREMENT_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val GAP_THRESHOLD_MS = 3000L
    }

    override val deviceId: String = address

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _heartRateData = MutableSharedFlow<HeartRateSample>(extraBufferCapacity = 64)
    override val heartRateData: Flow<HeartRateSample> = _heartRateData.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private val lastSampleTimestamp = AtomicLong(0L)
    private var reconnectJob: Job? = null

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = ConnectionState.CONNECTED
                    reconnectionStrategy.reset()
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    gatt.close()
                    this@GarminHrmConnection.gatt = null
                    attemptReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val hrmService = gatt.getService(HRM_SERVICE_UUID) ?: return
            val hrmCharacteristic = hrmService.getCharacteristic(HRM_MEASUREMENT_UUID) ?: return

            gatt.setCharacteristicNotification(hrmCharacteristic, true)

            val descriptor = hrmCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
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

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device: BluetoothDevice = bluetoothManager.adapter.getRemoteDevice(address)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectionStrategy.reset()
        _connectionState.value = ConnectionState.DISCONNECTED
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
        if (!reconnectionStrategy.hasAttemptsRemaining) return
        _connectionState.value = ConnectionState.RECONNECTING

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayDuration = reconnectionStrategy.nextDelay()
            delay(delayDuration)
            if (_connectionState.value == ConnectionState.RECONNECTING) {
                connect()
            }
        }
    }
}

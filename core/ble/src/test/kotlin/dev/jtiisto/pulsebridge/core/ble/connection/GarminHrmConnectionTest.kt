package dev.jtiisto.pulsebridge.core.ble.connection

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import dev.jtiisto.pulsebridge.core.ble.model.ConnectionState
import dev.jtiisto.pulsebridge.core.ble.reconnect.ReconnectionConfig
import dev.jtiisto.pulsebridge.core.ble.reconnect.ReconnectionStrategy
import dev.jtiisto.pulsebridge.core.common.DiagnosticLog
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises the connect-path hardening: no code path may leave the state
 * machine silently stuck in CONNECTING (or CONNECTED without data).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GarminHrmConnectionTest {

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }

    private val gattMock = mockk<BluetoothGatt>(relaxed = true)
    private val callbackSlot = slot<BluetoothGattCallback>()

    private fun mockContext(connectResult: BluetoothGatt?): Context {
        val device = mockk<BluetoothDevice>()
        every {
            device.connectGatt(any(), any(), capture(callbackSlot), any<Int>())
        } returns connectResult
        val btAdapter = mockk<BluetoothAdapter>()
        every { btAdapter.getRemoteDevice(ADDRESS) } returns device
        val btManager = mockk<BluetoothManager>()
        every { btManager.adapter } returns btAdapter
        val context = mockk<Context>()
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns btManager
        return context
    }

    private fun TestScope.connection(
        context: Context,
        maxAttempts: Int,
        advertisementProbe: ((String) -> Flow<Int>)? = null,
    ) = GarminHrmConnection(
        context = context,
        address = ADDRESS,
        scope = this,
        diagnosticLog = DiagnosticLog(),
        reconnectionStrategy = ReconnectionStrategy(ReconnectionConfig(maxAttempts = maxAttempts)),
        advertisementProbe = advertisementProbe,
    )

    @Test
    fun `null connectGatt result never leaves state stuck in CONNECTING`() = runTest {
        val conn = connection(mockContext(connectResult = null), maxAttempts = 2)

        conn.connect()

        assertEquals(ConnectionState.RECONNECTING, conn.connectionState.value)

        advanceUntilIdle()

        assertEquals(ConnectionState.DISCONNECTED, conn.connectionState.value)
        assertTrue(conn.connectionDetail.value!!.contains("Unable to connect"))
    }

    @Test
    fun `exception during connect is treated as a failed attempt`() = runTest {
        val btAdapter = mockk<BluetoothAdapter>()
        every { btAdapter.getRemoteDevice(any<String>()) } throws
            IllegalArgumentException("invalid address")
        val btManager = mockk<BluetoothManager>()
        every { btManager.adapter } returns btAdapter
        val context = mockk<Context>()
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns btManager

        val conn = connection(context, maxAttempts = 0)

        conn.connect()

        assertEquals(ConnectionState.DISCONNECTED, conn.connectionState.value)
        assertNotNull(conn.connectionDetail.value)
    }

    @Test
    fun `watchdog aborts a connect attempt that never calls back`() = runTest {
        val conn = connection(mockContext(gattMock), maxAttempts = 1)

        conn.connect()
        assertEquals(ConnectionState.CONNECTING, conn.connectionState.value)

        advanceTimeBy(GarminHrmConnection.DEFAULT_CONNECT_TIMEOUT_MS + 1)

        verify { gattMock.close() }
        assertEquals(ConnectionState.RECONNECTING, conn.connectionState.value)

        conn.disconnect() // cancel the pending retry so the test scope can finish
    }

    @Test
    fun `connected callback cancels the watchdog`() = runTest {
        every { gattMock.discoverServices() } returns true
        val conn = connection(mockContext(gattMock), maxAttempts = 1)

        conn.connect()
        callbackSlot.captured.onConnectionStateChange(
            gattMock, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED,
        )
        assertEquals(ConnectionState.CONNECTED, conn.connectionState.value)

        advanceTimeBy(GarminHrmConnection.DEFAULT_CONNECT_TIMEOUT_MS * 2)

        assertEquals(ConnectionState.CONNECTED, conn.connectionState.value)
        verify(exactly = 0) { gattMock.close() }

        conn.disconnect()
    }

    private fun stubHrmService(
        notificationEnableSucceeds: Boolean = true,
        cccdWriteResult: Int = 0, // BluetoothStatusCodes.SUCCESS
    ): BluetoothGattDescriptor {
        val descriptor = mockk<BluetoothGattDescriptor>(relaxed = true)
        every { descriptor.uuid } returns GarminHrmConnection.CLIENT_CHARACTERISTIC_CONFIG
        val characteristic = mockk<BluetoothGattCharacteristic>(relaxed = true)
        every { characteristic.getDescriptor(any()) } returns descriptor
        val service = mockk<BluetoothGattService>()
        every { service.getCharacteristic(any()) } returns characteristic
        every { gattMock.getService(any()) } returns service
        every { gattMock.discoverServices() } returns true
        every { gattMock.setCharacteristicNotification(any(), any()) } returns notificationEnableSucceeds
        every { gattMock.writeDescriptor(any(), any()) } returns cccdWriteResult
        return descriptor
    }

    @Test
    fun `failed CCCD write forces a disconnect instead of a silent stall`() = runTest {
        stubHrmService(cccdWriteResult = 201) // any non-SUCCESS status
        val conn = connection(mockContext(gattMock), maxAttempts = 1)

        conn.connect()
        callbackSlot.captured.onConnectionStateChange(
            gattMock, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED,
        )
        callbackSlot.captured.onServicesDiscovered(gattMock, BluetoothGatt.GATT_SUCCESS)

        verify { gattMock.disconnect() }

        conn.disconnect()
    }

    @Test
    fun `failed notification enable forces a disconnect`() = runTest {
        stubHrmService(notificationEnableSucceeds = false)
        val conn = connection(mockContext(gattMock), maxAttempts = 1)

        conn.connect()
        callbackSlot.captured.onConnectionStateChange(
            gattMock, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED,
        )
        callbackSlot.captured.onServicesDiscovered(gattMock, BluetoothGatt.GATT_SUCCESS)

        verify { gattMock.disconnect() }
        verify(exactly = 0) { gattMock.writeDescriptor(any(), any()) }

        conn.disconnect()
    }

    @Test
    fun `failed descriptor write completion forces a disconnect`() = runTest {
        val descriptor = stubHrmService()
        val conn = connection(mockContext(gattMock), maxAttempts = 1)

        conn.connect()
        callbackSlot.captured.onConnectionStateChange(
            gattMock, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED,
        )
        callbackSlot.captured.onDescriptorWrite(gattMock, descriptor, BluetoothGatt.GATT_FAILURE)

        verify { gattMock.disconnect() }

        conn.disconnect()
    }

    @Test
    fun `no data after connect triggers a disconnect so the retry path runs`() = runTest {
        every { gattMock.discoverServices() } returns true
        val conn = connection(mockContext(gattMock), maxAttempts = 1)

        conn.connect()
        callbackSlot.captured.onConnectionStateChange(
            gattMock, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED,
        )

        advanceTimeBy(GarminHrmConnection.DEFAULT_FIRST_SAMPLE_TIMEOUT_MS + 1)

        verify { gattMock.disconnect() }

        conn.disconnect()
    }

    @Test
    fun `retry budget is not reset by a bare connection without data`() = runTest {
        every { gattMock.discoverServices() } returns true
        val conn = connection(mockContext(gattMock), maxAttempts = 1)

        conn.connect()
        // Link comes up but never delivers data, then drops — the attempt
        // budget must not be refilled by the bare CONNECTED transition
        callbackSlot.captured.onConnectionStateChange(
            gattMock, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED,
        )
        callbackSlot.captured.onConnectionStateChange(
            gattMock, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED,
        )

        advanceUntilIdle()

        assertEquals(ConnectionState.DISCONNECTED, conn.connectionState.value)
        assertTrue(conn.connectionDetail.value!!.contains("Unable to connect after 1"))
    }

    // --- Advertising probe (spec: specs/advertising_probe.md) ---

    @Test
    fun `silent probe labels the watchdog abort as strap not advertising`() = runTest {
        val conn = connection(
            mockContext(gattMock),
            maxAttempts = 0,
            advertisementProbe = { MutableSharedFlow<Int>() }, // listens, hears nothing
        )

        conn.connect()
        advanceTimeBy(GarminHrmConnection.DEFAULT_CONNECT_TIMEOUT_MS + 1)

        assertTrue(conn.connectionDetail.value!!.contains("strap not advertising"))

        conn.disconnect()
    }

    @Test
    fun `heard advertisement labels the watchdog abort as connect failure`() = runTest {
        val conn = connection(
            mockContext(gattMock),
            maxAttempts = 0,
            advertisementProbe = { flowOf(-58) },
        )

        conn.connect()
        advanceTimeBy(GarminHrmConnection.DEFAULT_CONNECT_TIMEOUT_MS + 1)

        val detail = conn.connectionDetail.value!!
        assertTrue(detail.contains("strap is advertising"))
        assertTrue(detail.contains("rssi=-58"))

        conn.disconnect()
    }

    @Test
    fun `failed probe never claims the strap is silent`() = runTest {
        val conn = connection(
            mockContext(gattMock),
            maxAttempts = 0,
            advertisementProbe = {
                kotlinx.coroutines.flow.flow { throw IllegalStateException("scan failed") }
            },
        )

        conn.connect()
        advanceTimeBy(GarminHrmConnection.DEFAULT_CONNECT_TIMEOUT_MS + 1)

        val detail = conn.connectionDetail.value!!
        assertTrue(detail.contains("Unable to connect"))
        assertTrue(!detail.contains("strap not advertising"))
        assertTrue(!detail.contains("strap is advertising"))

        conn.disconnect()
    }

    @Test
    fun `probe stops after the first advertisement`() = runTest {
        var collected = 0
        val conn = connection(
            mockContext(gattMock),
            maxAttempts = 0,
            advertisementProbe = {
                kotlinx.coroutines.flow.flow {
                    while (true) {
                        collected++
                        emit(-60)
                        kotlinx.coroutines.delay(100)
                    }
                }
            },
        )

        conn.connect()
        advanceTimeBy(GarminHrmConnection.DEFAULT_CONNECT_TIMEOUT_MS + 1)

        // first() must cancel the scan after one emission — a probe that keeps
        // the radio scanning for the whole window defeats its own purpose
        assertEquals(1, collected)

        conn.disconnect()
    }

    @Test
    fun `successful connection cancels the probe`() = runTest {
        var cancelled = false
        every { gattMock.discoverServices() } returns true
        val conn = connection(
            mockContext(gattMock),
            maxAttempts = 1,
            advertisementProbe = {
                kotlinx.coroutines.flow.flow<Int> {
                    try {
                        kotlinx.coroutines.awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            },
        )

        conn.connect()
        runCurrent() // let the probe coroutine actually start collecting
        callbackSlot.captured.onConnectionStateChange(
            gattMock, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED,
        )
        advanceUntilIdle()

        assertTrue(cancelled)

        conn.disconnect()
    }

    @Test
    fun `probe verdict does not leak into a non-watchdog failure on the next attempt`() = runTest {
        // Attempt 1: watchdog abort with a silent probe. Attempt 2 (the last):
        // the strap answers and then drops with a real GATT callback — that
        // failure was not observed by a probe and the final give-up message
        // must not carry the stale verdict.
        val conn = connection(
            mockContext(gattMock),
            maxAttempts = 1,
            advertisementProbe = { MutableSharedFlow<Int>() },
        )

        conn.connect()
        advanceTimeBy(GarminHrmConnection.DEFAULT_CONNECT_TIMEOUT_MS + 1)
        assertTrue(conn.connectionDetail.value!!.contains("strap not advertising"))

        // Let the 1 s retry issue connect() again — but stop the clock before
        // its watchdog can fire, so the failure comes from the callback alone
        advanceTimeBy(1_100)
        runCurrent()
        callbackSlot.captured.onConnectionStateChange(
            gattMock, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED,
        )
        callbackSlot.captured.onConnectionStateChange(
            gattMock, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED,
        )
        advanceUntilIdle()

        val detail = conn.connectionDetail.value!!
        assertTrue(detail.contains("Unable to connect"))
        assertTrue(!detail.contains("strap not advertising"))

        conn.disconnect()
    }

    @Test
    fun `missing HR service forces a disconnect instead of a silent stall`() = runTest {
        every { gattMock.discoverServices() } returns true
        every { gattMock.getService(any()) } returns null
        val conn = connection(mockContext(gattMock), maxAttempts = 1)

        conn.connect()
        callbackSlot.captured.onConnectionStateChange(
            gattMock, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED,
        )
        callbackSlot.captured.onServicesDiscovered(gattMock, BluetoothGatt.GATT_SUCCESS)

        verify { gattMock.disconnect() }

        conn.disconnect()
    }
}

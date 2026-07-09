package dev.jtiisto.wellnesssync.core.ble.connection

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import dev.jtiisto.wellnesssync.core.ble.model.ConnectionState
import dev.jtiisto.wellnesssync.core.ble.reconnect.ReconnectionConfig
import dev.jtiisto.wellnesssync.core.ble.reconnect.ReconnectionStrategy
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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

    private fun TestScope.connection(context: Context, maxAttempts: Int) =
        GarminHrmConnection(
            context = context,
            address = ADDRESS,
            scope = this,
            reconnectionStrategy = ReconnectionStrategy(ReconnectionConfig(maxAttempts = maxAttempts)),
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

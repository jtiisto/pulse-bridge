package dev.jtiisto.pulsebridge.core.ble.connection

import app.cash.turbine.test
import dev.jtiisto.pulsebridge.core.ble.model.HeartRateSample
import dev.jtiisto.pulsebridge.core.ble.model.SensorPriority
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PriorityMultiplexerTest {

    private fun createSample(
        deviceId: String = "device-1",
        heartRateBpm: Int = 72,
        sensorPriority: SensorPriority = SensorPriority.GARMIN_ECG,
        sensorType: String = "garmin_hrm",
    ) = HeartRateSample(
        deviceId = deviceId,
        timestampDevice = System.currentTimeMillis(),
        heartRateBpm = heartRateBpm,
        rrIntervalsMs = listOf(833),
        sensorPriority = sensorPriority,
        sensorType = sensorType,
    )

    @Test
    fun `no sources registered means null active sensor`() {
        val mux = PriorityMultiplexer()
        assertNull(mux.activeSensorId.value)
    }

    @Test
    fun `single source becomes active`() {
        val mux = PriorityMultiplexer()
        val flow = MutableSharedFlow<HeartRateSample>()

        mux.register("garmin-001", SensorPriority.GARMIN_ECG, flow)
        assertEquals("garmin-001", mux.activeSensorId.value)
    }

    @Test
    fun `higher priority source takes over`() {
        val mux = PriorityMultiplexer()
        val polarFlow = MutableSharedFlow<HeartRateSample>()
        val garminFlow = MutableSharedFlow<HeartRateSample>()

        mux.register("polar-001", SensorPriority.POLAR_PPG, polarFlow)
        assertEquals("polar-001", mux.activeSensorId.value)

        mux.register("garmin-001", SensorPriority.GARMIN_ECG, garminFlow)
        assertEquals("garmin-001", mux.activeSensorId.value)
    }

    @Test
    fun `unregistering active source falls back to next`() {
        val mux = PriorityMultiplexer()
        val polarFlow = MutableSharedFlow<HeartRateSample>()
        val garminFlow = MutableSharedFlow<HeartRateSample>()

        mux.register("polar-001", SensorPriority.POLAR_PPG, polarFlow)
        mux.register("garmin-001", SensorPriority.GARMIN_ECG, garminFlow)
        assertEquals("garmin-001", mux.activeSensorId.value)

        mux.unregister("garmin-001")
        assertEquals("polar-001", mux.activeSensorId.value)
    }

    @Test
    fun `unregistering last source sets active to null`() {
        val mux = PriorityMultiplexer()
        val flow = MutableSharedFlow<HeartRateSample>()

        mux.register("garmin-001", SensorPriority.GARMIN_ECG, flow)
        mux.unregister("garmin-001")
        assertNull(mux.activeSensorId.value)
    }

    @Test
    fun `authoritative stream emits from active source`() = runTest {
        val mux = PriorityMultiplexer()
        val flow = MutableSharedFlow<HeartRateSample>()

        mux.register("garmin-001", SensorPriority.GARMIN_ECG, flow)

        mux.authoritativeStream.test {
            val sample = createSample(deviceId = "garmin-001", heartRateBpm = 150)
            flow.emit(sample)
            assertEquals(150, awaitItem().heartRateBpm)
        }
    }

    @Test
    fun `authoritative stream switches when higher priority registers`() = runTest {
        val mux = PriorityMultiplexer()
        val polarFlow = MutableSharedFlow<HeartRateSample>()
        val garminFlow = MutableSharedFlow<HeartRateSample>()

        mux.register("polar-001", SensorPriority.POLAR_PPG, polarFlow)

        mux.authoritativeStream.test {
            val polarSample = createSample(deviceId = "polar-001", heartRateBpm = 65, sensorPriority = SensorPriority.POLAR_PPG, sensorType = "polar_pvs")
            polarFlow.emit(polarSample)
            assertEquals(65, awaitItem().heartRateBpm)

            // Garmin connects — should become authoritative
            mux.register("garmin-001", SensorPriority.GARMIN_ECG, garminFlow)

            val garminSample = createSample(deviceId = "garmin-001", heartRateBpm = 150)
            garminFlow.emit(garminSample)
            assertEquals(150, awaitItem().heartRateBpm)
        }
    }
}

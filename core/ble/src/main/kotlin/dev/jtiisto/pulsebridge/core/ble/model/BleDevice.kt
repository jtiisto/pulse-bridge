package dev.jtiisto.pulsebridge.core.ble.model

sealed class BleDevice(
    open val address: String,
    open val name: String?,
    val sensorType: String,
    val priority: SensorPriority,
) {
    data class GarminHrm(
        override val address: String,
        override val name: String? = null,
    ) : BleDevice(
        address = address,
        name = name,
        sensorType = SENSOR_TYPE_GARMIN_HRM,
        priority = SensorPriority.GARMIN_ECG,
    )

    data class PolarPvs(
        override val address: String,
        override val name: String? = null,
    ) : BleDevice(
        address = address,
        name = name,
        sensorType = SENSOR_TYPE_POLAR_PVS,
        priority = SensorPriority.POLAR_PPG,
    )

    companion object {
        const val SENSOR_TYPE_GARMIN_HRM = "garmin_hrm"
        const val SENSOR_TYPE_POLAR_PVS = "polar_pvs"
    }
}

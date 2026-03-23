package dev.jtiisto.wellnesssync.core.ble.model

enum class SensorPriority(val rank: Int) {
    GARMIN_ECG(rank = 1),
    POLAR_PPG(rank = 2),
}

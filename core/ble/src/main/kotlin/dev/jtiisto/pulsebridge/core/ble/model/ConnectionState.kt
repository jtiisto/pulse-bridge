package dev.jtiisto.pulsebridge.core.ble.model

enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
}

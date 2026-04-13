package com.kyenet.ksnetlite.net

data class NetworkProfile(
    val packetLossPercent: Int = 0,
    val latencyMs: Int = 0,
    val jitterMs: Int = 0,
    val bandwidthKbps: Int = 0
) {
    init {
        require(packetLossPercent in 0..100)
        require(latencyMs >= 0)
        require(jitterMs >= 0)
        require(bandwidthKbps >= 0)
    }
}

enum class PresetProfile(val label: String, val profile: NetworkProfile) {
    NORMAL(
        "正常网络",
        NetworkProfile(packetLossPercent = 0, latencyMs = 0, jitterMs = 0, bandwidthKbps = 0)
    ),
    OFFLINE(
        "断网",
        NetworkProfile(packetLossPercent = 100, latencyMs = 0, jitterMs = 0, bandwidthKbps = 0)
    ),
    WIFI_GOOD(
        "WiFi 良好",
        NetworkProfile(packetLossPercent = 0, latencyMs = 20, jitterMs = 5, bandwidthKbps = 20000)
    ),
    FOUR_G(
        "4G",
        NetworkProfile(packetLossPercent = 1, latencyMs = 50, jitterMs = 15, bandwidthKbps = 10000)
    ),
    THREE_G(
        "3G",
        NetworkProfile(packetLossPercent = 3, latencyMs = 120, jitterMs = 30, bandwidthKbps = 2000)
    ),
    TWO_G(
        "2G",
        NetworkProfile(packetLossPercent = 8, latencyMs = 350, jitterMs = 80, bandwidthKbps = 256)
    ),
    BAD_NETWORK(
        "极差网络",
        NetworkProfile(packetLossPercent = 20, latencyMs = 800, jitterMs = 150, bandwidthKbps = 128)
    )
}

package com.kyenet.ksnetlite.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TrafficStats(
    val totalPackets: Long = 0,
    val droppedPackets: Long = 0,
    val shapedBytes: Long = 0
)

object StatsStore {
    private val _stats = MutableStateFlow(TrafficStats())
    val stats: StateFlow<TrafficStats> = _stats.asStateFlow()

    fun onPacketDropped() {
        _stats.value = _stats.value.copy(
            totalPackets = _stats.value.totalPackets + 1,
            droppedPackets = _stats.value.droppedPackets + 1
        )
    }

    fun onPacketForwarded(bytes: Int) {
        _stats.value = _stats.value.copy(
            totalPackets = _stats.value.totalPackets + 1,
            shapedBytes = _stats.value.shapedBytes + bytes
        )
    }

    fun reset() {
        _stats.value = TrafficStats()
    }
}

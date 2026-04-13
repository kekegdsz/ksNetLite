package com.kyenet.ksnetlite.net

import kotlin.math.max
import kotlin.math.min

class TrafficShaper {
    private var tokensBytes: Double = 0.0
    private var lastRefillNanos: Long = System.nanoTime()

    fun computeRequiredDelayMs(packetSizeBytes: Int, profile: NetworkProfile): Long {
        val bandwidthBytesPerSec = profile.bandwidthKbps * 1024.0 / 8.0
        if (bandwidthBytesPerSec <= 0.0) return 0

        refillTokens(bandwidthBytesPerSec)

        val burstLimit = max(bandwidthBytesPerSec, 8_192.0)
        tokensBytes = min(tokensBytes, burstLimit)

        if (tokensBytes >= packetSizeBytes) {
            tokensBytes -= packetSizeBytes
            return 0
        }

        val missingBytes = packetSizeBytes - tokensBytes
        tokensBytes = 0.0
        val seconds = missingBytes / bandwidthBytesPerSec
        return (seconds * 1000).toLong().coerceAtLeast(1L)
    }

    private fun refillTokens(bytesPerSec: Double) {
        val now = System.nanoTime()
        val elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0
        lastRefillNanos = now
        if (elapsedSec <= 0) return
        tokensBytes += bytesPerSec * elapsedSec
    }
}

package com.kyenet.ksnetlite.net

import kotlin.random.Random

class RuleEngine(
    private val random: Random = Random.Default
) {
    fun shouldDropPacket(profile: NetworkProfile): Boolean {
        if (profile.packetLossPercent <= 0) return false
        return random.nextInt(100) < profile.packetLossPercent
    }

    fun computeDelayMs(profile: NetworkProfile): Long {
        val jitter = if (profile.jitterMs == 0) 0 else random.nextInt(profile.jitterMs + 1)
        return (profile.latencyMs + jitter).toLong()
    }
}

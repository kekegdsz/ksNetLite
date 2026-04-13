package com.kyenet.ksnetlite.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ProfileStore {
    private const val PREFS = "ksnet_prefs"
    private const val KEY_LOSS = "loss"
    private const val KEY_LATENCY = "latency"
    private const val KEY_JITTER = "jitter"
    private const val KEY_BW = "bandwidth"

    private val _profile = MutableStateFlow(NetworkProfile())
    val profile: StateFlow<NetworkProfile> = _profile.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _profile.value = NetworkProfile(
            packetLossPercent = prefs.getInt(KEY_LOSS, 0),
            latencyMs = prefs.getInt(KEY_LATENCY, 0),
            jitterMs = prefs.getInt(KEY_JITTER, 0),
            bandwidthKbps = prefs.getInt(KEY_BW, 0)
        )
    }

    fun update(profile: NetworkProfile) {
        _profile.value = profile
    }

    fun persist(context: Context) {
        val p = _profile.value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LOSS, p.packetLossPercent)
            .putInt(KEY_LATENCY, p.latencyMs)
            .putInt(KEY_JITTER, p.jitterMs)
            .putInt(KEY_BW, p.bandwidthKbps)
            .apply()
    }
}

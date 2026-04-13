package com.kyenet.ksnetlite

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kyenet.ksnetlite.net.NetworkProfile
import com.kyenet.ksnetlite.net.PresetProfile
import com.kyenet.ksnetlite.net.ProfileStore
import com.kyenet.ksnetlite.net.KsNetVpnService
import com.kyenet.ksnetlite.net.StatsStore
import com.kyenet.ksnetlite.overlay.OverlayService

class MainActivity : ComponentActivity() {

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            startVpnService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProfileStore.init(this)
        setContent {
            MaterialTheme {
                WeakNetworkControlScreen(
                    onApplyProfile = { profile ->
                        ProfileStore.update(profile)
                        ProfileStore.persist(this)
                        if (profile == PresetProfile.NORMAL.profile) {
                            stopVpnService()
                        }
                    },
                    onStart = { requestAndStartAll() },
                    onStop = { stopAll() }
                )
            }
        }
    }

    private fun requestAndStartAll() {
        requestNotificationPermissionIfNeeded()
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startVpnService()
        }

        startService(Intent(this, OverlayService::class.java))
    }

    private fun startVpnService() {
        val intent = Intent(this, KsNetVpnService::class.java).apply {
            action = KsNetVpnService.ACTION_START
        }
        startService(intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, KsNetVpnService::class.java).apply {
            action = KsNetVpnService.ACTION_STOP
        }
        startService(intent)
        stopService(Intent(this, KsNetVpnService::class.java))
    }

    private fun stopAll() {
        stopVpnService()
        stopService(Intent(this, OverlayService::class.java))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2026)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun WeakNetworkControlScreen(
    onApplyProfile: (NetworkProfile) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val profile by ProfileStore.profile.collectAsStateWithLifecycle()
    val stats by StatsStore.stats.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("KsNetLite 弱网测试工具", style = MaterialTheme.typography.headlineSmall)
        Text(
            "累计包: ${stats.totalPackets}  丢包: ${stats.droppedPackets}  整形字节: ${stats.shapedBytes}",
            style = MaterialTheme.typography.bodySmall
        )

        Text("预置场景")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetProfile.entries.forEach { preset ->
                Button(onClick = { onApplyProfile(preset.profile) }) {
                    Text(preset.label)
                }
            }
        }

        LabeledSlider(
            label = "丢包率: ${profile.packetLossPercent}%",
            value = profile.packetLossPercent.toFloat(),
            valueRange = 0f..100f
        ) {
            onApplyProfile(profile.copy(packetLossPercent = it.toInt()))
        }

        LabeledSlider(
            label = "延迟: ${profile.latencyMs}ms",
            value = profile.latencyMs.toFloat(),
            valueRange = 0f..3000f
        ) {
            onApplyProfile(profile.copy(latencyMs = it.toInt()))
        }

        LabeledSlider(
            label = "抖动: ${profile.jitterMs}ms",
            value = profile.jitterMs.toFloat(),
            valueRange = 0f..500f
        ) {
            onApplyProfile(profile.copy(jitterMs = it.toInt()))
        }

        LabeledSlider(
            label = "带宽上限: ${profile.bandwidthKbps}kbps（0=不限速）",
            value = profile.bandwidthKbps.toFloat(),
            valueRange = 0f..20000f
        ) {
            onApplyProfile(profile.copy(bandwidthKbps = it.toInt()))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onStart) { Text("启动弱网") }
            Button(onClick = onStop) { Text("停止弱网") }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label)
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
    }
}

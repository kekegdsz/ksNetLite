package com.kyenet.ksnetlite.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.kyenet.ksnetlite.MainActivity
import com.kyenet.ksnetlite.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.IOException
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class KsNetVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ruleEngine = RuleEngine()
    private val trafficShaper = TrafficShaper()
    private var tunInterface: ParcelFileDescriptor? = null
    private var tunnelJob: Job? = null
    private var profileWatcherJob: Job? = null
    private var degradationJob: Job? = null
    private var tcpSessionManager: TcpSessionManager? = null
    private var udpProxy: UdpProxy? = null
    private var tunOutput: FileOutputStream? = null
    private val outputMutex = Mutex()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRunningTunnel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("弱网模拟运行中"))
        ServiceStateStore.setRunning(true)
        StatsStore.reset()
        applyLimitedMode(ProfileStore.profile.value)
        startProfileWatcherIfNeeded()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRunningTunnel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
    }

    private fun stopRunningTunnel() {
        ServiceStateStore.setRunning(false)
        profileWatcherJob?.cancel()
        profileWatcherJob = null
        degradationJob?.cancel()
        degradationJob = null
        tcpSessionManager?.shutdown()
        tcpSessionManager = null
        udpProxy = null
        tunnelJob?.cancel()
        tunnelJob = null
        tunOutput?.close()
        tunOutput = null
        tunInterface?.close()
        tunInterface = null
    }

    override fun onRevoke() {
        super.onRevoke()
        ServiceStateStore.setRunning(false)
    }

    private fun startTunnel() {
        if (tunnelJob != null) return

        val builder = Builder()
            .setSession("KsNetLite")
            .addAddress("10.10.0.2", 24)
            .addDnsServer("8.8.8.8")
            .addRoute("0.0.0.0", 0)

        tunInterface = builder.establish() ?: return
        val fd = tunInterface?.fileDescriptor ?: return

        tunOutput = FileOutputStream(fd)
        tcpSessionManager = TcpSessionManager(
            vpnService = this,
            scope = serviceScope,
            ruleEngine = ruleEngine,
            trafficShaper = trafficShaper,
            profileProvider = { ProfileStore.profile.value },
            sendToTun = { packet -> writeToTun(packet) }
        )
        udpProxy = UdpProxy(
            vpnService = this,
            scope = serviceScope,
            ruleEngine = ruleEngine,
            trafficShaper = trafficShaper,
            profileProvider = { ProfileStore.profile.value },
            sendToTun = { packet -> writeToTun(packet) }
        )

        tunnelJob = serviceScope.launch {
            val input = FileInputStream(fd)
            val buffer = ByteArray(32767)

            try {
                while (isActive) {
                    val len = try {
                        input.read(buffer)
                    } catch (_: IOException) {
                        // Tunnel FD may be closed during profile switching.
                        break
                    }
                    if (len <= 0) continue
                    val packet = PacketParser.parse(buffer, len)
                    when (packet) {
                        is PacketParser.ParsedPacket.Tcp -> {
                            tcpSessionManager?.handleClientPacket(packet)
                        }

                        is PacketParser.ParsedPacket.Udp -> {
                            udpProxy?.handleUdpPacket(packet)
                        }

                        null -> Unit
                    }
                }
            } finally {
                runCatching { input.close() }
            }
        }
    }

    private fun startTunnelIfNeeded(profile: NetworkProfile) {
        if (shouldBypassTunnel(profile)) {
            stopTunnelOnly()
            return
        }
        startTunnel()
    }

    private fun startProfileWatcherIfNeeded() {
        if (profileWatcherJob != null) return
        profileWatcherJob = serviceScope.launch {
            ProfileStore.profile.collectLatest { profile ->
                applyLimitedMode(profile)
            }
        }
    }

    private fun applyLimitedMode(profile: NetworkProfile) {
        degradationJob?.cancel()
        degradationJob = null

        val loss = profile.packetLossPercent.coerceIn(0, 100)
        if (loss <= 0) {
            stopTunnelOnly()
            return
        }

        if (loss >= 95) {
            startTunnelIfNeeded(profile)
            return
        }

        // Non-root limited mode:
        // loss directly maps to blocked/pass durations, ensuring obvious behavior changes.
        degradationJob = serviceScope.launch {
            while (isActive) {
                val p = ProfileStore.profile.value
                val currentLoss = p.packetLossPercent.coerceIn(0, 100)
                if (currentLoss <= 0) {
                    stopTunnelOnly()
                    delay(300)
                    continue
                }
                if (currentLoss >= 95) {
                    startTunnel()
                    delay(800)
                    continue
                }

                val cycleMs = 5000L
                val blockMs = ((cycleMs * currentLoss) / 100).coerceIn(300L, 4700L)
                val passMs = (cycleMs - blockMs).coerceAtLeast(300L)

                startTunnel()
                delay(blockMs)
                stopTunnelOnly()
                delay(passMs)
            }
        }
    }

    private fun stopTunnelOnly() {
        tcpSessionManager?.shutdown()
        tcpSessionManager = null
        udpProxy = null
        tunnelJob?.cancel()
        tunnelJob = null
        tunOutput?.close()
        tunOutput = null
        tunInterface?.close()
        tunInterface = null
    }

    private fun shouldBypassTunnel(profile: NetworkProfile): Boolean {
        // Stable fallback mode:
        // - loss < 100%: keep real network available unless limited-mode toggler enables tunnel
        // - loss = 100%: enter full tunnel mode to simulate offline
        return profile.packetLossPercent < 100
    }

    private suspend fun writeToTun(packet: ByteArray) {
        outputMutex.withLock {
            val output = tunOutput ?: return
            output.write(packet)
            output.flush()
        }
        StatsStore.onPacketForwarded(packet.size)
    }

    private fun buildNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("KsNetLite")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "弱网服务",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.kyenet.ksnetlite.action.START_VPN"
        const val ACTION_STOP = "com.kyenet.ksnetlite.action.STOP_VPN"
        private const val CHANNEL_ID = "ksnetlite_vpn"
        private const val NOTIFICATION_ID = 1001
    }
}

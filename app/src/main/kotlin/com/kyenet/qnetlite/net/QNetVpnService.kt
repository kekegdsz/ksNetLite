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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream

class KsNetVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ruleEngine = RuleEngine()
    private val trafficShaper = TrafficShaper()
    private var tunInterface: ParcelFileDescriptor? = null
    private var tunnelJob: Job? = null

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
        startTunnel()
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
        tunnelJob?.cancel()
        tunnelJob = null
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

        tunnelJob = serviceScope.launch {
            val input = FileInputStream(fd)
            val output = FileOutputStream(fd)
            val buffer = ByteArray(32767)

            while (isActive) {
                val len = input.read(buffer)
                if (len <= 0) continue

                val currentProfile = ProfileStore.profile.value
                if (ruleEngine.shouldDropPacket(currentProfile)) {
                    StatsStore.onPacketDropped()
                    continue
                }

                val networkDelayMs = ruleEngine.computeDelayMs(currentProfile)
                val shapeDelayMs = trafficShaper.computeRequiredDelayMs(len, currentProfile)
                val totalDelayMs = networkDelayMs + shapeDelayMs
                if (totalDelayMs > 0) delay(totalDelayMs)

                output.write(buffer, 0, len)
                StatsStore.onPacketForwarded(len)
            }
        }
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

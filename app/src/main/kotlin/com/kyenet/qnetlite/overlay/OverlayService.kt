package com.kyenet.ksnetlite.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt
import com.kyenet.ksnetlite.net.NetworkProfile
import com.kyenet.ksnetlite.net.PresetProfile
import com.kyenet.ksnetlite.net.ProfileStore
import com.kyenet.ksnetlite.net.KsNetVpnService
import com.kyenet.ksnetlite.net.ServiceStateStore
import com.kyenet.ksnetlite.net.StatsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var rootView: LinearLayout? = null
    private var statusView: TextView? = null
    private var startStopButton: Button? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var panelExpanded = true
    private val clickElapsedSeconds = MutableStateFlow(0)
    private var timerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
            setBackgroundColor(0xAA000000.toInt())
        }
        rootView = panel
        val dragBar = TextView(this).apply {
            text = "KsNetLite 快捷操作（按住这里拖动）"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 10)
        }
        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            text = "KsNetLite: 初始化中"
            setPadding(0, 0, 0, 10)
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        startStopButton = quickButton("启动")
        val collapseButton = quickButton("折叠")
        startStopButton?.let { addEqualButton(row1, it) }
        addEqualButton(row1, collapseButton)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val normal = quickButton("正常")
        val offline = quickButton("断网")
        val loss30 = quickButton("丢包30%")
        val loss60 = quickButton("丢包60%")
        addEqualButton(row2, normal)
        addEqualButton(row2, offline)
        addEqualButton(row2, loss30)
        addEqualButton(row2, loss60)

        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val fourG = quickButton("4G")
        val threeG = quickButton("3G")
        val twoG = quickButton("2G")
        val bad = quickButton("差网")
        addEqualButton(row3, fourG)
        addEqualButton(row3, threeG)
        addEqualButton(row3, twoG)
        addEqualButton(row3, bad)

        val row4 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val lossMinus = quickButton("丢-10")
        val lossPlus = quickButton("丢+10")
        val latencyMinus = quickButton("延-50")
        val latencyPlus = quickButton("延+50")
        addEqualButton(row4, lossMinus)
        addEqualButton(row4, lossPlus)
        addEqualButton(row4, latencyMinus)
        addEqualButton(row4, latencyPlus)

        val row5 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val jitterMinus = quickButton("抖-20")
        val jitterPlus = quickButton("抖+20")
        val bandwidthMinus = quickButton("速-10")
        val bandwidthPlus = quickButton("速+10")
        addEqualButton(row5, jitterMinus)
        addEqualButton(row5, jitterPlus)
        addEqualButton(row5, bandwidthMinus)
        addEqualButton(row5, bandwidthPlus)

        panel.addView(dragBar)
        panel.addView(statusView)
        panel.addView(row1)
        panel.addView(row2)
        panel.addView(row3)
        panel.addView(row4)
        panel.addView(row5)

        val layoutParams = WindowManager.LayoutParams(
            ((resources.displayMetrics.widthPixels * 0.92f).roundToInt()),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 160
        }

        dragBar.setOnTouchListener(DragTouchListener(layoutParams))
        startTimerTicker()

        startStopButton?.setOnClickListener {
            resetClickTimer()
            val running = ServiceStateStore.vpnRunning.value
            if (running) stopVpnService()
            else startVpnService()
        }
        collapseButton.setOnClickListener {
            resetClickTimer()
            panelExpanded = !panelExpanded
            row2.visibility = if (panelExpanded) View.VISIBLE else View.GONE
            row3.visibility = if (panelExpanded) View.VISIBLE else View.GONE
            row4.visibility = if (panelExpanded) View.VISIBLE else View.GONE
            row5.visibility = if (panelExpanded) View.VISIBLE else View.GONE
            collapseButton.text = if (panelExpanded) "折叠" else "展开"
        }

        normal.setOnClickListener {
            resetClickTimer()
            applyProfile(PresetProfile.NORMAL.profile)
            stopVpnService()
        }
        offline.setOnClickListener {
            resetClickTimer()
            applyProfileWithRuntimeTransition(PresetProfile.OFFLINE.profile, shouldRunVpn = true)
        }
        loss30.setOnClickListener {
            resetClickTimer()
            applyProfileWithRuntimeTransition(
                ProfileStore.profile.value.copy(packetLossPercent = 30),
                shouldRunVpn = true
            )
        }
        loss60.setOnClickListener {
            resetClickTimer()
            applyProfileWithRuntimeTransition(
                ProfileStore.profile.value.copy(packetLossPercent = 60),
                shouldRunVpn = true
            )
        }

        fourG.setOnClickListener {
            resetClickTimer()
            applyProfileWithRuntimeTransition(PresetProfile.FOUR_G.profile, shouldRunVpn = true)
        }
        threeG.setOnClickListener {
            resetClickTimer()
            applyProfileWithRuntimeTransition(PresetProfile.THREE_G.profile, shouldRunVpn = true)
        }
        twoG.setOnClickListener {
            resetClickTimer()
            applyProfileWithRuntimeTransition(PresetProfile.TWO_G.profile, shouldRunVpn = true)
        }
        bad.setOnClickListener {
            resetClickTimer()
            applyProfileWithRuntimeTransition(PresetProfile.BAD_NETWORK.profile, shouldRunVpn = true)
        }

        lossMinus.setOnClickListener {
            resetClickTimer()
            adjust(lossDelta = -10)
        }
        lossPlus.setOnClickListener {
            resetClickTimer()
            adjust(lossDelta = 10)
        }
        latencyMinus.setOnClickListener {
            resetClickTimer()
            adjust(latencyDelta = -50)
        }
        latencyPlus.setOnClickListener {
            resetClickTimer()
            adjust(latencyDelta = 50)
        }
        jitterMinus.setOnClickListener {
            resetClickTimer()
            adjust(jitterDelta = -20)
        }
        jitterPlus.setOnClickListener {
            resetClickTimer()
            adjust(jitterDelta = 20)
        }
        bandwidthMinus.setOnClickListener {
            resetClickTimer()
            adjust(bandwidthDelta = -10)
        }
        bandwidthPlus.setOnClickListener {
            resetClickTimer()
            adjust(bandwidthDelta = 10)
        }

        windowManager.addView(panel, layoutParams)

        scope.launch {
            combine(ProfileStore.profile, StatsStore.stats, ServiceStateStore.vpnRunning, clickElapsedSeconds) { profile, stats, running, elapsed ->
                "丢包 ${profile.packetLossPercent}% | 延迟 ${profile.latencyMs}ms | " +
                    "抖动 ${profile.jitterMs}ms | 限速 ${profile.bandwidthKbps}kbps\n" +
                    "总包 ${stats.totalPackets} | 丢包 ${stats.droppedPackets} | 状态 ${if (running) "运行" else "停止"} | 计时 ${elapsed}s"
            }.collect { text ->
                statusView?.text = text
                val stateText = if (ServiceStateStore.vpnRunning.value) "停止" else "启动"
                startStopButton?.text = "$stateText ${clickElapsedSeconds.value}s"
            }
        }
    }

    private fun applyProfile(profile: NetworkProfile) {
        ProfileStore.update(profile)
        ProfileStore.persist(this)
    }

    private fun applyProfileWithRuntimeTransition(
        profile: NetworkProfile,
        shouldRunVpn: Boolean
    ) {
        val previous = ProfileStore.profile.value
        applyProfile(profile)

        if (!shouldRunVpn) {
            stopVpnService()
            return
        }

        val wasOffline = previous.packetLossPercent >= 100
        val nowOffline = profile.packetLossPercent >= 100
        if (wasOffline && !nowOffline && ServiceStateStore.vpnRunning.value) {
            restartVpnService()
            return
        }

        ensureVpnRunning()
    }

    private fun adjust(
        lossDelta: Int = 0,
        latencyDelta: Int = 0,
        jitterDelta: Int = 0,
        bandwidthDelta: Int = 0
    ) {
        val p = ProfileStore.profile.value
        val next = p.copy(
            packetLossPercent = (p.packetLossPercent + lossDelta).coerceIn(0, 100),
            latencyMs = (p.latencyMs + latencyDelta).coerceAtLeast(0),
            jitterMs = (p.jitterMs + jitterDelta).coerceAtLeast(0),
            bandwidthKbps = (p.bandwidthKbps + bandwidthDelta).coerceAtLeast(0)
        )
        applyProfile(next)
    }

    private fun quickButton(label: String): Button {
        return Button(this).apply {
            text = label
            textSize = 13f
            minHeight = dp(52)
            minimumHeight = dp(52)
            setPadding(12, 12, 12, 12)
            setAllCaps(false)
        }
    }

    private fun addEqualButton(row: LinearLayout, button: Button) {
        val lp = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
        row.addView(button, lp)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    private fun startVpnService() {
        val intent = Intent(this, KsNetVpnService::class.java).apply {
            action = KsNetVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopVpnService() {
        val intent = Intent(this, KsNetVpnService::class.java).apply {
            action = KsNetVpnService.ACTION_STOP
        }
        startService(intent)
        stopService(Intent(this, KsNetVpnService::class.java))
        ServiceStateStore.setRunning(false)
    }

    private fun restartVpnService() {
        stopVpnService()
        scope.launch {
            delay(150)
            startVpnService()
        }
    }

    private fun ensureVpnRunning() {
        if (!ServiceStateStore.vpnRunning.value) {
            startVpnService()
        }
    }

    private fun startTimerTicker() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (true) {
                delay(1000)
                clickElapsedSeconds.value = clickElapsedSeconds.value + 1
            }
        }
    }

    private fun resetClickTimer() {
        clickElapsedSeconds.value = 0
    }

    override fun onDestroy() {
        super.onDestroy()
        timerJob?.cancel()
        scope.cancel()
        rootView?.let { windowManager.removeView(it) }
        rootView = null
        statusView = null
        startStopButton = null
    }

    private inner class DragTouchListener(
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    rootView?.let { windowManager.updateViewLayout(it, params) }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }
}

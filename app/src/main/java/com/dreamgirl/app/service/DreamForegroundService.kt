package com.dreamgirl.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.dreamgirl.app.data.ServiceLocator
import com.dreamgirl.app.domain.ProactiveEngine
import com.dreamgirl.app.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 常驻前台服务（方案 §7-7：前台服务 + 电池白名单，对抗 MIUI/HyperOS 的后台清理）。
 *
 * 它只做两件事：
 *   1. 挂一个低调的常驻通知，让系统知道"她在"
 *   2. 每隔一段时间问一次 [ProactiveEngine]：现在该不该开口
 *
 * 真正的判断逻辑全在 [ProactiveEngine] 里，这里不掺业务。
 */
class DreamForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var lastSummaryCheckAt = 0L

    /** 通知标题用的显示名。onStartCommand 里不能挂起读 DataStore，所以走缓存。 */
    @Volatile
    private var cachedTitle: String = "dream"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val statusText = "她在看着你的作息（只在本机）"
        Notifications.ensureChannels(this)
        val notification = Notifications.serviceNotification(this, statusText, cachedTitle)
        // 注意：specialUse 这个类型是 API 34 才有的；34 以下不带类型启动（走 manifest 默认）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Notifications.ID_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Notifications.ID_SERVICE, notification)
        }
        startLoopIfNeeded()
        return START_STICKY
    }

    private fun startLoopIfNeeded() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                runCatching { tick() }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private suspend fun tick() {
        runCatching { ServiceLocator.settings.current().displayName }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { cachedTitle = it }
        maybeCatchUpSummaries()
        checkProactive()
    }

    /**
     * 「新的一天开始时，总结前一天」。
     *
     * 为什么不交给 WorkManager：在 MIUI/HyperOS 上它会被冻结（实测 dumpsys jobscheduler
     * 显示我们那两个任务处于 Frozen + thermal-restricted 状态）。前台服务已经在跑、
     * 又不会被冻结，所以这里才是可靠的触发点。每半小时最多查一次，查不到东西时
     * 只是一条本地 SQL，几乎不花钱。
     */
    private suspend fun maybeCatchUpSummaries() {
        val now = System.currentTimeMillis()
        if (now - lastSummaryCheckAt < SUMMARY_CHECK_INTERVAL_MS) return
        lastSummaryCheckAt = now
        runCatching { ServiceLocator.memory.catchUpMissingSummaries() }
    }

    private suspend fun checkProactive() {
        val engine = ProactiveEngine(
            messageDao = ServiceLocator.db.messageDao(),
            settings = ServiceLocator.settings,
            apiKeys = ServiceLocator.apiKeys,
            sense = ServiceLocator.usageStats,
            chat = ServiceLocator.chat,
            triggers = ServiceLocator.triggers,
        )
        when (val outcome = engine.checkOnce()) {
            is ProactiveEngine.Outcome.Spoke -> {
                val title = runCatching { ServiceLocator.settings.current().displayName }.getOrDefault("dream")
                Notifications.showHerMessage(this, outcome.text, outcome.reason.label, title)
            }

            else -> Unit
        }
    }

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 10 * 60_000L
        private const val SUMMARY_CHECK_INTERVAL_MS = 30 * 60_000L

        fun start(context: Context) {
            val intent = Intent(context, DreamForegroundService::class.java)
            // 后台启动前台服务在 Android 12+ 可能被拒（ForegroundServiceStartNotAllowed），
            // 所以这里必须吞掉异常：能被 Worker 兜底，绝不能因此崩溃。
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DreamForegroundService::class.java)) }
        }
    }
}

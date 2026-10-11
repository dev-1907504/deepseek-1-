package com.dreamgirl.app

import android.app.Application
import androidx.work.WorkManager
import com.dreamgirl.app.data.ServiceLocator
import com.dreamgirl.app.notify.Notifications
import com.dreamgirl.app.service.DreamForegroundService
import com.dreamgirl.app.work.AppScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 进程入口。做三件事：装依赖容器、建通知渠道、把后台任务登记上。
 * 都是廉价操作，不阻塞冷启动——首页要在她开口之前就出现。
 */
class DreamGirlApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        Notifications.ensureChannels(this)
        AppScheduler.scheduleAll(this)

        scope.launch {
            // Keystore 解密要碰磁盘和 TEE，别放在主线程上做（会拖慢冷启动、极端情况 ANR）
            runCatching { ServiceLocator.apiKeys.hasKey }
            // 她自己补记：昨天或更早聊过、但还没总结的日子。
            // 直接在进程里做，不走 WorkManager —— MIUI 会把后者冻住。
            runCatching { ServiceLocator.memory.catchUpMissingSummaries() }
            val enabled = runCatching { ServiceLocator.settings.current().proactiveEnabled }.getOrDefault(false)
            if (enabled) DreamForegroundService.start(this@DreamGirlApp)
        }
    }
}

package com.dreamgirl.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dreamgirl.app.data.ServiceLocator
import com.dreamgirl.app.domain.ProactiveEngine
import com.dreamgirl.app.notify.Notifications

/** 前台服务被系统杀掉时的兜底：WorkManager 会尽量把你叫醒。 */
class ProactiveWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!ServiceLocator.isInitialized) ServiceLocator.init(ctx)
        return runCatching {
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
                    Notifications.ensureChannels(ctx)
                    val title = runCatching { ServiceLocator.settings.current().displayName }
                        .getOrDefault("dream")
                    Notifications.showHerMessage(ctx, outcome.text, outcome.reason.label, title)
                }

                is ProactiveEngine.Outcome.Failed -> return Result.retry()
                else -> Unit
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }
}

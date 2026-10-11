package com.dreamgirl.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dreamgirl.app.data.DayClock
import com.dreamgirl.app.data.ServiceLocator
import com.dreamgirl.app.notify.Notifications

/**
 * 每天把「有对话但还没总结」的过去几天补上（方案 §6.4-4：次日首次打开 / 最后一条消息后 30 分钟）。
 * 失败不影响主流程，下次再试。
 */
class SummaryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!ServiceLocator.isInitialized) ServiceLocator.init(ctx)
        if (!ServiceLocator.apiKeys.hasKey) return Result.success()
        return runCatching {
            val todayDate = DayClock.today()
            val today = todayDate.toString()

            // 新的一天开始时，把前一天（以及更早漏掉的）整理成总结。
            // 不整理"今天"——今天还没过完，等她睡下、新的一天开始再记。
            val missing = ServiceLocator.db.daySummaryDao().datesMissingSummary(today)
            missing.takeLast(3).forEach { iso ->
                val date = runCatching { java.time.LocalDate.parse(iso) }.getOrNull() ?: return@forEach
                ServiceLocator.memory.summarizeDay(date)
            }

            // 90 天前的日总结压成月记，控制体积
            runCatching { ServiceLocator.memory.compressOldSummaries() }
            Notifications.ensureChannels(ctx)
            Result.success()
        }.getOrElse { Result.retry() }
    }
}

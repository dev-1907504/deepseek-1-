package com.dreamgirl.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 后台任务的登记处。
 *
 * 分工：
 *  - [ProactiveWorker] 15 分钟一次（WorkManager 的最小周期），是前台服务的兜底；
 *  - [SummaryWorker] 每天一次，把昨天漏掉的总结补上（方案 §6.4-4）。
 */
object AppScheduler {

    private const val WORK_PROACTIVE = "dreamgirl.proactive"
    private const val WORK_SUMMARY = "dreamgirl.summary"

    fun scheduleAll(context: Context) {
        scheduleProactive(context)
        scheduleSummary(context)
    }

    fun scheduleProactive(context: Context) {
        val request = PeriodicWorkRequestBuilder<ProactiveWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_PROACTIVE,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun scheduleSummary(context: Context) {
        val request = PeriodicWorkRequestBuilder<SummaryWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_SUMMARY,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** App 一起来就补漏（她自己做，不问用户）。 */
    fun catchUpNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SummaryWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "dreamgirl.summary.catchup",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_PROACTIVE)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_SUMMARY)
    }
}

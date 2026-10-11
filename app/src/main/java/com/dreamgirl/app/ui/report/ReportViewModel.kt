package com.dreamgirl.app.ui.report

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamgirl.app.data.AppDatabase
import com.dreamgirl.app.data.DayClock
import com.dreamgirl.app.data.SettingsStore
import com.dreamgirl.app.sense.UsageStatsCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ReportViewModel(
    private val usageStats: UsageStatsCollector,
    private val db: AppDatabase,
    private val settings: SettingsStore,
) : ViewModel() {

    data class Report(
        val date: LocalDate,
        val hasPermission: Boolean,
        val screenOnMinutes: Int,
        val continuousMinutes: Int,
        val foregroundLabel: String?,
        val topApps: List<Pair<String, Int>>,
        val sleepAt: String?,
        val messageCount: Int,
        val proactiveEnabled: Boolean,
        /** 方案 §7-6：界面上要有常驻的「正在观察」指示。 */
        val observing: Boolean,
    )

    var report by mutableStateOf<Report?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    init {
        refresh()
    }

    fun refresh(date: LocalDate = DayClock.today()) {
        viewModelScope.launch {
            busy = true
            val cfg = settings.current()
            val r = withContext(Dispatchers.IO) {
                val agg = usageStats.snapshot()
                val sleep = usageStats.sleepTime(date)
                Report(
                    date = date,
                    hasPermission = usageStats.hasPermission(),
                    screenOnMinutes = agg.screenOnMinutesToday,
                    continuousMinutes = agg.continuousUseMinutes,
                    foregroundLabel = agg.foregroundLabel,
                    topApps = usageStats.dailyReport(date),
                    sleepAt = sleep?.format(DateTimeFormatter.ofPattern("HH:mm")),
                    messageCount = db.messageDao().countByDate(date.toString()),
                    proactiveEnabled = cfg.proactiveEnabled,
                    observing = cfg.proactiveEnabled && cfg.usageStatsEnabled && usageStats.hasPermission(),
                )
            }
            report = r
            busy = false
        }
    }
}

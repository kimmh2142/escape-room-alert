package com.keyescape.notifier.monitoring

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class MonitoringWorker @AssistedInject constructor(
    @Assisted appContext: Context, @Assisted params: WorkerParameters,
    private val checkTargets: CheckMonitoringTargetsUseCase
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val summary = checkTargets.checkActive()
        return if (summary.failedGroupCount > 0) Result.retry() else Result.success()
    }
}

object MonitoringScheduler {
    private const val UNIQUE_WORK = "keyescape_availability_monitor"
    private const val CHECK_NOW_WORK = "keyescape_availability_check_now"
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<MonitoringWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Development-only manual trigger. Existing taps share one pending check. */
    fun checkNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<MonitoringWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork(CHECK_NOW_WORK, ExistingWorkPolicy.KEEP, request)
    }
}

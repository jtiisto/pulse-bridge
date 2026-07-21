package dev.jtiisto.pulsebridge.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.jtiisto.pulsebridge.core.common.DiagnosticLog
import dev.jtiisto.pulsebridge.core.network.ServerHealthMonitor
import io.ktor.client.plugins.ClientRequestException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val syncManager: SyncManager by inject()
    private val serverHealthMonitor: ServerHealthMonitor by inject()
    private val diagnosticLog: DiagnosticLog by inject()

    override suspend fun doWork(): Result {
        return try {
            val result = syncManager.sync()
            diagnosticLog.log(
                "sync",
                "ok: intervals=${result.totalAccepted}/${result.totalSent} " +
                    "acc=${result.accAccepted}/${result.accSent}",
            )
            serverHealthMonitor.reportSuccess()
            Result.success()
        } catch (e: ClientRequestException) {
            // 4xx: the server is reachable but rejected the request — retrying
            // the same payload can't succeed, so don't spin on a poison batch
            diagnosticLog.log("sync", "server rejected: HTTP ${e.response.status.value}")
            syncManager.updateSyncStatusOnError("Server rejected sync: HTTP ${e.response.status.value}")
            serverHealthMonitor.reportSuccess()
            Result.failure()
        } catch (e: Exception) {
            diagnosticLog.log("sync", "failed (attempt $runAttemptCount): ${e.message}")
            syncManager.updateSyncStatusOnError(e.message ?: "Sync failed")
            serverHealthMonitor.reportFailure()
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "pulse_bridge_periodic"
        private const val MAX_RETRIES = 5

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun enqueuePeriodicSync(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                15, TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS,
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun enqueueSyncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}

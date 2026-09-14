package com.umbra.app.data.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.umbra.app.UmbraApp
import com.umbra.app.data.diag.DiagLog
import java.util.concurrent.TimeUnit

/**
 * Довозит очередь отправки, когда сеть вернётся, даже если экран закрыт.
 * До 0.18.0 очередь жила только внутри процесса: сворачивание приложения в метро
 * означало, что сообщение дожидалось следующего ручного захода.
 */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? UmbraApp ?: return Result.success()
        val repo = app.container.chatRepository
        return try {
            repo.flushOutboxNow()
            Result.success()
        } catch (e: Exception) {
            DiagLog.log("outbox-worker", e)
            // Не теряем очередь: WorkManager повторит с растущей паузой.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "umbra-outbox"
        private const val MAX_ATTEMPTS = 8

        /**
         * Ставит одну работу на очередь. KEEP: повторные вызовы не плодят копий,
         * а уже запланированная попытка не сбрасывается.
         */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
            }
        }
    }
}

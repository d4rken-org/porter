package moe.shizuku.manager.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.model.PorterServiceVersion
import moe.shizuku.manager.starter.ServiceReplacement
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku

class ServiceUpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!eligible(inputData.getString(TARGET), PorterServiceVersion.installed.buildId,
                ShizukuSettings.getAutoUpdateService(), UserHandleCompat.myUserId())) return Result.success()
        val connected = withTimeoutOrNull(15_000) {
            ShizukuStateMachine.asFlow().first { it == ShizukuStateMachine.State.RUNNING && Shizuku.pingBinder() }
        }
        if (connected != null) ServiceReplacement.updateInBackground()
        else moe.shizuku.manager.utils.Logger.LOGGER.i("Service update skipped: no running service connected within 15 seconds")
        return Result.success()
    }

    companion object {
        private const val NAME = "porter_service_update"
        private const val TARGET = "target_build"

        internal fun eligible(target: String?, installed: String?, enabled: Boolean, userId: Int) =
            enabled && userId == 0 && target != null && target == installed

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<ServiceUpdateWorker>()
                .setInputData(workDataOf(TARGET to PorterServiceVersion.installed.buildId)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    }
}

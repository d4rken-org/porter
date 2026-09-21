package eu.darken.porter.manager.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.model.PorterServiceVersion
import eu.darken.porter.manager.starter.ServiceReplacement
import eu.darken.porter.manager.utils.PorterStateMachine
import eu.darken.porter.manager.utils.UserHandleCompat
import eu.darken.porter.sdk.Porter

class ServiceUpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!eligible(inputData.getString(TARGET), PorterServiceVersion.installed.buildId,
                PorterSettings.autoUpdateService, UserHandleCompat.myUserId())) return Result.success()
        val connected = withTimeoutOrNull(15_000) {
            PorterStateMachine.instance.asFlow().first { it == PorterStateMachine.State.RUNNING && Porter.connection.value?.isAlive == true }
        }
        if (connected != null) ServiceReplacement.get(applicationContext).updateInBackground()
        else eu.darken.porter.manager.utils.LOGGER.i("Service update skipped: no running service connected within 15 seconds")
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

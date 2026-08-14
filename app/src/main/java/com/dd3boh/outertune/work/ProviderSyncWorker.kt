package com.dd3boh.outertune.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dd3boh.outertune.providers.ProviderId
import com.dd3boh.outertune.providers.sync.SyncConflictPolicy
import com.dd3boh.outertune.providers.syncengine.ProviderSyncOutcome
import com.dd3boh.outertune.providers.syncengine.ProviderSyncRequest
import com.dd3boh.outertune.providers.syncengine.SyncTrigger
import kotlinx.coroutines.CancellationException

/** Network-constrained one-shot provider sync. It never promotes itself to foreground work. */
class ProviderSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        // Default-deny: raw/stale work without an explicit product enablement bit is a no-op.
        if (!inputData.getBoolean(KEY_SYNC_ENABLED, false)) {
            return Result.success(workDataOf(KEY_STATUS to STATUS_DISABLED))
        }

        val provider = inputData.enumValue<ProviderId>(KEY_PROVIDER)
            ?: return Result.failure(errorData("Invalid or missing provider"))
        val policy = inputData.enumValue<SyncConflictPolicy>(KEY_POLICY)
            ?: SyncConflictPolicy.ADD_ONLY_MERGE
        val trigger = inputData.enumValue<SyncTrigger>(KEY_TRIGGER)
            ?: SyncTrigger.RETRY
        val coordinator = ProviderSyncWorkerRuntime.coordinator()
            ?: return Result.retry()

        val outcome = try {
            coordinator.synchronize(
                ProviderSyncRequest(
                    provider = provider,
                    conflictPolicy = policy,
                    allowDeletions = inputData.getBoolean(KEY_ALLOW_DELETIONS, false),
                    syncLibrary = inputData.getBoolean(KEY_SYNC_LIBRARY, true),
                    syncPlaylists = inputData.getBoolean(KEY_SYNC_PLAYLISTS, true),
                    trigger = trigger,
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Result.retry()
        }
        val output = outcomeData(outcome)
        return when (outcome) {
            is ProviderSyncOutcome.Completed,
            is ProviderSyncOutcome.Unavailable -> Result.success(output)

            is ProviderSyncOutcome.Partial -> {
                if (outcome.retryable) Result.retry() else Result.success(output)
            }

            is ProviderSyncOutcome.Failed -> {
                if (outcome.retryable) Result.retry() else Result.failure(output)
            }
        }
    }

    private fun outcomeData(outcome: ProviderSyncOutcome): Data = workDataOf(
        KEY_STATUS to when (outcome) {
            is ProviderSyncOutcome.Completed -> STATUS_COMPLETED
            is ProviderSyncOutcome.Partial -> STATUS_PARTIAL
            is ProviderSyncOutcome.Unavailable -> STATUS_UNAVAILABLE
            is ProviderSyncOutcome.Failed -> STATUS_FAILED
        },
        KEY_RUN_ID to outcome.report.runId,
        KEY_TOTAL_COUNT to outcome.report.counts.total,
        KEY_APPLIED_LOCAL_COUNT to outcome.report.counts.appliedLocal,
        KEY_ENQUEUED_REMOTE_COUNT to outcome.report.counts.enqueuedRemote,
        KEY_MANUAL_REVIEW_COUNT to outcome.report.counts.manualReview,
        KEY_SUPPRESSED_COUNT to outcome.report.counts.suppressed,
    )

    private fun errorData(message: String): Data = workDataOf(
        KEY_STATUS to STATUS_FAILED,
        KEY_ERROR to message,
    )

    private inline fun <reified T : Enum<T>> Data.enumValue(key: String): T? =
        getString(key)?.let { value -> enumValues<T>().firstOrNull { it.name == value } }

    companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_POLICY = "policy"
        const val KEY_TRIGGER = "trigger"
        const val KEY_ALLOW_DELETIONS = "allow_deletions"
        const val KEY_SYNC_LIBRARY = "sync_library"
        const val KEY_SYNC_PLAYLISTS = "sync_playlists"
        const val KEY_SYNC_ENABLED = "sync_enabled"

        const val KEY_STATUS = "status"
        const val KEY_RUN_ID = "run_id"
        const val KEY_TOTAL_COUNT = "total_count"
        const val KEY_APPLIED_LOCAL_COUNT = "applied_local_count"
        const val KEY_ENQUEUED_REMOTE_COUNT = "enqueued_remote_count"
        const val KEY_MANUAL_REVIEW_COUNT = "manual_review_count"
        const val KEY_SUPPRESSED_COUNT = "suppressed_count"
        const val KEY_ERROR = "error"

        const val STATUS_DISABLED = "DISABLED"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_PARTIAL = "PARTIAL"
        const val STATUS_UNAVAILABLE = "UNAVAILABLE"
        const val STATUS_FAILED = "FAILED"
    }
}

package com.dd3boh.outertune.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.dd3boh.outertune.providers.ProviderId
import com.dd3boh.outertune.providers.sync.SyncConflictPolicy
import com.dd3boh.outertune.providers.syncengine.SyncTrigger
import java.util.concurrent.TimeUnit

object ProviderSyncScheduler {
    fun enqueueManual(
        context: Context,
        provider: ProviderId,
        conflictPolicy: SyncConflictPolicy = SyncConflictPolicy.ADD_ONLY_MERGE,
        allowDeletions: Boolean = false,
        syncLibrary: Boolean = true,
        syncPlaylists: Boolean = true,
    ): Operation = enqueueUnique(
        context = context,
        provider = provider,
        trigger = SyncTrigger.MANUAL,
        policy = ExistingWorkPolicy.REPLACE,
        conflictPolicy = conflictPolicy,
        allowDeletions = allowDeletions,
        syncLibrary = syncLibrary,
        syncPlaylists = syncPlaylists,
        syncEnabled = true,
    )

    /**
     * Startup sync is opt-in. Callers must pass persisted product enablement explicitly;
     * a blocked or not-yet-configured provider must not create background work.
     */
    fun enqueueStartup(
        context: Context,
        provider: ProviderId,
        syncEnabled: Boolean,
    ): Operation? {
        if (!syncEnabled) return null
        return enqueueUnique(
            context = context,
            provider = provider,
            trigger = SyncTrigger.STARTUP,
            policy = ExistingWorkPolicy.KEEP,
            conflictPolicy = SyncConflictPolicy.ADD_ONLY_MERGE,
            allowDeletions = false,
            syncLibrary = true,
            syncPlaylists = true,
            syncEnabled = true,
        )
    }

    private fun enqueueUnique(
        context: Context,
        provider: ProviderId,
        trigger: SyncTrigger,
        policy: ExistingWorkPolicy,
        conflictPolicy: SyncConflictPolicy,
        allowDeletions: Boolean,
        syncLibrary: Boolean,
        syncPlaylists: Boolean,
        syncEnabled: Boolean,
    ): Operation = WorkManager.getInstance(context).enqueueUniqueWork(
        uniqueWorkName(provider, trigger),
        policy,
        request(
            provider = provider,
            trigger = trigger,
            conflictPolicy = conflictPolicy,
            allowDeletions = allowDeletions,
            syncLibrary = syncLibrary,
            syncPlaylists = syncPlaylists,
            syncEnabled = syncEnabled,
        ),
    )

    private fun request(
        provider: ProviderId,
        trigger: SyncTrigger,
        conflictPolicy: SyncConflictPolicy,
        allowDeletions: Boolean,
        syncLibrary: Boolean,
        syncPlaylists: Boolean,
        syncEnabled: Boolean,
    ): OneTimeWorkRequest = OneTimeWorkRequestBuilder<ProviderSyncWorker>()
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .setInputData(
            workDataOf(
                ProviderSyncWorker.KEY_PROVIDER to provider.name,
                ProviderSyncWorker.KEY_POLICY to conflictPolicy.name,
                ProviderSyncWorker.KEY_TRIGGER to trigger.name,
                ProviderSyncWorker.KEY_ALLOW_DELETIONS to allowDeletions,
                ProviderSyncWorker.KEY_SYNC_LIBRARY to syncLibrary,
                ProviderSyncWorker.KEY_SYNC_PLAYLISTS to syncPlaylists,
                ProviderSyncWorker.KEY_SYNC_ENABLED to syncEnabled,
            )
        )
        .addTag(TAG_PROVIDER_SYNC)
        .addTag("$TAG_PROVIDER_SYNC:${provider.name}")
        .build()

    private fun uniqueWorkName(provider: ProviderId, trigger: SyncTrigger): String =
        "provider-sync:${provider.name}:${trigger.name}"

    private const val TAG_PROVIDER_SYNC = "provider-sync"
}

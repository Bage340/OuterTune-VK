package com.dd3boh.outertune.work

import com.dd3boh.outertune.providers.syncengine.SyncCoordinator

/** Process-local bridge for plain WorkManager workers when Hilt-Work is not installed. */
object ProviderSyncWorkerRuntime {
    @Volatile
    private var coordinator: SyncCoordinator? = null

    fun install(coordinator: SyncCoordinator) {
        this.coordinator = coordinator
    }

    internal fun coordinator(): SyncCoordinator? = coordinator
}

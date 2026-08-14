package com.dd3boh.outertune.providers.syncengine

internal data class AreaSyncResult(
    val actions: List<ProviderSyncAction> = emptyList(),
    val diagnostics: List<SyncDiagnostic> = emptyList(),
    val pagesRead: Int = 0,
    val isComplete: Boolean = true,
    val scannedCount: Int = 0,
)

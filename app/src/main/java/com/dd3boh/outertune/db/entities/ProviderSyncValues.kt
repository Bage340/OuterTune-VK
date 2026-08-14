package com.dd3boh.outertune.db.entities

/**
 * Values persisted by the provider sync schema.
 *
 * These are deliberately strings instead of provider-layer enums. Database rows must remain
 * readable if an enum is renamed or moved, and migrations cannot depend on application code.
 */
object StoredProviderId {
    const val LOCAL = "LOCAL"
    const val YOUTUBE = "YOUTUBE"
    const val VK = "VK"
}

object RemoteMappingSyncState {
    const val LINKED = "LINKED"
    const val PENDING = "PENDING"
    const val STALE = "STALE"
    const val CONFLICT = "CONFLICT"
    const val UNAVAILABLE = "UNAVAILABLE"
}

object PlaylistSyncMode {
    const val ADD_ONLY = "ADD_ONLY"
    const val MERGE = "MERGE"
    const val REMOTE_WINS = "REMOTE_WINS"
    const val LOCAL_WINS = "LOCAL_WINS"
    const val MANUAL = "MANUAL"
}

object SyncOperationState {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val SUCCEEDED = "SUCCEEDED"
    const val RETRYABLE_FAILURE = "RETRYABLE_FAILURE"
    const val PERMANENT_FAILURE = "PERMANENT_FAILURE"
}

object SyncRunState {
    const val RUNNING = "RUNNING"
    const val SUCCEEDED = "SUCCEEDED"
    const val PARTIAL = "PARTIAL"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
    const val UNAVAILABLE = "UNAVAILABLE"
}

object SyncHealthState {
    const val HEALTHY = "HEALTHY"
    const val DEGRADED = "DEGRADED"
    const val ERROR = "ERROR"
    const val AUTH_REQUIRED = "AUTH_REQUIRED"
    const val UNAVAILABLE = "UNAVAILABLE"
}

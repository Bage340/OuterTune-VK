package com.dd3boh.outertune.providers.syncengine

import com.dd3boh.outertune.db.entities.ProviderSyncHealth
import com.dd3boh.outertune.db.entities.SyncHealthState
import com.dd3boh.outertune.db.entities.SyncRun
import com.dd3boh.outertune.db.entities.SyncRunState
import com.dd3boh.outertune.providers.CapabilityAvailability
import com.dd3boh.outertune.providers.CapabilityUnavailableReasonCode
import com.dd3boh.outertune.providers.ProviderCapability
import com.dd3boh.outertune.providers.ProviderId
import com.dd3boh.outertune.providers.sync.ReconciliationActionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class SyncCoordinator(
    private val store: ProviderSyncStore,
    private val providers: MusicProviderResolver,
    private val clock: ProviderSyncClock = ProviderSyncClock.SYSTEM,
    private val runIdGenerator: SyncRunIdGenerator = SyncRunIdGenerator.UUID,
) {
    private val operationFactory = SyncOperationFactory(clock)
    private val identityResolver = TrackIdentityResolver(store)
    private val libraryReconciler = LibrarySyncReconciler(
        store = store,
        identityResolver = identityResolver,
        operationFactory = operationFactory,
        clock = clock,
    )
    private val playlistReconciler = PlaylistSyncReconciler(
        store = store,
        identityResolver = identityResolver,
        operationFactory = operationFactory,
        clock = clock,
    )
    private val operationProcessor = SyncOperationProcessor(store = store, clock = clock)

    suspend fun preview(request: ProviderSyncRequest): ProviderSyncOutcome = synchronize(
        request.copy(dryRun = true, trigger = SyncTrigger.PREVIEW)
    )

    suspend fun synchronize(request: ProviderSyncRequest): ProviderSyncOutcome =
        mutexFor(request.provider).withLock {
            synchronizeLocked(request)
        }

    private suspend fun synchronizeLocked(request: ProviderSyncRequest): ProviderSyncOutcome {
        val startedAt = clock.now()
        val initialRun = SyncRun(
            id = runIdGenerator.create(request.provider),
            provider = request.provider.name,
            trigger = request.trigger.name,
            state = SyncRunState.RUNNING,
            health = SyncHealthState.HEALTHY,
            startedAt = startedAt,
        )
        store.upsertRun(initialRun)

        val provider = providers.resolve(request.provider)
        if (provider == null) {
            val reason = CapabilityUnavailableReasonCode.PROVIDER_DISABLED
            val diagnostic = SyncDiagnostic(
                code = "PROVIDER_NOT_REGISTERED",
                message = "Provider ${request.provider.name} is not registered",
            )
            val report = finishRun(
                request = request,
                initialRun = initialRun,
                areaResults = emptyList(),
                diagnostics = listOf(diagnostic),
                runState = SyncRunState.UNAVAILABLE,
                healthState = SyncHealthState.UNAVAILABLE,
            )
            return ProviderSyncOutcome.Unavailable(report, reason)
        }

        val requestedAreas = buildList {
            if (request.syncLibrary) add(ProviderCapability.LIBRARY_READ)
            if (request.syncPlaylists) add(ProviderCapability.PLAYLIST_READ)
        }
        val unavailable = requestedAreas.mapNotNull { capability ->
            val availability = provider.availability(capability)
            (availability as? CapabilityAvailability.Unavailable)?.let { capability to it }
        }
        val availableAreaCount = requestedAreas.size - unavailable.size
        if (availableAreaCount == 0) {
            val first = unavailable.first().second
            val diagnostics = unavailable.map { (capability, availability) ->
                SyncDiagnostic(
                    code = "${capability.name}_UNAVAILABLE_${availability.reasonCode.name}",
                    message = "${capability.name} is unavailable: ${availability.reasonCode.name}",
                )
            }
            val health = if (first.reasonCode == CapabilityUnavailableReasonCode.AUTH_REQUIRED) {
                SyncHealthState.AUTH_REQUIRED
            } else {
                SyncHealthState.UNAVAILABLE
            }
            val report = finishRun(
                request = request,
                initialRun = initialRun,
                areaResults = emptyList(),
                diagnostics = diagnostics,
                runState = SyncRunState.UNAVAILABLE,
                healthState = health,
            )
            return ProviderSyncOutcome.Unavailable(report, first.reasonCode)
        }

        return try {
            val diagnostics = unavailable.mapTo(mutableListOf()) { (capability, availability) ->
                SyncDiagnostic(
                    code = "${capability.name}_UNAVAILABLE_${availability.reasonCode.name}",
                    message = "${capability.name} is unavailable: ${availability.reasonCode.name}",
                )
            }
            val results = mutableListOf<AreaSyncResult>()
            if (request.syncLibrary && provider.supports(ProviderCapability.LIBRARY_READ)) {
                results += libraryReconciler.reconcile(provider, request)
            }
            if (request.syncPlaylists && provider.supports(ProviderCapability.PLAYLIST_READ)) {
                results += playlistReconciler.reconcile(provider, request)
            }
            diagnostics += results.flatMap(AreaSyncResult::diagnostics)

            val outbox = if (!request.dryRun) {
                operationProcessor.process(provider, request.maxOutboxOperations)
            } else {
                OutboxProcessReport(0, 0, 0, 0)
            }
            if (outbox.retryableFailures > 0) {
                diagnostics += SyncDiagnostic(
                    code = "OUTBOX_RETRYABLE_FAILURE",
                    message = "${outbox.retryableFailures} remote operations scheduled for retry",
                    retryable = true,
                )
            }
            if (outbox.permanentFailures > 0) {
                diagnostics += SyncDiagnostic(
                    code = "OUTBOX_PERMANENT_FAILURE",
                    message = "${outbox.permanentFailures} remote operations failed permanently",
                )
            }

            val unresolvedActionCount = results
                .flatMap(AreaSyncResult::actions)
                .count { action ->
                    action.state == SyncActionState.MANUAL_REVIEW ||
                        action.state.name.startsWith("SUPPRESSED_")
                }
            if (unresolvedActionCount > 0) {
                diagnostics += SyncDiagnostic(
                    code = "RECONCILIATION_REQUIRES_ATTENTION",
                    message = "$unresolvedActionCount reconciliation actions require attention",
                )
            }
            val incomplete = unavailable.isNotEmpty() || results.any { !it.isComplete }
            val degraded = incomplete || unresolvedActionCount > 0 ||
                outbox.retryableFailures > 0 || outbox.permanentFailures > 0
            val report = finishRun(
                request = request,
                initialRun = initialRun,
                areaResults = results,
                diagnostics = diagnostics,
                runState = if (degraded) SyncRunState.PARTIAL else SyncRunState.SUCCEEDED,
                healthState = if (degraded) SyncHealthState.DEGRADED else SyncHealthState.HEALTHY,
            )
            if (degraded) {
                ProviderSyncOutcome.Partial(
                    report = report,
                    retryable = diagnostics.any(SyncDiagnostic::retryable),
                )
            } else {
                ProviderSyncOutcome.Completed(report)
            }
        } catch (cancelled: CancellationException) {
            try {
                withContext(NonCancellable) {
                    finishRun(
                        request = request,
                        initialRun = initialRun,
                        areaResults = emptyList(),
                        diagnostics = listOf(
                            SyncDiagnostic(
                                "SYNC_CANCELLED",
                                "Sync coroutine was cancelled",
                                retryable = true,
                            )
                        ),
                        runState = SyncRunState.CANCELLED,
                        healthState = SyncHealthState.DEGRADED,
                    )
                }
            } catch (_: Exception) {
                // Preserve structured coroutine cancellation even if diagnostics persistence fails.
            }
            throw cancelled
        } catch (error: Exception) {
            val diagnostic = SyncDiagnostic(
                code = "SYNC_UNEXPECTED_FAILURE",
                message = "Sync failed with ${error.safeClassName()}",
                retryable = true,
            )
            val report = finishRun(
                request = request,
                initialRun = initialRun,
                areaResults = emptyList(),
                diagnostics = listOf(diagnostic),
                runState = SyncRunState.FAILED,
                healthState = SyncHealthState.ERROR,
            )
            ProviderSyncOutcome.Failed(report, retryable = true)
        }
    }

    private suspend fun finishRun(
        request: ProviderSyncRequest,
        initialRun: SyncRun,
        areaResults: List<AreaSyncResult>,
        diagnostics: List<SyncDiagnostic>,
        runState: String,
        healthState: String,
    ): ProviderSyncReport {
        val actions = areaResults.flatMap(AreaSyncResult::actions)
        val counts = ProviderSyncCounts.from(actions)
        val finishedAt = clock.now()
        val complete = areaResults.all(AreaSyncResult::isComplete) &&
            diagnostics.none { it.code.contains("UNAVAILABLE") }
        val finalRun = initialRun.copy(
            state = runState,
            health = healthState,
            finishedAt = finishedAt,
            pageCount = areaResults.sumOf(AreaSyncResult::pagesRead),
            scannedCount = areaResults.sumOf(AreaSyncResult::scannedCount),
            insertedCount = actions.count {
                it.state == SyncActionState.APPLIED_LOCAL &&
                    it.type == ReconciliationActionType.ADD_TO_LOCAL
            },
            updatedCount = actions.count {
                it.state == SyncActionState.APPLIED_LOCAL &&
                    it.type == ReconciliationActionType.UPDATE_LOCAL
            },
            deletedCount = actions.count {
                it.state == SyncActionState.APPLIED_LOCAL &&
                    it.type == ReconciliationActionType.DELETE_LOCAL
            },
            conflictCount = counts.manualReview + counts.suppressed,
            errorCount = diagnostics.size,
            lastError = diagnostics.lastOrNull()?.code,
        )
        store.upsertRun(finalRun)

        val previousHealth = store.currentHealth(request.provider)
        val succeeded = runState == SyncRunState.SUCCEEDED
        store.upsertHealth(
            ProviderSyncHealth(
                provider = request.provider.name,
                state = healthState,
                lastRunId = initialRun.id,
                lastStartedAt = initialRun.startedAt,
                lastCompletedAt = finishedAt,
                lastSuccessfulAt = if (succeeded) {
                    finishedAt
                } else {
                    previousHealth?.lastSuccessfulAt
                },
                consecutiveFailures = if (succeeded) {
                    0
                } else {
                    (previousHealth?.consecutiveFailures ?: 0) + 1
                },
                pendingOperationCount = store.pendingOperationCount(request.provider),
                lastError = diagnostics.lastOrNull()?.code,
                updatedAt = finishedAt,
            )
        )

        return ProviderSyncReport(
            runId = initialRun.id,
            provider = request.provider,
            policy = request.conflictPolicy,
            dryRun = request.dryRun,
            allowDeletions = request.allowDeletions,
            actions = actions,
            counts = counts,
            diagnostics = diagnostics,
            pagesRead = areaResults.sumOf(AreaSyncResult::pagesRead),
            snapshotsComplete = complete,
        )
    }

    private companion object {
        val providerMutexes = ConcurrentHashMap<ProviderId, Mutex>()

        fun mutexFor(provider: ProviderId): Mutex =
            providerMutexes.computeIfAbsent(provider) { Mutex() }
    }

    private fun Throwable.safeClassName(): String =
        this::class.java.simpleName.takeIf(String::isNotBlank) ?: "Exception"
}

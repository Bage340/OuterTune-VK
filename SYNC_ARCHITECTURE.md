# Provider sync architecture

## Safety invariants

Provider sync is separate from the legacy YouTube `SyncUtils`. It is serialized per provider,
transactional at Room boundaries, resumable across process death, and capability-gated before any
network operation. It never interprets an empty or incomplete remote page as deletion.

Default first reconciliation is **ADD_ONLY**:

- preserve every existing local track, playlist, and order;
- add/link identities that are unambiguous;
- do not delete locally or remotely;
- surface medium/low matches and conflicts for review;
- require an explicit opt-in policy before destructive propagation.

## Pipeline

```text
schedule/manual request
        |
        v
capability + auth + connectivity gate
        |
        v
acquire provider mutex / record SyncRun
        |
        v
read all remote pages -> validate cursor/completeness
        |
        v
normalize + match + pure reconciliation plan
        |
        v
single Room transaction per committed batch
        |
        v
lease and dispatch durable outbox mutations
        |
        v
health/counters/sanitized diagnostics + next retry
```

## Pagination and partial failure

Remote reads are bounded and cursor-based. Pages are accumulated with loop detection before a
complete snapshot is reconciled; run status and sanitized diagnostics are persisted. Duplicate
remote IDs are de-duplicated by database constraints. Cursor
loops, malformed pages, cancellation, rate limits, auth loss, and transient failures produce a
partial/retryable run, not inferred deletions. Remote absence is considered only after a complete,
stable snapshot and the selected policy permits it.

## Durable mutation outbox

`sync_operation` represents remote writes independently from local commits. Every operation has a
deterministic SHA-256 idempotency key over provider, entity, operation, target, and canonical
payload. Workers claim bounded batches with an expiring lease:

- `PENDING` or due `RETRYABLE_FAILURE` -> `RUNNING` under a unique lease owner;
- success -> `SUCCEEDED`;
- transient/network/rate-limit failure -> bounded exponential backoff (30 seconds to 6 hours);
- auth/capability loss -> health state plus retry after the prerequisite changes;
- validation/unsupported operation -> `PERMANENT_FAILURE` with sanitized error category.

An expired lease is recoverable after worker/process death. Duplicate scheduling is harmless because
the idempotency key is unique and state transitions are conditional. No raw token, request header,
stream URL, email, phone, or untrusted response body is written to diagnostics.

## Reconciliation and conflicts

Reconciliation is pure: inputs are local snapshot, complete remote snapshot, mappings, tombstones,
and a declared policy; output is a plan. Deterministic operation order makes retries and tests
repeatable.

Supported policy vocabulary:

- `ADD_ONLY`: additions/links only; safe default.
- `MERGE`: non-conflicting changes in both directions, with explicit deletions still gated.
- `REMOTE_WINS`: remote state may replace local state only after user selection and complete read.
- `LOCAL_WINS`: local state may schedule remote mutations only when write capabilities exist.
- `MANUAL`: record conflicts without applying a winner.

Conflicts include divergent playlist metadata/order, ambiguous matches, incompatible variants,
concurrent remote/local edits, and unsupported write capabilities. A blocked provider produces an
`UNAVAILABLE` run and no queued mutation.

## Tombstones and deletion propagation

A tombstone is an intentional, timestamped deletion marker for a provider/entity/remote ID. It
prevents retries, stale pages, and first sync from resurrecting a deletion. Deletion propagation
requires all of the following:

1. a complete remote snapshot;
2. an explicit non-default user policy;
3. corresponding write capability;
4. no unresolved conflict or newer opposite-side edit;
5. durable outbox/idempotency protection.

Acknowledged tombstones may be compacted later under a retention policy. An unacknowledged tombstone
is never removed merely because a run succeeded.

## Scheduling

WorkManager implementations exist for background execution with network constraints, unique
provider work, default-deny enablement, and backoff. Manual and startup scheduler entry points use
the same coordinator and never reconcile on the UI thread. The current production UI does not
enqueue VK work because every official music capability is unavailable and the corresponding
controls are disabled. A future adapter must provide persisted opt-in and auth-aware capability
state before calling these entry points. Unsupported work exits successfully with an unavailable
health state; it does not spin or retry an impossible API.

## Observability

`sync_run` and `provider_sync_health` retain bounded, sanitized operational facts: provider, state,
cursor, counts, duration, retry category, and last success/failure time. Diagnostics distinguish
auth, capability, rate limit, connectivity, malformed data, conflict, and internal failure without
persisting secrets or personal response payloads.

These facts are a persistence/API seam, not a finished user-facing diagnostics screen. The current
settings explain the unavailable official capability and keep sync controls disabled; a partner
adapter still needs last-run/retry/conflict presentation.

## Testing strategy

- pure JVM tests: normalization, confidence thresholds, variant guards, idempotency, policy plans,
  tombstones, duplicate pages, partial failure, lease/retry transitions;
- Room migration test: real exported schema 20 -> 21 with data/FK/index verification;
- fake provider tests: pagination, capability changes, typed failures, and repeatable reconciliation;
- Android integration: worker constraints, process-safe resume, and UI states when an emulator/device
  is available.

## Status

- **IMPLEMENTED**: schema, DAO, pure planner primitives, idempotency, provider coordinator/outbox
  boundary, default-deny WorkManager gate, dry-run API and tests.
- **IMPLEMENTED BUT REQUIRES VK CREDENTIAL**: identity prerequisite for a future authorized adapter.
- **BLOCKED BY VK API ACCESS**: dispatch of real VK Music reads/writes.
- **OPTIONAL FUTURE WORK**: partner-specific mutation adapter, persisted per-playlist opt-in,
  startup/manual UI wiring, previous-known-state tombstone producers, provider-spanning mutation
  serialization, server-side webhook/delta support, and user-facing diagnostics/conflict handling.

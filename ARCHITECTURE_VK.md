# VK/provider architecture

## Design boundary

The fork adds a provider layer around new cross-service behavior without rewriting OuterTune's
stable local and YouTube playback stack. `:innertube` remains the existing YouTube transport.
VK-specific code cannot call private music endpoints and cannot make the player treat an unavailable
VK item as a playable YouTube item without an explicit mapping.

```text
Compose screens / ViewModels
          |
          v
provider contracts + capability checks
     |                         |
     v                         v
YouTube adapter/fallback    VK provider boundary
                               |
                       official VK ID only
                               |
                VK Music methods: unavailable

provider-independent matcher / reconciliation
          |
          v
Room v21 mapping + membership + outbox + tombstone tables
```

## Provider contracts

`MusicProvider` exposes auth, track search, library, playlist, upload, and stream operations. Every
operation returns `ProviderResult`; expected auth/network/capability failures are data, while
coroutine cancellation remains cancellation. `ProviderId` is stable and exhaustive for `LOCAL`,
`YOUTUBE`, and `VK`.

`CapabilitySet` publishes an explicit state for every `ProviderCapability`. An omitted declaration
becomes `NOT_DECLARED`, not an accidental success. The production VK provider reports identity auth
separately and marks music operations `OFFICIAL_API_ACCESS_UNAVAILABLE`. The fake VK provider allows
the complete boundary, pagination, matching, and sync logic to be exercised without real VK access.

## Authentication

`OfficialVkAuthManager` is the only official SDK integration point. It handles sign-in, SDK-managed
session restore, refresh, account loading, logout/revocation, cancellation, state mismatch, missing
browser/redirect, network, expired token, and generic service failure states. Credentials enter via
Gradle properties, environment variables, or ignored `local.properties`; a clean checkout compiles
with auth disabled. Tokens are not copied into Room, DataStore, logs, diagnostics, or app models.

Identity state is intentionally separate from `UnsupportedVkMusicProvider`: signing in with VK ID
does not make a music capability available. A future documented partner adapter must bridge its own
authorized music session/capabilities to `MusicProvider`; the placeholder must not infer them from
VK ID alone.

See `VK_SETUP.md` and `docs/VK_API_CAPABILITIES.md` for configuration and the verified public API
boundary.

## Data model

Room database version 21 adds provider-neutral tables instead of VK columns on `SongEntity`:

- `remote_track_mapping`: `(provider, remoteTrackId)` to local song identity, metadata snapshot,
  match confidence, revision/etag, timestamps, and sync state.
- `remote_playlist_mapping`: remote/local playlist identity, sync mode, cursor/revision, and state.
- `provider_playlist_item`: provider membership/order with a stable remote membership ID.
- `sync_operation`: durable mutation outbox with idempotency key, attempts, retry time, and lease.
- `sync_tombstone`: explicit deletions so partial/empty reads cannot resurrect or destroy data.
- `sync_run`: run status, cursor, counters, timing, and sanitized diagnostics.
- `provider_sync_health`: last success/failure and provider health state.

The `20 -> 21` migration backfills existing non-local songs and remote playlists as YouTube mappings
while preserving local IDs and playlist order. Foreign keys and uniqueness constraints prevent
duplicate remote identities.

## Matching

`TrackMatcher` is deterministic and side-effect free. Ranking uses, in order: an existing mapping,
exact provider key, normalized artist/title, duration tolerance, and album. Unicode NFKC,
case/whitespace/punctuation normalization, `feat.`/`ft.` cleanup, bracket decoration cleanup, and a
secondary Russian `ё`/`е` heuristic improve recall. Remix, live, sped-up, slowed, instrumental,
remaster, and cover variants are guarded against unsafe merging. Only high-confidence results can be
auto-linked; medium/low results remain reviewable.

## Search and playback

Online search asks VK first only when `SEARCH_TRACK` is currently available. Otherwise it records a
typed unavailability state and immediately uses the existing YouTube search. Results carry provider
identity and render a compact source badge. The matcher and mapping schema can persist an explicit
high-confidence equivalence, but the production player is not yet a provider dispatcher: existing
YouTube/local playback remains unchanged, while VK playback stays unavailable. No VK item is made
playable by fabricating a stream URL or silently treating its ID as a YouTube ID.

## UI principles

The existing Material 3 hierarchy remains intact. New provider state is inline, not modal:

- VK account state lives in Settings with sign-in/out/retry actions and clear disabled reasons.
- Search rows carry accessible text+icon source badges rather than color-only meaning.
- unavailable, loading, empty, partial, and retryable states are explicit;
- operations are launched from ViewModels/workers so Compose is never blocked by reconciliation;
- controls prevent duplicate submission and keep touch targets/semantics accessible.

The launcher keeps the familiar audio/play shape but uses an indigo palette and the visible name
`OuterTune VK`; it does not use the VK logo or imply endorsement.

## Status

- **IMPLEMENTED**: provider contracts/capabilities/results, fake provider, matcher, Room schema,
  YouTube fallback boundary, source representation, sync primitives.
- **IMPLEMENTED BUT REQUIRES VK CREDENTIAL**: official VK ID identity lifecycle and account UI.
- **BLOCKED BY VK API ACCESS**: real VK music search/library/playlist/upload/stream/playback.
- **OPTIONAL FUTURE WORK**: a documented partner adapter, provider-aware playback dispatcher,
  per-playlist controls, diagnostics/conflict UI, and menu/details mapping actions added behind the
  existing boundaries.

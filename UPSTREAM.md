# Upstream provenance and update policy

## Immutable starting point

- Upstream repository: `https://github.com/OuterTune/OuterTune.git`
- Upstream tag: `v0.10.1`
- Verified peeled commit: `dd399658b39dc44a6a82c46ee295e77a5a0e3d22`
- Fork integration branch: `main`
- Fork application ID: `com.bage340.outertunevk`
- Kotlin namespace intentionally retained: `com.dd3boh.outertune`

Keeping the namespace avoids a high-risk mechanical rewrite. Runtime identities are isolated by
`applicationId`, debug suffix, manifest placeholders, FileProvider authority, shortcut targets, and
VK ID redirect configuration.

## Remotes

```text
origin    https://github.com/Bage340/OuterTune-VK.git
upstream  https://github.com/OuterTune/OuterTune.git
```

Confirm these rather than trusting documentation:

```bash
git remote -v
git rev-parse v0.10.1^{}
git merge-base --is-ancestor dd399658b39dc44a6a82c46ee295e77a5a0e3d22 HEAD
```

## Safe update procedure

1. Fetch, but do not merge directly into `main`:

   ```bash
   git fetch --tags upstream
   git switch -c codex/upstream-<version> main
   git merge --no-commit --no-ff upstream/<branch>
   ```

2. Resolve conflicts manually. Never accept an entire side for these protected areas:

   - `app/src/main/java/com/dd3boh/outertune/providers/`
   - `app/src/main/java/com/dd3boh/outertune/auth/vk/`
   - `app/src/main/java/com/dd3boh/outertune/sync/`
   - provider mapping/outbox/tombstone entities and `ProviderSyncDao`
   - `MusicDatabase.kt` version, entity list, and migrations
   - `OnlineSearchViewModel` and search result UI
   - VK account/settings UI and its dedicated string resources
   - application IDs, VK manifest placeholders, shortcuts, launcher identity, CI, and docs

3. Reconcile Room schema evolution additively. If upstream also uses version 21, allocate a new
   version and compose both migrations; never replace this fork's `20 -> 21` migration.

4. Audit upstream assumptions that equate every remote track or `browseId` with YouTube. New code
   must route through provider IDs/capabilities and leave `:innertube` unchanged unless an upstream
   fix requires it.

5. Run unit tests, migration tests, lint, and `assembleCoreDebug`. Inspect the generated APK package
   and merged manifest. Compare local/YouTube smoke behavior against the preceding `main` build.

6. Commit the reviewed merge, push the feature branch, and integrate it into `main` only after all
   required checks pass. Do not create a release or tag as part of a routine upstream update.

## Pinned NewPipe dependency resolution

OuterTune `v0.10.1` abbreviates its NewPipeExtractor revision as `d59dc21`. JitPack no longer serves
that seven-character coordinate even though it reports the historical build as successful. This fork
uses the unambiguous nine-character coordinate `d59dc216f`, which resolves to the same full commit
`d59dc216f49290b2c6c8cf532378e2ee42b32d4a`; no extractor source is upgraded or substituted.

## Conflict rules

- Prefer a small adapter over copying upstream implementation into provider code.
- Keep provider database tables additive and keyed by `(provider, remote ID)`.
- Do not restore the upstream application ID in any build type.
- Do not add undocumented VK endpoints while resolving a feature conflict.
- Preserve the official capability gate even if upstream introduces similarly named generic APIs.
- Review privacy/export/backup behavior whenever auth or manifest components change.

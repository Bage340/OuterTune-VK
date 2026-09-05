# OuterTune 0.10.15 custom revisions

The application ID remains `com.dd3boh.outertune`. Revision v91 uses
`versionName = 0.10.15` and `versionCode = 91`.

## Source history

- Base: AsterTune `b7f58abfd3e77b7548bee353761828cfd213a0c2`.
- `0.10.15-v90`: materialized source patches from the workflow in
  `072d8b119e6ebf210870b2447fb419a210c14a6e`.
- v91 is developed directly from that v90 source, not the abandoned playlist-context experiment.
- `legacy-outertune-vk` preserves the former repository main; `legacy-v90-workflow`
  preserves the original reconstruction recipe. Local refs are not evidence of remote publication.

## Build and signing

Use JDK 21 and an Android SDK with the required platforms/build tools:

```text
./gradlew :app:testCoreDebugUnitTest :app:lintCoreUserdebug :app:assembleCoreUserdebug -DskipFormatKtlint
```

The universal APK is under `app/build/outputs/apk/core/userdebug/`.
CI builds from this repository and uploads an explicitly unsigned APK.

An unsigned APK is not an installable update. Sign only with the existing private
OuterTune key, outside Git. Never generate a replacement key. Verify package,
version, alignment, APK SHA-256, and this certificate SHA-256 before publishing:

```text
98de410a5f16c5743ca3885d4ded7850fab73730a99bfd67f5912a5d91f6b736
```

## v91 release notes

- Repair local-library playback after a scan cleared `localPath`: check both DB/queue paths,
  then the exact audio path retained as song artwork by the local scanners. Persist a readable
  recovered path without changing IDs, likes, playlist membership, or a newer scanner update.
- Preserve the last known path when a scan disables a local song. Generated local IDs
  (`LS` plus eight letters) never enter YouTube resolution, even with missing queue metadata.
- Report missing/inaccessible local files as a local storage error instead of “video unavailable”.
- Prefer physical downloads and fresh database paths when playlist queue metadata is stale.
- Keep the download file index updated after save/delete/rescan, using exact media IDs.
- Require completed download state before treating internal download cache as offline audio.
- Store stream URL, client, headers, and safe expiry together; invalidate rejected URLs.
- Try alternative audio formats and clients, returning only a validated stream.
- Preserve sequential bulk downloads, nullable-length support, and the bundled player-config snapshot.
- Include source IDs, local path state, and per-client outcomes when resolution fails.
  Diagnostic reports can contain private track/playlist IDs and paths; review them before public sharing.

## Phone acceptance checks

Test the same downloaded song from an online playlist, library, and queue with
airplane mode enabled. Exercise shuffle, next/previous, repeat, seeking, and app
restart. Then test remote playback, a complete playlist download, an individual
download, and retry of a failed download. Existing downloads and user data must remain.

For the local-library repair, first retry the reported “Lost Frequency” entry without
rescanning, including airplane mode. If its original audio path is still readable, it
should recover automatically. Also check next/previous and playback after a restart.
An actually moved/deleted file or revoked media permission cannot be repaired from an ID
alone; the local error includes the recorded paths for diagnosis. No title-based file search
is used, and no permission is requested automatically during playback.

If a Source error persists, collect its extended diagnostics. Equal IDs with a
missing local path indicate a storage/index problem; differing IDs require further
canonical-ID investigation. No title-based alias matching or database migration is introduced.

Keep revision/tag/release `0.10.15-v91` until the user confirms these phone checks.
Build and unit tests alone do not establish that intermittent live YouTube failures are resolved.

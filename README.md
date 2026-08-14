# OuterTune VK

OuterTune VK is an independent, GPL-3.0 derivative of
[OuterTune](https://github.com/OuterTune/OuterTune), based exactly on release
[`v0.10.1`](https://github.com/OuterTune/OuterTune/releases/tag/v0.10.1) at commit
`dd399658b39dc44a6a82c46ee295e77a5a0e3d22`.

It preserves OuterTune's local-player and YouTube Music functionality while adding a provider-neutral
foundation for VK ID, catalog search, identity mapping, and safe playlist reconciliation. It uses the
distinct application ID `com.bage340.outertunevk`, so a release build can coexist with upstream
OuterTune on the same Android device. Debug builds use `com.bage340.outertunevk.debug`.

> [!IMPORTANT]
> This project does not claim that VK ID grants access to VK Music. As verified on 2026-08-14,
> ordinary third-party apps have no documented public VK API for music search, libraries,
> playlists, uploads, stream URLs, playback, or downloads. Those capabilities remain visibly
> unavailable; no private endpoint, scraped request, borrowed credential, or reverse-engineered
> official-client protocol is used.

## Delivery status

| Area | Status |
|---|---|
| Existing local and YouTube playback | **IMPLEMENTED** |
| Distinct package, name, launcher palette, and shortcuts | **IMPLEMENTED** |
| Provider contracts, capability model, fake provider, matcher | **IMPLEMENTED** |
| Room v20 -> v21 provider mappings, outbox, tombstones, run history | **IMPLEMENTED** |
| VK ID sign-in/account/logout through the official SDK | **IMPLEMENTED BUT REQUIRES VK CREDENTIAL** |
| VK music catalog/library/playlist/stream operations | **BLOCKED BY VK API ACCESS** |
| Partner VK Music adapter | **OPTIONAL FUTURE WORK** |

See the complete evidence-based matrix in
[`docs/VK_API_CAPABILITIES.md`](docs/VK_API_CAPABILITIES.md).

## Architecture and operation

- [`VK_SETUP.md`](VK_SETUP.md) - register a VK ID app and supply local/CI credentials safely.
- [`ARCHITECTURE_VK.md`](ARCHITECTURE_VK.md) - provider boundaries, search fallback, data model, and UI.
- [`SYNC_ARCHITECTURE.md`](SYNC_ARCHITECTURE.md) - idempotent outbox, reconciliation, safety policy, and jobs.
- [`UPSTREAM.md`](UPSTREAM.md) - provenance and guarded upstream update procedure.
- [`docs/VK_API_CAPABILITIES.md`](docs/VK_API_CAPABILITIES.md) - official VK API gate and sources.
- [`docs/TOOLING_AUDIT.md`](docs/TOOLING_AUDIT.md) - tools used and reproducible verification scope.

## Build

Requirements match upstream `v0.10.1`: JDK 21 to run Gradle, Android SDK 36, Build Tools 36.0.0,
NDK `29.0.13113456`, CMake `3.31.6`, and recursive Git submodules. Kotlin/JVM compilation targets
Java 17.

```bash
git submodule update --init --recursive
./gradlew assembleCoreDebug lintCoreDebug testCoreDebugUnitTest
```

A clean checkout builds without VK credentials with `VK_ID_CONFIGURED=false`. To exercise VK ID,
follow [`VK_SETUP.md`](VK_SETUP.md). Never commit credentials, tokens, `.env`, `local.properties`, or
secret property files.

## Upstream features retained

- Local MP3, OGG, FLAC, and other Android-supported audio playback.
- YouTube Music browsing, playback, downloads, account integration, and lyrics.
- Multiple queues, audio effects, Android Auto, and Material 3 UI.
- Android 8 (API 26) and newer are supported by the upstream project; the technical minimum remains
  API 24 without an upstream support promise for Android 7.x.

## Attribution and license

OuterTune VK is not an official OuterTune build. It retains the original project history and the
[`GPL-3.0`](LICENSE) license. Thanks to the
[OuterTune contributors](https://github.com/OuterTune/OuterTune/graphs/contributors),
[InnerTune](https://github.com/z-huang/InnerTune),
[Gramophone](https://github.com/FoedusProgramme/Gramophone), and all bundled-library authors.

This project is not affiliated with, funded, authorized, endorsed by, or otherwise associated with
VK, VK Music, YouTube, Google LLC, or their affiliates. Product names and trademarks belong to
their respective owners. The official VK ID SDK's source-code license does not grant catalog,
streaming, offline-copy, redistribution, subscription, or trademark rights.

The optional `tracking-tracer` runtime bridge shipped by VK ID is replaced with VK ID's official
`tracking-noop` module: its transitive Tracer components use a separate non-FOSS license, while the
no-op implementation satisfies the SDK runtime contract without VK/OK crash or performance
telemetry.

# Official VK capability boundary

Verified from official VK sources on **2026-08-14**. Public VK API schema version: **5.199**.
VK ID Android SDK version used by this project: **2.7.2**, released **2026-07-20**.

This document is a hard implementation boundary. DTOs, old scope constants, behavior observed in
the official consumer app, or undocumented endpoints are not evidence of public API access.

## Status legend

- **IMPLEMENTED** — works locally without a VK credential or partner grant.
- **IMPLEMENTED BUT REQUIRES VK CREDENTIAL** — implemented through documented VK ID APIs and gated
  until the developer supplies credentials for their own registered Android application.
- **BLOCKED BY VK API ACCESS** — no documented public method is available to an ordinary third-party
  application; this project must not emulate it through private endpoints.
- **OPTIONAL FUTURE WORK** — may be considered only after VK supplies a written partner contract,
  allowlist, and current official documentation for the exact application.

## Capability matrix

| Capability | Status | Public official surface | No credentials / no partner allowlist |
|---|---|---|---|
| Build and run the existing local/YouTube player | **IMPLEMENTED** | Existing OuterTune code | Yes |
| Build with VK ID dependency present but disabled | **IMPLEMENTED** | Local build gate | Yes; `VK_ID_CONFIGURED=false` |
| VK ID sign-in with PKCE/state validation | **IMPLEMENTED BUT REQUIRES VK CREDENTIAL** | VK ID SDK | No |
| Restore SDK-managed local auth state | **IMPLEMENTED BUT REQUIRES VK CREDENTIAL** | `VKID.accessToken` | No |
| Refresh access/refresh tokens | **IMPLEMENTED BUT REQUIRES VK CREDENTIAL** | `VKID.refreshToken` | No |
| Refresh account information | **IMPLEMENTED BUT REQUIRES VK CREDENTIAL** | `VKID.getUserData` | No |
| VK ID logout/revocation | **IMPLEMENTED BUT REQUIRES VK CREDENTIAL** | `VKID.logout` | No |
| Basic name/avatar/user ID | **IMPLEMENTED BUT REQUIRES VK CREDENTIAL** | Default personal-info scope | No |
| Email/phone after consent | **IMPLEMENTED BUT REQUIRES VK CREDENTIAL** | `email`, `phone` scopes | No |
| Search VK Music catalog | **BLOCKED BY VK API ACCESS** | No public `audio.search` | No |
| Read saved tracks/library | **BLOCKED BY VK API ACCESS** | No public `audio.get` | No |
| Read/create/edit/delete VK playlists | **BLOCKED BY VK API ACCESS** | No public audio playlist methods | No |
| Add/remove a VK track | **BLOCKED BY VK API ACCESS** | No public `audio.add/delete` | No |
| Upload/save audio | **BLOCKED BY VK API ACCESS** | No public upload/save methods | No |
| Obtain stream URLs or control VK playback | **BLOCKED BY VK API ACCESS** | No public playback API | No |
| Download/offline-cache VK audio | **BLOCKED BY VK API ACCESS** | No public API; platform rules restrict downloading | No |
| VK-provided partner music integration | **OPTIONAL FUTURE WORK** | Only if VK supplies it directly | No |

Ordinary VK ID credentials enable identity only. They do not turn any blocked music row into an
available capability.

## Why music operations are blocked

The [official VK API schema](https://github.com/VKCOM/vk-api-schema) describes the public VK API.
Its current [audio namespace](https://github.com/VKCOM/vk-api-schema/tree/master/audio) contains
object models but no `methods.json` or `responses.json`. In the official Android VK API SDK,
[the audio package](https://github.com/VKCOM/vk-android-sdk/tree/master/api/src/main/java/com/vk/sdk/api/audio)
contains DTOs but no generated `AudioService`; public namespaces such as
[users](https://github.com/VKCOM/vk-android-sdk/tree/master/api/src/main/java/com/vk/sdk/api/users)
do have generated services.

The current [official public method catalog](https://dev.vk.com/ru/method) has no documented
`audio.get`, `audio.search`, `audio.add`, `audio.delete`, `audio.save`, audio upload, playlist, or
playback methods. A legacy
[`VKScope.AUDIO`](https://github.com/VKCOM/vk-android-sdk/blob/master/core/src/main/java/com/vk/api/sdk/auth/VKScope.kt)
constant is not a grant of server capability and is not used by this project.

No private endpoint, scraped web response, intercepted official-client request, borrowed client ID,
or reverse-engineered playback URL is an acceptable substitute. If VK later offers a partner API,
it must be integrated behind the existing provider boundary only after verifying the supplied
contract, scopes, methods, content rights, and revocation behavior.

## Authentication boundary

The official VK ID scope list currently contains only:

- `vkid.personal_info` (default personal information);
- `email`;
- `phone`.

See the [official scope documentation](https://id.vk.ru/about/business/go/docs/ru/vkid/latest/vk-id/connection/work-with-user-info/scopes).
There is no public audio or VK Music scope.

`OfficialVkAuthManager` uses only documented SDK types. It distinguishes canceled authorization,
OAuth state mismatch, redirect/no-browser failures, network failures, expired refresh tokens,
not-authenticated/revoked-or-signed-out state, and generic service failures. The SDK does not expose
a stable public error code that lets every generic failed API call be truthfully classified as
revocation versus outage, so the integration does not guess.

## Security, privacy, and licensing

The [VK platform rules](https://dev.vk.com/rules), edition dated **2026-04-02**, require integrations
to use officially published methods. They also restrict downloading VK content, including audio,
outside functionality supplied by VK, and require respecting user-data and third-party rights.

- Never commit a client ID/key pair, access token, refresh token, ID token, service key, or signing
  secret.
- Never use another application's credential or official-client token.
- The Android protected key is extractable from an APK. It cannot be the sole trust anchor for a
  backend.
- The custom URI redirect receiver must remain paired with the exact registered client ID, package,
  and signing SHA-1. PKCE and state validation are mandatory.
- Tokens remain solely in the official SDK's encrypted storage. Do not duplicate them in DataStore,
  Room, logs, analytics, crash reports, clipboard, diagnostics, or exported backups.
- Account email/phone are personal data: request only required scopes, display consent accurately,
  and delete derived data when no longer needed.

The [VK ID SDK is MIT-licensed](https://github.com/VKCOM/vkid-android-sdk/blob/2.7.2/LICENSE). That
license covers the SDK source, not the VK Music catalog, streaming, offline copies, distribution
rights, subscriptions, or VK trademarks.

The SDK's optional `com.vk.id:tracking-tracer` runtime bridge is deliberately replaced by the
official `com.vk.id:tracking-noop` module. The Tracer bridge's transitive `ru.ok.tracer` artifacts
use a separate non-FOSS license; the no-op implementation preserves VK ID's runtime contract
without enabling VK/OK telemetry.

## Primary sources

- [VK ID Android SDK 2.7.2](https://github.com/VKCOM/vkid-android-sdk/tree/2.7.2)
- [VK ID Android installation guide](https://id.vk.ru/about/business/go/docs/ru/vkid/latest/vk-id/connection/start-integration/android/install)
- [VK ID Android OAuth flow](https://id.vk.ru/about/business/go/docs/ru/vkid/latest/vk-id/connection/start-integration/how-auth-works/auth-flow-android)
- [VK ID application registration](https://id.vk.ru/about/business/go/docs/ru/vkid/latest/vk-id/connection/create-application)
- [VK public API method catalog](https://dev.vk.com/ru/method)
- [VK public API schema](https://github.com/VKCOM/vk-api-schema)
- [VK platform rules](https://dev.vk.com/rules)

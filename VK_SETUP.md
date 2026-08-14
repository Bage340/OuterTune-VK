# VK ID setup

Checked against the official VK ID Android SDK **2.7.2** on **2026-08-14**.
Version 2.7.2 was released on 2026-07-20.

This project integrates official VK ID authentication only. It does not use private VK endpoints,
does not reuse credentials from another Android application, and does not claim VK Music API
access.

## Build-safe default

A clean checkout has no credentials and must still resolve and compile:

- `BuildConfig.VK_ID_CONFIGURED` is `false`;
- safe manifest values are `VKIDClientID=0`, `VKIDClientSecret=not-configured`,
  `VKIDRedirectHost=vk.ru`, and `VKIDRedirectScheme=vk0`;
- `OfficialVkAuthManager` reports `VkAuthState.NotConfigured` and does not initialize or call VK ID.

Both a positive numeric client ID and a non-empty protected client key are required before the
build is marked configured. A partial or invalid pair is ignored and produces a Gradle warning
without printing either value.

## Create and register your own VK ID application

1. Create an application using VK's
   [official application setup](https://id.vk.ru/about/business/go/docs/ru/vkid/latest/vk-id/connection/create-application).
2. Obtain that application's ID (`client_id`) and protected key (used by the Android SDK as
   `client_secret`). Never copy these values from OuterTune, the official VK client, or another
   application.
3. Register every Android build you will actually authorize:
   - release/userdebug package: `com.bage340.outertunevk`;
   - debug package: `com.bage340.outertunevk.debug`.
4. Register the SHA-1 fingerprint of the signing certificate for each package. Inspect local
   variants with `./gradlew :app:signingReport` (`.\gradlew.bat :app:signingReport` on Windows).
5. Register the exact callback derived from the application ID:

   ```text
   vk{clientId}://vk.ru/blank.html
   ```

   For client ID `123456`, the exact URI is `vk123456://vk.ru/blank.html`. `vk.ru`, not the legacy
   `vk.com` host, is required by current SDK documentation.

If debug and release use different VK applications, build each one with the matching credential
pair. Package, certificate SHA-1, client ID, protected key, and redirect must all describe the same
registered VK application configuration.

## Supply credentials locally

Credential precedence is: Gradle property, environment variable, then ignored root
`local.properties`.

| Value | Gradle property | Environment variable | `local.properties` |
|---|---|---|---|
| Client ID | `vk.id.clientId` | `VK_ID_CLIENT_ID` | `VKIDClientID` |
| Protected key | `vk.id.clientSecret` | `VK_ID_CLIENT_SECRET` | `VKIDClientSecret` |

Recommended developer setup in the already ignored root `local.properties`:

```properties
VKIDClientID=123456
VKIDClientSecret=replace_with_your_own_protected_key
```

Temporary PowerShell environment setup:

```powershell
$env:VK_ID_CLIENT_ID = "123456"
$env:VK_ID_CLIENT_SECRET = "replace_with_your_own_protected_key"
.\gradlew.bat :app:assembleCoreDebug
```

The Gradle properties are supported for protected automation, but do not pass the protected key on
an interactive command line: command-line values can be exposed through process listings and shell
history. Prefer ignored `local.properties` for development and protected environment variables for
CI. Do not add credentials to tracked `gradle.properties`, a workflow file, source code, resources,
or documentation.

## Application usage

`OfficialVkAuthManager` initializes `VKID` only in a configured build. Construct one
application-scoped instance and observe its `StateFlow`:

```kotlin
val vkAuthManager: VkAuthManager = OfficialVkAuthManager(applicationContext)

vkAuthManager.signIn(
    lifecycleOwner = this,
    requestedScopes = setOf(VkAuthScope.EMAIL),
)

lifecycleScope.launch {
    vkAuthManager.refreshSession()
    vkAuthManager.refreshAccount()
    vkAuthManager.signOut()
}
```

`restoreSession()` is deliberately local-only: `SignedIn` means the SDK has a cached, unexpired
access token. Use `refreshSession()` or `refreshAccount()` when server validation is required; only
a network operation can reveal a token revoked before its local expiry.

An empty requested-scope set uses VK ID's default personal-information scope. The only scopes
exposed by this integration are `vkid.personal_info`, `email`, and `phone`, matching the
[current official scope list](https://id.vk.ru/about/business/go/docs/ru/vkid/latest/vk-id/connection/work-with-user-info/scopes).
There is no audio/music scope.

The manager exposes sanitized account information and error categories. It never exposes or logs
an access token, refresh token, or ID token. The SDK stores its token pair in its own encrypted
storage; do not copy those values into OuterTune DataStore, databases, diagnostics, analytics, or
backups.

## Error behavior

- canceled flow, no browser, redirect failure, and OAuth state mismatch remain distinct states;
- `IOException`-based SDK failures are classified as network failures and are retryable;
- typed `RefreshTokenExpired` and `NotAuthenticated` failures require a new sign-in;
- `getUserData` is allowed to perform the SDK's documented internal access-token refresh;
- other API failures remain a generic service failure because SDK 2.7.2 does not publicly expose
  enough stable detail to label every rejection as revocation, rate limiting, or outage.

## Security notes

- The protected key is embedded in the APK manifest because the official SDK requires it. An APK
  cannot keep this value confidential; package/SHA-1 registration and PKCE are still required.
- Never treat the Android protected key as a backend high-trust secret or authenticate a backend
  request using that value alone.
- The SDK performs Authorization Code + PKCE and state validation. Do not bypass its public flow or
  weaken redirect validation.
- The SDK contributes an exported redirect receiver for the custom URI scheme. The host/scheme are
  generated by the official manifest-placeholders plugin and the manager remains disabled when the
  real configuration is absent.
- The application still permits user-data backup, but both cloud backup and device transfer
  explicitly exclude the SDK-owned `vkid_encrypted_shared_prefs.xml` token/session store. The
  integration does not duplicate tokens into any other backupable app storage.
- If a credential ever enters Git history, rotate it in VK ID. Removing it only from the latest
  commit is insufficient.

## Build wiring

- SDK: `com.vk.id:vkid:2.7.2`
- Manifest plugin: `vkid.manifest.placeholders:1.1.0`
- SDK repository: `https://artifactory-external.vkpartner.ru/artifactory/vkid-sdk-android/`
- Plugin repository: `https://artifactory-external.vkpartner.ru/artifactory/maven/`
- Captcha transitive repository:
  `https://artifactory-external.vkpartner.ru/artifactory/vk-id-captcha/android/`

Useful checks:

```powershell
.\gradlew.bat :app:processCoreDebugMainManifest
.\gradlew.bat :app:compileCoreDebugKotlin
.\gradlew.bat :app:assembleCoreDebug
```

Inspect the merged manifest rather than manually declaring SDK activities in the app manifest.

## Official sources

- [VK ID Android installation](https://id.vk.ru/about/business/go/docs/ru/vkid/latest/vk-id/connection/start-integration/android/install)
- [Android authorization flow](https://id.vk.ru/about/business/go/docs/ru/vkid/latest/vk-id/connection/start-integration/how-auth-works/auth-flow-android)
- [Android authorization setup](https://id.vk.ru/about/business/go/docs/ru/vkid/latest/vk-id/connection/setting-up-auth/setup-android)
- [VK ID API endpoints](https://id.vk.ru/about/business/go/docs/ru/vkid/latest/vk-id/connection/api-description)
- [VK ID Android SDK source](https://github.com/VKCOM/vkid-android-sdk/tree/2.7.2)
- [VK ID Android SDK 2.7.2 release](https://github.com/VKCOM/vkid-android-sdk/releases/tag/2.7.2)

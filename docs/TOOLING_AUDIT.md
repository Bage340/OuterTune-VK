# Tooling and verification audit

This file records reproducible tool choices, not credentials or machine-specific secrets.

## Used

- Git and GitHub CLI: verified tag/commit provenance, preserved full history, configured `origin` and
  `upstream`, created the public repository, and pushed the untouched baseline.
- Official-source web research: checked VK ID SDK/setup/scopes, public VK method/schema surfaces,
  VK platform rules, and the exact OuterTune release.
- `ui-ux-pro-max`: generated an Android music-player design system and UX guidance before UI work.
  The result favored retaining upstream Material 3, compact text+icon provider badges, explicit
  unavailable/loading/retry states, accessible semantics, and non-blocking background work.
- PowerShell, `rg`, Gradle wrapper, Temurin JDK, Android command-line tools, SDK/NDK/CMake, Room/KSP,
  Android lint, unit tests, and APK inspection: source and build verification.
- Parallel code-review agents: upstream architecture/Room, provider/search, and official VK ID were
  implemented and reviewed in separated file scopes before integration.

## Considered but not used

- Image generation: unnecessary; the launcher retains the GPL-covered upstream vector geometry with
  a distinct indigo palette and no third-party logo.
- Browser/device UI automation: used only if an Android emulator/device becomes available; it is not
  a substitute for Compose/unit/migration/build checks.
- Security/offensive skills: unrelated to this authorized application integration; ordinary static
  secret/export/backup review is sufficient.
- Additional SaaS plugins: no project data lives in those services and installing them would not
  improve the requested source, build, test, or GitHub delivery.

## Reproduction notes

The workspace path contains Cyrillic characters on Windows. Android Gradle Plugin may require the
quoted property `-Pandroid.overridePathCheck=true`, or an ASCII-only worktree. Local SDK and VK
credential paths remain ignored. CI uses a clean Linux checkout with recursive submodules and no
production VK secret.

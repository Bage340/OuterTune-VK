# OuterTune

[![OuterTune app icon](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/outertune.webp)](https://github.com/yuuichi-s/OuterTune/blob/dev/assets/outertune.webp)


[![Latest release](https://img.shields.io/github/v/release/yuuichi-s/OuterTune?include_prereleases)](https://github.com/yuuichi-s/OuterTune/releases)
[![License](https://img.shields.io/github/license/yuuichi-s/OuterTune)](https://www.gnu.org/licenses/gpl-3.0)

[English](README.md) | [日本語](README_ja.md)

A Material 3 YouTube Music client & local music player for Android

> [!NOTE]
> This is a maintenance fork based on [OuterTune/OuterTune](https://github.com/OuterTune/OuterTune).
>
> Since upstream development of the YouTube Music features has stopped, this fork continues fixing issues and improving the app so it remains usable today.
>
> - No distribution channel is available at this time, but one may be provided in the future.
>
> If you would like to use it, you can build the app yourself. For most people, we recommend the `core` build:
>
> ```bash
> # core debug build
> ./gradlew assembleCoreDebug
> ```
>
> For step-by-step instructions, see [CONTRIBUTING.md](https://github.com/yuuichi-s/OuterTune/blob/dev/CONTRIBUTING.md).

## What This Fork Improves

This fork builds on [OuterTune/OuterTune](https://github.com/OuterTune/OuterTune) with a focus on YouTube Music playback stability, lyrics, navigation, and local music playback.

- Fixes YouTube Music playback and display issues
  - Fixed albums with missing tracks, crashes while opening playlists, and failed search result parsing
  - Fixed the "Source error 2004" issue that could block YouTube Music playback
  - Improved YouTube Music thumbnail resolution
  - Fixed a crash that could occur when opening playlists or albums while their data was being updated

- Improves lyrics
  - Uses LrcLib and caption tracks to improve lyrics matching and loading speed
  - Adds a lyrics toggle button to the now-playing action bar

- Improves app navigation
  - Adjusted bottom navigation so tab switching and re-tapping the active tab behave more naturally
  - Preserves the search bar state for each route
  - Fixed the Folders screen search bar restoration, default sorting, and list refresh behavior

- Improves local music playback
  - Improved tag reading for local music files
  - Improved local song linking and gapless playback
  - Fixed the album song count shown on album screens

- Improves display and settings
  - Restored the tablet UI
  - Fixed overlapping player UI and made dialogs scrollable
  - Automatically detects the system contrast setting on Android 14 and later
  - Added custom accent colors
  - Added selectable slider styles, with an animated squiggly style as the default
  - Added a "keep audio focus" player setting
  - Added a home screen grid showing your recent YouTube Music activity when signed in

- Updates internal libraries and build tooling
  - Updated Kotlin, KSP, NewPipeExtractor, Ktor, Android Gradle Plugin, Gradle, and related tooling

## Features

OuterTune is a supercharged fork of [InnerTune](https://github.com/z-huang/InnerTune). This app is both a local media player, and a YouTube Music client.

- YouTube Music client features
  * Song downloading (offline playback)
  * Seamless playback: no ads & background playback
  * Account synchronization
    + Full playlist sync from the app to the remote account is temporarily unavailable
- Local audio file playback (ex. MP3, OGG, FLAC, etc.)
  * Play local and YouTube Music songs at the same time
  * Uses a custom tag extractor instead of MediaStore's broken metadata extractor! (e.g tags delimited with \ now show up properly)
- Sleek Material3 design
- Multiple queues
- Synchronized lyrics, and support for word by word/Karaoke lyrics formats (e.g LRC, TTML)
- Audio normalization, tempo/pitch adjustment, and various other audio effects
- Android Auto support
- Support for Android 8 (Oreo) and higher

> [!NOTE]
> Android 8 (Oreo) and higher is supported. While the app may work on Android 7.x (Nougat), we do not officially support this version

## Screenshots

[![Main player interface](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/main-interface.jpg)](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/main-interface.jpg)

[![Player interface](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/player.jpg)](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/player.jpg)

[![Sync with YouTube Music](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/ytm-sync.jpg)](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/ytm-sync.jpg)

[Full image gallery](https://github.com/yuuichi-s/OuterTune/tree/dev/assets/gallery)

> [!WARNING]
> If you're in a region where YouTube Music is not supported, you won't be able to use this app ***unless*** you have a proxy or VPN to connect to a YTM supported region.

## Building & Contributing

Just wish to build the app yourself, please see the [building and contribution notes](CONTRIBUTING.md).

### Submitting Translations

We use Weblate to translate OuterTune. For more details or to submit translations, visit our [Weblate page](https://hosted.weblate.org/projects/yuuichi-s-outertune/).

[![Translation status](https://hosted.weblate.org/widget/yuuichi-s-outertune/multi-auto.svg)](https://hosted.weblate.org/projects/yuuichi-s-outertune/)

Thank you very much for helping to make OuterTune accessible to many people worldwide.

## Help & Support

- For bugs **specific to this fork**, please open an [Issue in this repository](https://github.com/yuuichi-s/OuterTune/issues).

## Attribution

Thanks to all our contributors! Check them out [here](https://github.com/OuterTune/OuterTune/graphs/contributors)

[z-huang/InnerTune](https://github.com/z-huang/InnerTune) for providing an awesome base for this fork, none of this
would have been possible without it.

[Musicolet](https://play.google.com/store/apps/details?id=in.krosbits.musicolet) for inspiration of a local music player
experience done right.

[Gramophone](https://github.com/FoedusProgramme/Gramophone) for emotional support, and a legendary lyrics parser

## Disclaimer

This project and its contents are not affiliated with, funded, authorized, endorsed by, or in any
way associated with YouTube, Google LLC or any of its affiliates and subsidiaries.

Any trademark, service mark, trade name, or other intellectual property rights used in this project
are owned by the respective owners.

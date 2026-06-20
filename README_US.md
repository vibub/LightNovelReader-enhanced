[简体中文](README.md) | [繁體中文](README_TW.md) | **English** | [Русский](README_RU.md)

<div>
    <h1>LightNovelReader Enhanced</h1>
    <a><img alt="Android" src="https://img.shields.io/badge/Android-3DDC84?logo=android&logoColor=white&style=for-the-badge"/></a>
    <a><img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-0095D5.svg?logo=kotlin&logoColor=white&style=for-the-badge"/></a>
    <a><img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white&style=for-the-badge"></a>
    <a href="http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=P__gXIArh5UDBsEq7ttd4WhIYnNh3y1t&authKey=GAsRKEZ%2FwHpzRv19hNJsDnknOc86lYzNIHMPy2Jxt3S3U8f90qestOd760IAj%2F3l&noverify=0&group_code=867785526"><img alt="QQ Group" src="https://img.shields.io/badge/QQ讨论群-867785526-brightgreen.svg?logoColor=white&style=for-the-badge"></a>
    <a href="https://discord.gg/pnf4ABmDJt"><img alt="Discord" src="https://img.shields.io/badge/Discord-JOIN-4285F4.svg?logo=discord&logoColor=white&style=for-the-badge"></a>
    <a href="https://t.me/lightnoble"><img alt="Telegram" src="https://img.shields.io/badge/Telegram-JOIN-188FCA.svg?logo=telegram&logoColor=white&style=for-the-badge"></a>
    <p>An enhanced fork of the LightNovelReader refactored version, built with Jetpack Compose</p>
    <img src="assets/header.png" alt="drawing"/>
</div>

## Introduction

LightNovelReader Enhanced is an enhanced fork of [LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader) <sup>*Refactored Version*</sup>. This repository is maintained by `vibub/LightNovelReader-enhanced` on the `refactoring` branch. It keeps the upstream Kotlin + Jetpack Compose app, offline caching, bookshelves, multiple data sources, plugin support, and EPUB export, while continuously merging upstream changes.

The main goal of this branch is to preserve the upstream experience while improving the Linovelib/Bilinovel data source, reading progress/bookmark sync, chapter parsing stability, and the GitHub Actions build/update experience. Its application ID is `indi.dmzz_yyhyy.lightnovelreader_enhanced`, so it can be installed side by side with the official upstream package.

## Relationship with upstream

- Upstream repository: [dmzz-yyhyy/LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader)
- Enhanced fork repository: [vibub/LightNovelReader-enhanced](https://github.com/vibub/LightNovelReader-enhanced)
- The long-term maintenance branch is still `refactoring`, and this branch will continue to absorb updates from upstream `refactoring`.
- Common features, the plugin API, and community resources generally follow upstream; this repository prioritizes enhanced-fork issues around builds, updates, Linovelib/Bilinovel, and local changes.

## Features

- Fully refactored version (see the [upstream pre-refactoring branch](https://github.com/dmzz-yyhyy/LightNovelReader/tree/master))
- Modern UI with Jetpack Compose, compatible with Android 7.0 and above
- Caching - support for caching book content and offline-first reading
- Explore - discover new books, recommendation lists, tag categories, keyword search...
- Multi-source support - easily switch between data sources, including manga. Data is independent between sources
- Bookshelf - bookshelf management with custom shelves, favorites, and update notifications
- EPUB export functionality for your favorite novels
- Plugin system - support for custom data sources and plugin extensions

## Enhanced fork highlights

- Linovelib/Bilinovel data source improvements: account login, Cookie persistence, website login/search flow, bookshelf sync, and chapter bookmark sync.
- Independent local storage for Bilinovel/Linovelib chapter bookmarks: no longer mixed with local reading records, with bookmark entry points shown in book details, the table of contents, and the reader.
- WebView-assisted sync: injects saved Cookies, attempts to locate and sync website-side chapter bookmarks automatically, and still supports manual handling when needed.
- Chapter parsing and reader stability: improves mobile/desktop URL handling, multi-page chapter parsing, illustration/paragraph spacing, continuous-scroll cross-chapter loading, and image jump issues.
- Request and rate-limit handling: optimizes Linovelib request frequency, reduces the chance of triggering Cloudflare rate limits, and provides clearer error paths when search/parsing fails.
- Updates and CI: the in-app update source has been switched to this enhanced repository, with support for checking Release and GitHub Actions artifacts, plus CI build changelog display.
- Package separation: the enhanced version uses `indi.dmzz_yyhyy.lightnovelreader_enhanced` as its applicationId to avoid confusion with the upstream package.

## Plugin Development and Custom Data Sources

You can add custom data sources and plugins to LightNovelReader. This enhanced fork still follows the upstream plugin API and documentation.

The following are links to relevant resources:

- [Example Plugin](https://github.com/dmzz-yyhyy/LightNovelReaderPlguin-Template)
- [Development Guide](https://lnr.nariko.org/plugin-dev/)
- [LNR API KDoc](https://api-doc.lnr.nariko.org/)

Developers are welcome to contribute!

## Download

For the enhanced version, prefer downloads from this repository:

- [GitHub Releases](https://github.com/vibub/LightNovelReader-enhanced/releases/latest): release APKs.
- [ReleaseApkBuild Actions](https://github.com/vibub/LightNovelReader-enhanced/actions/workflows/marge.yml): latest CI artifacts for trying the newest fixes; CI builds have not gone through full release testing.

> The `indi.dmzz_yyhyy.lightnovelreader` package on F-Droid belongs to the upstream project, not this enhanced fork. If you want the official upstream version, use [LightNovelReader Releases](https://github.com/dmzz-yyhyy/LightNovelReader/releases/latest) or [F-Droid](https://f-droid.org/packages/indi.dmzz_yyhyy.lightnovelreader).

## Support

- For enhanced-fork bugs or feature requests, please submit them in [this repository's Issues](https://github.com/vibub/LightNovelReader-enhanced/issues/new/choose).
- For common upstream features, community discussion, or the plugin ecosystem, you can also refer to the [upstream repository](https://github.com/dmzz-yyhyy/LightNovelReader) and the community channels below.
  - Join the QQ discussion group: `867785526` | [**Invitation Link**](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=P__gXIArh5UDBsEq7ttd4WhIYnNh3y1t&authKey=GAsRKEZ%2FwHpzRv19hNJsDnknOc86lYzNIHMPy2Jxt3S3U8f90qestOd760IAj%2F3l&noverify=0&group_code=867785526)
  - Join the Discord server: [**Invitation Link**](https://discord.gg/pnf4ABmDJt)
  - Join the Telegram group: [**Invitation Link**](https://t.me/lightnoble)

## Screenshots

|                             |
|-----------------------------|
| ![image](assets/light1.png) |
| ![image](assets/light2.png) |
| ![image](assets/light3.png) |

### About EpubLib

To handle EPUB export, the upstream project created a dedicated EPUB module; this fork continues to use and maintain it. If you're interested, check it out [**here**](https://github.com/vibub/LightNovelReader-enhanced/blob/refactoring/epub.md).

## Contributing

Contributions to this enhanced fork or upstream LightNovelReader are welcome! Here's how you can contribute to this repository:

### Getting Started

1. Fork this repository.
2. Clone your fork: `git clone https://github.com/your-username/LightNovelReader-enhanced.git`
3. Create a new branch for your changes: `git checkout -b feature/your-feature-name`
4. Make your changes and test them.
5. Commit your changes following the commit guidelines below.
6. Push to your fork: `git push origin feature/your-feature-name`
7. Open a Pull Request to this repository's `refactoring` branch.

If your change is a common feature or plugin API improvement, consider contributing it upstream as well.

### Commit Guidelines

- Keep commits atomic and descriptive.
- If your change affects the version, update `versionCode` / `versionName` in `app/build.gradle.kts`.
- For changes involving Linovelib/Bilinovel, reader bookmarks, Room migrations, update checks, or CI, please add or run the relevant tests when possible.

## Support the upstream project

[![Support Us on Aifadian](https://img.shields.io/badge/❤%20Support%20Us-ifdian-orange)](https://www.ifdian.net/a/lightnovelreader)

LightNovelReader is a fully free and open-source project.
If you enjoy the upstream project or find it helpful, consider supporting the original authors through [Aifadian](https://www.ifdian.net/a/lightnovelreader) (a China-based platform similar to Patreon).
All contributions go toward continuous development, new features, possible future server maintenance, and community growth.

## Translation

[![Crowdin](https://badges.crowdin.net/lightnovelreader/localized.svg)](https://crowdin.com/project/lightnovelreader)

LightNovelReader uses [Crowdin](https://crowdin.com/project/lightnovelreader) to manage translations. Want to help localize the app into your language? Head over to the Crowdin project to contribute!

> Don't see your language? Request it on [Crowdin](https://crowdin.com/project/lightnovelreader)!

## License

```
Copyright (C) 2024 by NightFish <hk198580666@outlook.com>
Copyright (C) 2024 by yukonisen <yukonisen@curiousers.org>

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```

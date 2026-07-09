**简体中文** | [繁體中文](README_TW.md) | [English](README_US.md) | [Русский](README_RU.md)

<div>
    <h1>LightNovelReader Enhanced</h1>
    <a><img alt="Android" src="https://img.shields.io/badge/Android-3DDC84?logo=android&logoColor=white&style=for-the-badge"/></a>
    <a><img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-0095D5.svg?logo=kotlin&logoColor=white&style=for-the-badge"/></a>
    <a><img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white&style=for-the-badge"></a>
    <a href="http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=P__gXIArh5UDBsEq7ttd4WhIYnNh3y1t&authKey=GAsRKEZ%2FwHpzRv19hNJsDnknOc86lYzNIHMPy2Jxt3S3U8f90qestOd760IAj%2F3l&noverify=0&group_code=867785526"><img alt="QQ Group" src="https://img.shields.io/badge/QQ讨论群-867785526-brightgreen.svg?logoColor=white&style=for-the-badge"></a>
    <a href="https://discord.gg/pnf4ABmDJt"><img alt="Discord" src="https://img.shields.io/badge/Discord-JOIN-4285F4.svg?logo=discord&logoColor=white&style=for-the-badge"></a>
    <a href="https://t.me/lightnoble"><img alt="Telegram" src="https://img.shields.io/badge/Telegram-JOIN-188FCA.svg?logo=telegram&logoColor=white&style=for-the-badge"></a>
    <p>基于 LightNovelReader 重构版维护的增强分支，使用 Jetpack Compose 编写</p>
    <img src="assets/header.png" alt="drawing"/>
</div>

## 介绍

LightNovelReader Enhanced 是 [LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader) <sup>*重构版*</sup> 的增强分叉。本仓库由 `vibub/LightNovelReader-enhanced` 在 `refactoring` 分支维护，保留上游 Kotlin + Jetpack Compose、离线缓存、书架、多数据源、插件与 EPUB 导出等能力，并持续合并上游改动。

这个分支的主要目标是保留上游体验，同时增强 Linovelib/Bilinovel 数据源、阅读进度/书签同步、章节解析稳定性以及 GitHub Actions 构建与更新体验。应用包名为 `indi.dmzz_yyhyy.lightnovelreader_enhanced`，可与上游正式包共存安装。

## 与上游的关系

- 上游仓库：[dmzz-yyhyy/LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader)
- 增强分支仓库：[vibub/LightNovelReader-enhanced](https://github.com/vibub/LightNovelReader-enhanced)
- 长期维护分支仍为 `refactoring`，本分支会继续吸收上游 `refactoring` 的更新。
- 通用功能、插件 API 与社区资源基本沿用上游；本仓库优先处理增强分支相关的构建、更新、Linovelib/Bilinovel 与本地改动问题。

## 特性

- 完全重构的版本（可在[此处](https://github.com/dmzz-yyhyy/LightNovelReader/tree/master)查看重构前的上游分支）
- 使用 Jetpack Compose，提供流畅的阅读体验，支持 Android 7.0 及以上
- 缓存－支持缓存书本内容，以及离线优先的阅读
- 探索－发现新书、推荐榜，标签分类，关键词搜索……
- 多数据源支持－可以切换数据源，甚至可以看漫画。数据源之间数据独立
- 书架－完整的书架系统，支持创建和命名书架，将书本加入收藏、获取书本更新提示
- 将书本导出为 EPUB 文件
- 插件机制－支持自定义数据源与插件扩展

## 增强分支重点

- Linovelib/Bilinovel 数据源增强：账号登录、Cookie 保存、网页登录搜索、书架同步与章节书签同步。
- 独立的 Bilinovel/Linovelib 章节书签本地存储：不再与本地阅读记录混用，并在书籍详情、目录和阅读器中显示书签入口。
- WebView 辅助同步：可注入已保存 Cookie，尝试自动定位并同步网页端章节书签，必要时支持手动处理。
- 章节解析与阅读稳定性：改进移动/桌面端 URL 处理、多页章节解析、插图与段落间距、连续滚动跨章加载与图片跳动等问题。
- 请求与限流处理：优化 Linovelib 请求频率，降低触发 Cloudflare 限流的概率，并在搜索/解析失败时提供更明确的错误路径。
- 更新与 CI：应用内更新源已切换到本增强仓库，支持 Release 与 GitHub Actions 构建产物检查，并补全 CI 构建更新日志展示。
- 包名区分：增强版 applicationId 使用 `indi.dmzz_yyhyy.lightnovelreader_enhanced`，避免与上游包名混淆。

## 插件开发与自定义数据源

您可以为 LightNovelReader 添加自定义的数据源与插件。本增强分支仍沿用上游插件 API 与文档。

以下为相关资源链接：

- [示例插件](https://github.com/dmzz-yyhyy/LightNovelReaderPlguin-Template)
- [开发指南](https://lnr.nariko.org/plugin-dev/)
- [LNR Api KDoc](https://api-doc.lnr.nariko.org/)

欢迎各位开发者进行开发！

## 下载

增强版请优先从本仓库下载：

- [GitHub Releases](https://github.com/vibub/LightNovelReader-enhanced/releases/latest)：发布版 APK。
- [ReleaseApkBuild Actions](https://github.com/vibub/LightNovelReader-enhanced/actions/workflows/marge.yml)：最新 CI 构建产物，适合体验最新修复；CI 构建未经完整发布测试。

> F-Droid 上的 `indi.dmzz_yyhyy.lightnovelreader` 包对应上游项目，不是本增强分支。如需上游官方版本，可前往 [LightNovelReader Releases](https://github.com/dmzz-yyhyy/LightNovelReader/releases/latest) 或 [F-Droid](https://f-droid.org/packages/indi.dmzz_yyhyy.lightnovelreader)。

## 支持

- 增强分支相关 Bug 或功能请求，请在 [本仓库 Issues](https://github.com/vibub/LightNovelReader-enhanced/issues/new/choose) 提交。
- 与上游通用功能、社区交流或插件生态相关的问题，也可以参考 [上游仓库](https://github.com/dmzz-yyhyy/LightNovelReader) 与下列社区渠道。
  - ~~欢迎加入 QQ 讨论群：`867785526` | [**邀请链接**](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=P__gXIArh5UDBsEq7ttd4WhIYnNh3y1t&authKey=GAsRKEZ%2FwHpzRv19hNJsDnknOc86lYzNIHMPy2Jxt3S3U8f90qestOd760IAj%2F3l&noverify=0&group_code=867785526)~~
  - 由于上游大群暂时被封，请先加入 QQ 讨论群：`1044272064` | [**邀请链接**](https://qm.qq.com/q/VLu11qDvgs)
  - 欢迎加入 Discord 服务器：[**邀请链接**](https://discord.gg/pnf4ABmDJt)
  - 欢迎加入 Telegram 讨论群组：[**邀请链接**](https://t.me/lightnoble)

## 软件截图

|                             |
|-----------------------------|
| ![image](assets/light1.png) |
| ![image](assets/light2.png) |
| ![image](assets/light3.png) |

### 关于 EpubLib

为了处理 EPUB 的导出问题，上游单独创建了一个 EPUB 处理模块；本分支继续沿用并维护该模块。如果您感兴趣，可以看[**这里**](https://github.com/vibub/LightNovelReader-enhanced/blob/refactoring/epub.md)。

## 贡献

欢迎对本增强分支或上游 LightNovelReader 进行贡献！以下是参与本仓库的方式：

### 开始

1. Fork 本仓库。
2. 克隆你的 fork：`git clone https://github.com/your-username/LightNovelReader-enhanced.git`
3. 为你的更改创建新分支：`git checkout -b feature/your-feature-name`
4. 进行更改并测试。
5. 按照下面的提交指南提交更改。
6. 推送到你的 fork：`git push origin feature/your-feature-name`
7. 向本仓库 `refactoring` 分支打开 Pull Request。

如果你的更改属于通用功能或插件 API，也欢迎同步考虑向上游项目贡献。

### 提交指南

- 保持提交原子化和描述性。
- 如果你的更改影响版本，请在 `app/build.gradle.kts` 中更新 `versionCode` / `versionName`。
- 涉及 Linovelib/Bilinovel、阅读器书签、Room migration、更新检查或 CI 的改动，请尽量补充或运行相关测试。

## 支持上游项目

[![爱发电赞助我们](https://img.shields.io/badge/❤%20支持我们-爱发电-orange)](https://www.ifdian.net/a/lightnovelreader)

LightNovelReader 是一个完全免费、开源的项目。
如果你喜欢上游项目或它对你有所帮助，欢迎通过 [爱发电](https://www.ifdian.net/a/lightnovelreader) 支持原作者。
所有款项将用于持续开发、新功能的实现、（如果有）服务器维护以及社区建设。

## 翻译

[![Crowdin](https://badges.crowdin.net/lightnovelreader/localized.svg)](https://crowdin.com/project/lightnovelreader)

LightNovelReader 使用 [Crowdin](https://crowdin.com/project/lightnovelreader) 管理翻译工作。如果你希望帮助翻译或改进现有的翻译，欢迎前往 Crowdin 项目页面参与贡献！

> 没有找到你的语言？欢迎在 [Crowdin](https://crowdin.com/project/lightnovelreader) 申请添加新语言！

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

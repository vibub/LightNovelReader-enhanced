[简体中文](README.md) | **繁體中文** | [English](README_US.md) | [Русский](README_RU.md)

<div>
    <h1>LightNovelReader Enhanced</h1>
    <a><img alt="Android" src="https://img.shields.io/badge/Android-3DDC84?logo=android&logoColor=white&style=for-the-badge"/></a>
    <a><img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-0095D5.svg?logo=kotlin&logoColor=white&style=for-the-badge"/></a>
    <a><img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white&style=for-the-badge"></a>
    <a href="http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=P__gXIArh5UDBsEq7ttd4WhIYnNh3y1t&authKey=GAsRKEZ%2FwHpzRv19hNJsDnknOc86lYzNIHMPy2Jxt3S3U8f90qestOd760IAj%2F3l&noverify=0&group_code=867785526"><img alt="QQ Group" src="https://img.shields.io/badge/QQ讨论群-867785526-brightgreen.svg?logoColor=white&style=for-the-badge"></a>
    <a href="https://discord.gg/pnf4ABmDJt"><img alt="Discord" src="https://img.shields.io/badge/Discord-JOIN-4285F4.svg?logo=discord&logoColor=white&style=for-the-badge"></a>
    <a href="https://t.me/lightnoble"><img alt="Telegram" src="https://img.shields.io/badge/Telegram-JOIN-188FCA.svg?logo=telegram&logoColor=white&style=for-the-badge"></a>
    <p>基於 LightNovelReader 重構版維護的增強分支，使用 Jetpack Compose 編寫</p>
    <img src="assets/header.png" alt="drawing"/>
</div>

## 介紹

LightNovelReader Enhanced 是 [LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader) <sup>*重構版*</sup> 的增強分叉。本倉庫由 `vibub/LightNovelReader-enhanced` 在 `refactoring` 分支維護，保留上游 Kotlin + Jetpack Compose、離線快取、書架、多資料來源、外掛與 EPUB 匯出等能力，並持續合併上游改動。

這個分支的主要目標是保留上游體驗，同時增強 Linovelib/Bilinovel 資料來源、閱讀進度/書籤同步、章節解析穩定性以及 GitHub Actions 建置與更新體驗。應用程式包名為 `indi.dmzz_yyhyy.lightnovelreader_enhanced`，可與上游正式包共存安裝。

## 與上游的關係

- 上游倉庫：[dmzz-yyhyy/LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader)
- 增強分支倉庫：[vibub/LightNovelReader-enhanced](https://github.com/vibub/LightNovelReader-enhanced)
- 長期維護分支仍為 `refactoring`，本分支會繼續吸收上游 `refactoring` 的更新。
- 通用功能、外掛 API 與社群資源基本沿用上游；本倉庫優先處理增強分支相關的建置、更新、Linovelib/Bilinovel 與本地改動問題。

## 特色

- 完全重構的版本（可在[此處](https://github.com/dmzz-yyhyy/LightNovelReader/tree/master)查看重構前的上游分支）
- 使用 Jetpack Compose，提供流暢的閱讀體驗，支援 Android 7.0 及以上
- 快取－支援快取書本內容，以及離線優先的閱讀
- 探索－發現新書、推薦榜，標籤分類，關鍵字搜尋……
- 多資料來源支援－可以切換資料來源，甚至可以看漫畫。資料來源之間資料獨立
- 書架－完整的書架系統，支援建立和命名書架，將書本加入收藏、取得書本更新提示
- 將書本匯出為 EPUB 檔案
- 外掛機制－支援自訂資料來源與外掛擴充

## 增強分支重點

- Linovelib/Bilinovel 資料來源增強：帳號登入、Cookie 儲存、網頁登入搜尋、書架同步與章節書籤同步。
- 獨立的 Bilinovel/Linovelib 章節書籤本地儲存：不再與本地閱讀記錄混用，並在書籍詳情、目錄和閱讀器中顯示書籤入口。
- WebView 輔助同步：可注入已儲存 Cookie，嘗試自動定位並同步網頁端章節書籤，必要時支援手動處理。
- 章節解析與閱讀穩定性：改進行動/桌面端 URL 處理、多頁章節解析、插圖與段落間距、連續滾動跨章載入與圖片跳動等問題。
- 請求與限流處理：最佳化 Linovelib 請求頻率，降低觸發 Cloudflare 限流的機率，並在搜尋/解析失敗時提供更明確的錯誤路徑。
- 更新與 CI：應用內更新源已切換到本增強倉庫，支援 Release 與 GitHub Actions 建置產物檢查，並補全 CI 建置更新日誌展示。
- 包名區分：增強版 applicationId 使用 `indi.dmzz_yyhyy.lightnovelreader_enhanced`，避免與上游包名混淆。

## 外掛開發與自訂資料來源

您可以為 LightNovelReader 新增自訂的資料來源與外掛。本增強分支仍沿用上游外掛 API 與文件。

以下為相關資源連結：

- [範例外掛程式](https://github.com/dmzz-yyhyy/LightNovelReaderPlguin-Template)
- [開發指南](https://lnr.nariko.org/plugin-dev/)
- [LNR Api KDoc](https://api-doc.lnr.nariko.org/)

歡迎各位開發者進行開發！

## 下載

增強版請優先從本倉庫下載：

- [GitHub Releases](https://github.com/vibub/LightNovelReader-enhanced/releases/latest)：發布版 APK。
- [ReleaseApkBuild Actions](https://github.com/vibub/LightNovelReader-enhanced/actions/workflows/marge.yml)：最新 CI 建置產物，適合體驗最新修復；CI 建置未經完整發布測試。

> F-Droid 上的 `indi.dmzz_yyhyy.lightnovelreader` 包對應上游專案，不是本增強分支。如需上游官方版本，可前往 [LightNovelReader Releases](https://github.com/dmzz-yyhyy/LightNovelReader/releases/latest) 或 [F-Droid](https://f-droid.org/packages/indi.dmzz_yyhyy.lightnovelreader)。

## 支援

- 增強分支相關 Bug 或功能請求，請在 [本倉庫 Issues](https://github.com/vibub/LightNovelReader-enhanced/issues/new/choose) 提交。
- 與上游通用功能、社群交流或外掛生態相關的問題，也可以參考 [上游倉庫](https://github.com/dmzz-yyhyy/LightNovelReader) 與下列社群渠道。
  - 歡迎加入 QQ 討論群：`867785526` | [**邀請連結**](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=P__gXIArh5UDBsEq7ttd4WhIYnNh3y1t&authKey=GAsRKEZ%2FwHpzRv19hNJsDnknOc86lYzNIHMPy2Jxt3S3U8f90qestOd760IAj%2F3l&noverify=0&group_code=867785526)
  - 歡迎加入 Discord 伺服器：[**邀請連結**](https://discord.gg/pnf4ABmDJt)
  - 歡迎加入 Telegram 討論群組：[**邀請連結**](https://t.me/lightnoble)

## 軟體截圖

|                             |
|-----------------------------|
| ![image](assets/light1.png) |
| ![image](assets/light2.png) |
| ![image](assets/light3.png) |

### 關於 EpubLib

為了處理 EPUB 的匯出問題，上游單獨建立了一個 EPUB 處理模組；本分支繼續沿用並維護該模組。如果您感興趣，可以看[**這裡**](https://github.com/vibub/LightNovelReader-enhanced/blob/refactoring/epub.md)。

## 貢獻

歡迎對本增強分支或上游 LightNovelReader 進行貢獻！以下是參與本倉庫的方式：

### 開始

1. Fork 本倉庫。
2. 複製你的 fork：`git clone https://github.com/your-username/LightNovelReader-enhanced.git`
3. 為你的更改建立新分支：`git checkout -b feature/your-feature-name`
4. 進行更改並測試。
5. 依照下方提交指南提交更改。
6. 推送到你的 fork：`git push origin feature/your-feature-name`
7. 向本倉庫 `refactoring` 分支開啟 Pull Request。

如果你的更改屬於通用功能或外掛 API，也歡迎同步考慮向上游專案貢獻。

### 提交指南

- 保持提交原子化且描述清楚。
- 如果你的更改影響版本，請在 `app/build.gradle.kts` 中更新 `versionCode` / `versionName`。
- 涉及 Linovelib/Bilinovel、閱讀器書籤、Room migration、更新檢查或 CI 的改動，請盡量補充或執行相關測試。

## 支持上游專案

[![支持我們 愛發電](https://img.shields.io/badge/❤%20支持我們-愛發電-orange)](https://www.ifdian.net/a/lightnovelreader)

LightNovelReader 是一個完全免費、開源的專案。
如果你喜歡上游專案或它對你有所幫助，歡迎透過 [愛發電](https://www.ifdian.net/a/lightnovelreader) 支持原作者。
所有款項將用於持續開發、新功能實作、（若有）伺服器維護以及社群建設。

## 翻譯

[![Crowdin](https://badges.crowdin.net/lightnovelreader/localized.svg)](https://crowdin.com/project/lightnovelreader)

LightNovelReader 使用 [Crowdin](https://crowdin.com/project/lightnovelreader) 管理翻譯工作。如果你希望協助翻譯或改進現有的譯文，歡迎前往 Crowdin 專案頁面參與貢獻！

> 找不到你的語言？歡迎在 [Crowdin](https://crowdin.com/project/lightnovelreader) 申請新增語言！

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

[简体中文](README.md) | [繁體中文](README_TW.md) | [English](README_US.md) | **Русский**

<div>
    <h1>LightNovelReader Enhanced</h1>
    <a><img alt="Android" src="https://img.shields.io/badge/Android-3DDC84?logo=android&logoColor=white&style=for-the-badge"/></a>
    <a><img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-0095D5.svg?logo=kotlin&logoColor=white&style=for-the-badge"/></a>
    <a><img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white&style=for-the-badge"></a>
    <a href="http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=P__gXIArh5UDBsEq7ttd4WhIYnNh3y1t&authKey=GAsRKEZ%2FwHpzRv19hNJsDnknOc86lYzNIHMPy2Jxt3S3U8f90qestOd760IAj%2F3l&noverify=0&group_code=867785526"><img alt="QQ Group" src="https://img.shields.io/badge/QQ讨论群-867785526-brightgreen.svg?logoColor=white&style=for-the-badge"></a>
    <a href="https://discord.gg/pnf4ABmDJt"><img alt="Discord" src="https://img.shields.io/badge/Discord-JOIN-4285F4.svg?logo=discord&logoColor=white&style=for-the-badge"></a>
    <a href="https://t.me/lightnoble"><img alt="Telegram" src="https://img.shields.io/badge/Telegram-JOIN-188FCA.svg?logo=telegram&logoColor=white&style=for-the-badge"></a>
    <p>Усиленная ветка на базе переработанной версии LightNovelReader, созданная с использованием Jetpack Compose</p>
    <img src="assets/header.png" alt="drawing"/>
</div>

## Введение

LightNovelReader Enhanced — это усиленный форк [LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader) <sup>*переработанной версии*</sup>. Репозиторий `vibub/LightNovelReader-enhanced` поддерживается в ветке `refactoring`; он сохраняет возможности upstream-проекта на Kotlin + Jetpack Compose, офлайн-кэширование, книжные полки, несколько источников данных, плагины и экспорт в EPUB, а также продолжает включать изменения из upstream.

Главная цель этой ветки — сохранить опыт upstream-версии и одновременно улучшить источник данных Linovelib/Bilinovel, синхронизацию прогресса чтения/закладок, стабильность разбора глав и работу сборок/обновлений через GitHub Actions. Идентификатор приложения: `indi.dmzz_yyhyy.lightnovelreader_enhanced`, поэтому его можно установить рядом с официальным upstream-пакетом.

## Связь с upstream

- Upstream-репозиторий: [dmzz-yyhyy/LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader)
- Репозиторий enhanced-форка: [vibub/LightNovelReader-enhanced](https://github.com/vibub/LightNovelReader-enhanced)
- Долгосрочная ветка сопровождения по-прежнему `refactoring`; эта ветка продолжит получать изменения из upstream `refactoring`.
- Общие функции, plugin API и ресурсы сообщества в основном следуют upstream; этот репозиторий в первую очередь занимается сборками, обновлениями, Linovelib/Bilinovel и локальными изменениями enhanced-форка.

## Особенности

- Полностью переработанная версия (см. [upstream-ветку до рефакторинга](https://github.com/dmzz-yyhyy/LightNovelReader/tree/master))
- Современный интерфейс на Jetpack Compose, поддержка Android 7.0 и выше
- Кэширование — поддержка кэширования содержимого книг и чтения с приоритетом офлайн-режима
- Обзор — открывайте новые книги, списки рекомендаций, категории тегов, поиск по ключевым словам и многое другое...
- Поддержка нескольких источников — легко переключайтесь между источниками, включая мангу. Данные между источниками независимы
- Книжная полка — полноценное управление полками с пользовательскими полками, избранным и уведомлениями об обновлениях книг
- Экспорт книг в формат EPUB
- Система плагинов — поддержка пользовательских источников данных и расширений через плагины

## Ключевые улучшения enhanced-ветки

- Улучшения источника Linovelib/Bilinovel: вход в аккаунт, сохранение Cookie, поиск через веб-страницы с учётом входа, синхронизация книжной полки и закладок глав.
- Отдельное локальное хранилище закладок глав Bilinovel/Linovelib: они больше не смешиваются с локальными записями чтения, а входы к закладкам показываются на странице книги, в оглавлении и в читалке.
- Синхронизация с помощью WebView: можно внедрять сохранённые Cookie, автоматически находить и синхронизировать закладки глав на сайте, а при необходимости выполнять действия вручную.
- Разбор глав и стабильность чтения: улучшена обработка мобильных/десктопных URL, многостраничных глав, расстояний между иллюстрациями и абзацами, непрерывной прокрутки между главами и скачков изображений.
- Обработка запросов и лимитов: оптимизирована частота запросов Linovelib, снижена вероятность срабатывания лимитов Cloudflare, а при сбоях поиска/разбора предоставляются более понятные пути ошибок.
- Обновления и CI: источник обновлений в приложении переключён на этот enhanced-репозиторий, поддерживается проверка Release и артефактов GitHub Actions, а также отображение списка изменений CI-сборок.
- Разделение пакетов: enhanced-версия использует applicationId `indi.dmzz_yyhyy.lightnovelreader_enhanced`, чтобы не смешиваться с upstream-пакетом.

## Разработка плагинов и пользовательских источников данных

Вы можете добавлять в LightNovelReader пользовательские источники данных и плагины. Этот enhanced-форк по-прежнему использует upstream plugin API и документацию.

Полезные ресурсы:

- [Пример плагина](https://github.com/dmzz-yyhyy/LightNovelReaderPlguin-Template)
- [Руководство разработчика](https://lnr.nariko.org/plugin-dev/)
- [LNR API KDoc](https://api-doc.lnr.nariko.org/)

Разработчики приветствуются!

## Загрузка

Enhanced-версию лучше скачивать из этого репозитория:

- [GitHub Releases](https://github.com/vibub/LightNovelReader-enhanced/releases/latest): APK релизных версий.
- [ReleaseApkBuild Actions](https://github.com/vibub/LightNovelReader-enhanced/actions/workflows/marge.yml): свежие CI-артефакты для проверки последних исправлений; CI-сборки не проходят полный релизный цикл тестирования.

> Пакет `indi.dmzz_yyhyy.lightnovelreader` в F-Droid относится к upstream-проекту, а не к этому enhanced-форку. Если вам нужна официальная upstream-версия, используйте [LightNovelReader Releases](https://github.com/dmzz-yyhyy/LightNovelReader/releases/latest) или [F-Droid](https://f-droid.org/packages/indi.dmzz_yyhyy.lightnovelreader).

## Поддержка

- Ошибки и запросы функций, связанные с enhanced-веткой, отправляйте в [Issues этого репозитория](https://github.com/vibub/LightNovelReader-enhanced/issues/new/choose).
- По вопросам общих upstream-функций, общения сообщества или экосистемы плагинов также можно обращаться к [upstream-репозиторию](https://github.com/dmzz-yyhyy/LightNovelReader) и каналам сообщества ниже.
  - Присоединяйтесь к группе обсуждений QQ: `867785526` | [**Ссылка-приглашение**](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=P__gXIArh5UDBsEq7ttd4WhIYnNh3y1t&authKey=GAsRKEZ%2FwHpzRv19hNJsDnknOc86lYzNIHMPy2Jxt3S3U8f90qestOd760IAj%2F3l&noverify=0&group_code=867785526)
  - Присоединяйтесь к серверу Discord: [**Ссылка-приглашение**](https://discord.gg/pnf4ABmDJt)
  - Присоединяйтесь к группе в Telegram: [**Ссылка-приглашение**](https://t.me/lightnoble)

## Скриншоты

|                             |
|-----------------------------|
| ![image](assets/light1.png) |
| ![image](assets/light2.png) |
| ![image](assets/light3.png) |

### О EpubLib

Для экспорта EPUB upstream-проект создал отдельный модуль EPUB; этот форк продолжает использовать и поддерживать его. Если вам интересно, смотрите [**здесь**](https://github.com/vibub/LightNovelReader-enhanced/blob/refactoring/epub.md).

## Вклад

Мы приветствуем вклад в этот enhanced-форк или upstream LightNovelReader! Вот как можно участвовать в этом репозитории:

### Начало работы

1. Форкните этот репозиторий.
2. Клонируйте ваш форк: `git clone https://github.com/your-username/LightNovelReader-enhanced.git`
3. Создайте новую ветку для ваших изменений: `git checkout -b feature/your-feature-name`
4. Внесите изменения и протестируйте их.
5. Зафиксируйте изменения, следуя приведённым ниже рекомендациям по коммитам.
6. Отправьте изменения в ваш форк: `git push origin feature/your-feature-name`
7. Откройте Pull Request в ветку `refactoring` этого репозитория.

Если ваше изменение относится к общим функциям или plugin API, также рассмотрите возможность отправить его в upstream.

### Рекомендации по коммитам

- Делайте коммиты атомарными и описательными.
- Если ваше изменение влияет на версию, обновите `versionCode` / `versionName` в `app/build.gradle.kts`.
- Для изменений, затрагивающих Linovelib/Bilinovel, закладки в читалке, Room migration, проверку обновлений или CI, по возможности добавляйте или запускайте соответствующие тесты.

## Поддержать upstream-проект

[![Поддержать нас на Aifadian](https://img.shields.io/badge/❤%20Support%20Us-ifdian-orange)](https://www.ifdian.net/a/lightnovelreader)

LightNovelReader — полностью бесплатный проект с открытым исходным кодом.
Если вам нравится upstream-проект или он оказался полезен, рассмотрите возможность поддержать оригинальных авторов через [Aifadian](https://www.ifdian.net/a/lightnovelreader) (китайскую платформу, похожую на Patreon).
Все средства идут на дальнейшую разработку, новые функции, возможное обслуживание серверов и развитие сообщества.

## Перевод

[![Crowdin](https://badges.crowdin.net/lightnovelreader/localized.svg)](https://crowdin.com/project/lightnovelreader)

LightNovelReader использует [Crowdin](https://crowdin.com/project/lightnovelreader) для управления переводами. Хотите помочь локализовать приложение на свой язык? Перейдите на страницу проекта Crowdin и внесите вклад!

> Не видите свой язык? Запросите его добавление на [Crowdin](https://crowdin.com/project/lightnovelreader)!

## Лицензия

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

package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.sync

import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import java.text.Normalizer

internal object LinovelibBookmarkMatcher {
    fun resolve(
        remoteChapterId: String,
        remoteTitle: String,
        volumes: BookVolumes
    ): ChapterInformation? {
        val candidates = volumes.toChapterCandidates()
        if (candidates.isEmpty()) return null

        val remoteIds = chapterIdCandidates(remoteChapterId)
        if (remoteIds.isNotEmpty()) {
            candidates.firstOrNull { candidate ->
                chapterIdCandidates(candidate.chapter.id).any { it in remoteIds }
            }?.let { return it.chapter }
        }

        val remoteKey = remoteTitle.toTitleKey()
        if (remoteKey.compact.isBlank()) return null

        uniqueChapter(candidates.filter { it.titleKey.sameDirectTitle(remoteKey) })?.let { return it }

        val volumeScoped = candidates.filter { it.matchesRemoteVolumeContext(remoteKey) }
        if (volumeScoped.isNotEmpty()) {
            uniqueChapter(volumeScoped.filter { it.titleKey.matchesAnyMeaningfulVariant(remoteKey) })?.let { return it }
            remoteKey.shortKind?.let { kind ->
                uniqueChapter(volumeScoped.filter { it.titleKey.shortKind == kind })?.let { return it }
            }
        }

        uniqueChapter(candidates.filter { it.titleKey.matchesAnyMeaningfulVariant(remoteKey) })?.let { return it }

        remoteKey.shortKind?.let { kind ->
            uniqueChapter(candidates.filter { it.titleKey.shortKind == kind })?.let { return it }
        }

        return uniqueChapter(candidates.filter { it.titleKey.boundedContains(remoteKey) })
    }

    fun matchesTitle(remoteTitle: String, localTitle: String): Boolean {
        val remoteKey = remoteTitle.toTitleKey()
        val localKey = localTitle.toTitleKey()
        if (remoteKey.compact.isBlank() || localKey.compact.isBlank()) return false
        return localKey.sameDirectTitle(remoteKey) ||
            localKey.matchesAnyMeaningfulVariant(remoteKey) ||
            (remoteKey.shortKind != null && remoteKey.shortKind == localKey.shortKind) ||
            localKey.boundedContains(remoteKey)
    }

    fun chapterIdCandidates(value: String): Set<String> {
        val normalized = value.trim()
            .replace("&amp;", "&")
            .substringBefore('#')
        val ids = linkedSetOf<String>()
        listOf(
            Regex("[?&](?:cid|chapterid|chapter_id)=(\\d+(?:_\\d+)?)", RegexOption.IGNORE_CASE),
            Regex("cid\\((\\d+(?:_\\d+)?)\\)", RegexOption.IGNORE_CASE),
            Regex("/(\\d+(?:_\\d+)?)\\.html", RegexOption.IGNORE_CASE)
        ).forEach { regex ->
            regex.findAll(normalized).forEach { match ->
                match.groups[1]?.value?.addChapterIdVariantsTo(ids)
            }
        }

        val fallback = normalized
            .substringBefore('?')
            .substringBefore(".html")
            .substringAfterLast('/')
            .removePrefix("cid(")
            .removeSuffix(")")
            .filter { it.isDigit() || it == '_' }
        fallback.addChapterIdVariantsTo(ids)
        return ids
    }

    private fun BookVolumes.toChapterCandidates(): List<ChapterCandidate> = volumes.flatMapIndexed { volumeIndex, volume ->
        volume.chapters.mapIndexed { chapterIndex, chapter ->
            ChapterCandidate(
                chapter = chapter,
                volume = volume,
                volumeIndex = volumeIndex,
                chapterIndex = chapterIndex,
                titleKey = chapter.title.toTitleKey(),
                volumeKey = volume.volumeTitle.toTitleKey()
            )
        }
    }

    private data class ChapterCandidate(
        val chapter: ChapterInformation,
        val volume: Volume,
        val volumeIndex: Int,
        val chapterIndex: Int,
        val titleKey: TitleKey,
        val volumeKey: TitleKey
    ) {
        fun matchesRemoteVolumeContext(remoteKey: TitleKey): Boolean {
            val candidateNumbers = buildSet {
                add(volumeIndex + 1)
                addAll(volumeKey.volumeNumbers)
            }
            if (remoteKey.volumeNumbers.isNotEmpty() && remoteKey.volumeNumbers.any { it in candidateNumbers }) {
                return true
            }
            if (volumeKey.compact.length >= 3 && volumeKey.compact !in GENERIC_VOLUME_TITLES) {
                return remoteKey.compact.contains(volumeKey.compact) ||
                    remoteKey.meaningfulVariants.any { it.contains(volumeKey.compact) }
            }
            return false
        }
    }

    private data class TitleKey(
        val normalized: String,
        val compact: String,
        val meaningfulVariants: Set<String>,
        val shortKind: ShortTitleKind?,
        val volumeNumbers: Set<Int>,
        val chapterNumbers: Set<String>
    ) {
        fun sameDirectTitle(other: TitleKey): Boolean =
            normalized == other.normalized || compact == other.compact

        fun matchesAnyMeaningfulVariant(other: TitleKey): Boolean =
            meaningfulVariants.any { it in other.meaningfulVariants }

        fun boundedContains(other: TitleKey): Boolean {
            if (hasConflictingChapterNumber(other)) return false
            for (self in meaningfulVariants) {
                for (target in other.meaningfulVariants) {
                    if (self == target) return true
                    if (self in GENERIC_COMPACT_TITLES || target in GENERIC_COMPACT_TITLES) continue
                    val shorter = minOf(self.length, target.length)
                    if (shorter < 4) continue
                    if (self.endsWith(target) || target.endsWith(self)) return true
                    if (shorter >= 6 && (self.contains(target) || target.contains(self))) return true
                }
            }
            return false
        }

        private fun hasConflictingChapterNumber(other: TitleKey): Boolean =
            chapterNumbers.isNotEmpty() && other.chapterNumbers.isNotEmpty() && chapterNumbers.intersect(other.chapterNumbers).isEmpty()
    }

    private enum class ShortTitleKind {
        Illustration,
        Prologue,
        Epilogue,
        Afterword,
        Interlude,
        Extra
    }

    private fun String.addChapterIdVariantsTo(target: MutableSet<String>) {
        if (isBlank()) return
        target += this
        substringBefore('_').takeIf { it.isNotBlank() }?.let { target += it }
    }

    private fun uniqueChapter(candidates: List<ChapterCandidate>): ChapterInformation? =
        candidates.distinctBy { it.chapter.id }.singleOrNull()?.chapter

    private fun String.toTitleKey(): TitleKey {
        val normalized = normalizeBookmarkTitle()
        val variants = normalized.titleVariants()
        val compactVariants = variants
            .map { it.compactBookmarkTitle() }
            .filter { it.isMeaningfulCompactTitle() }
            .toSet()
        val compact = normalized.compactBookmarkTitle()
        return TitleKey(
            normalized = normalized,
            compact = compact,
            meaningfulVariants = buildSet {
                if (compact.isMeaningfulCompactTitle()) add(compact)
                addAll(compactVariants)
            },
            shortKind = findShortTitleKind(compact),
            volumeNumbers = normalized.extractVolumeNumbers(),
            chapterNumbers = normalized.extractChapterNumbers()
        )
    }

    private fun String.titleVariants(): Set<String> = buildSet {
        val base = this@titleVariants
        add(base)
        val noStorePrefix = base.stripStoreBonusPrefix()
        add(noStorePrefix)
        val noVolumePrefix = base.stripLeadingVolumePrefix()
        add(noVolumePrefix)
        add(noStorePrefix.stripLeadingVolumePrefix())
        add(noVolumePrefix.stripStoreBonusPrefix())
    }.filter { it.isNotBlank() }.toSet()

    private fun String.normalizeBookmarkTitle(): String = Normalizer.normalize(this, Normalizer.Form.NFKC)
        .replace(' ', ' ')
        .replace('　', ' ')
        .replace('：', ':')
        .replace('〜', '～')
        .replace(Regex("^(?:书签章节|書籤章節|书签|書籤|阅读至|閱讀至|读到|讀到|看到|继续阅读|繼續閱讀|上次阅读|上次閱讀|最近阅读|最近閱讀)[:：\\s]*"), "")
        .normalizeChineseNumbers()
        .replace(Regex("[\\s]+"), " ")
        .trim(' ', ':', '：', '-', '—', '－', '–', '～', '~')
        .lowercase()

    private fun String.compactBookmarkTitle(): String = normalizeBookmarkTitle()
        .replace(Regex("[\\s:：,，、。.!！?？;；《》〈〉「」『』（）()\\[\\]【】〔〕〖〗｢｣\"“”‘’'`·・･~～_\\-—－–―─/／|｜&＆+＋…‥]+"), "")
        .trim()

    private fun String.stripLeadingVolumePrefix(): String = replace(
        Regex("^(?:第\\s*\\d+\\s*卷|vol(?:ume)?\\.?\\s*\\d+)[:：\\s_\\-—－–~～]*", RegexOption.IGNORE_CASE),
        ""
    ).trim()

    private fun String.stripStoreBonusPrefix(): String = replace(
        Regex("^(?:(?:animate|bookwalker|melonbooks|gamers|ゲーマーズ|虎之穴|とらのあな|店铺|店鋪|店舗|电子书|電子書|限定|特装版)\\s*)?(?:特典|bonus)[:：\\s_\\-—－–~～]*", RegexOption.IGNORE_CASE),
        ""
    ).trim()

    private fun String.isMeaningfulCompactTitle(): Boolean =
        isNotBlank() && this !in GENERIC_COMPACT_TITLES && (length >= 2 || any { it.isDigit() })

    private fun findShortTitleKind(compact: String): ShortTitleKind? = SHORT_TITLE_SYNONYMS.entries
        .firstOrNull { (_, synonyms) -> synonyms.any { compact == it || compact.endsWith(it) } }
        ?.key

    private fun String.extractVolumeNumbers(): Set<Int> = buildSet {
        Regex("第\\s*(\\d{1,3})\\s*卷").findAll(this@extractVolumeNumbers).forEach { match ->
            match.groups[1]?.value?.toIntOrNull()?.let(::add)
        }
        Regex("\\bvol(?:ume)?\\.?\\s*(\\d{1,3})\\b", RegexOption.IGNORE_CASE).findAll(this@extractVolumeNumbers).forEach { match ->
            match.groups[1]?.value?.toIntOrNull()?.let(::add)
        }
    }

    private fun String.extractChapterNumbers(): Set<String> = buildSet {
        Regex("第\\s*(\\d{1,3}(?:[._-]\\d{1,3})?)\\s*[章节話话]").findAll(this@extractChapterNumbers).forEach { match ->
            match.groups[1]?.value?.replace('_', '.')?.replace('-', '.')?.let(::add)
        }
        Regex("\\bchap(?:ter)?\\.?\\s*(\\d{1,3}(?:[._-]\\d{1,3})?)\\b", RegexOption.IGNORE_CASE).findAll(this@extractChapterNumbers).forEach { match ->
            match.groups[1]?.value?.replace('_', '.')?.replace('-', '.')?.let(::add)
        }
    }

    private fun String.normalizeChineseNumbers(): String = replace(
        Regex("第\\s*([零〇一二两三四五六七八九十百]{1,4})\\s*([卷章节話话])")
    ) { match ->
        val number = parseChineseNumber(match.groupValues[1]) ?: return@replace match.value
        "第${number}${match.groupValues[2]}"
    }

    private fun parseChineseNumber(value: String): Int? {
        if (value.isBlank()) return null
        if (value == "十") return 10
        if ('百' in value) {
            val parts = value.split('百', limit = 2)
            val hundreds = parts.getOrNull(0)?.singleChineseDigit() ?: return null
            val rest = parts.getOrNull(1).orEmpty().takeIf { it.isNotBlank() }?.let(::parseChineseNumber) ?: 0
            return hundreds * 100 + rest
        }
        if ('十' in value) {
            val parts = value.split('十', limit = 2)
            val tens = parts.getOrNull(0).orEmpty().takeIf { it.isNotBlank() }?.singleChineseDigit() ?: 1
            val ones = parts.getOrNull(1).orEmpty().takeIf { it.isNotBlank() }?.singleChineseDigit() ?: 0
            return tens * 10 + ones
        }
        return value.singleChineseDigit()
    }

    private fun String.singleChineseDigit(): Int? = when (this) {
        "零", "〇" -> 0
        "一" -> 1
        "二", "两" -> 2
        "三" -> 3
        "四" -> 4
        "五" -> 5
        "六" -> 6
        "七" -> 7
        "八" -> 8
        "九" -> 9
        else -> null
    }

    private val SHORT_TITLE_SYNONYMS = mapOf(
        ShortTitleKind.Illustration to setOf("插图", "插圖", "插画", "插畫", "彩页", "彩頁", "彩图", "彩圖", "illustration", "illustrations"),
        ShortTitleKind.Prologue to setOf("序章", "序幕", "楔子", "prologue", "プロローグ"),
        ShortTitleKind.Epilogue to setOf("终章", "終章", "epilogue", "エピローグ"),
        ShortTitleKind.Afterword to setOf("后记", "後記", "あとがき", "afterword"),
        ShortTitleKind.Interlude to setOf("间章", "間章", "幕间", "幕間", "interlude"),
        ShortTitleKind.Extra to setOf("特典", "番外", "短篇", "ss", "extra", "bonus")
    )

    private val GENERIC_COMPACT_TITLES = setOf("章", "话", "話", "卷", "特典", "bonus", "extra", "ss")
    private val GENERIC_VOLUME_TITLES = setOf("正文", "目录", "目錄", "章节", "章節")
}

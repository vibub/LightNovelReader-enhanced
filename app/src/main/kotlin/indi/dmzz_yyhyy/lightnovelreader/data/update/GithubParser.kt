package indi.dmzz_yyhyy.lightnovelreader.data.update

import android.util.Log
import androidx.compose.ui.util.fastFilter
import indi.dmzz_yyhyy.lightnovelreader.utils.md.HtmlToMdUtil
import kotlinx.coroutines.flow.MutableStateFlow
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/***
 * Github的更新源获取
 * 我们知道github api
 * 但是为了可以让github加速生效(大多数加速方案不支持github api), 我们被迫选择解析网页
 */
object GithubParser {
    private const val REPOSITORY_SLUG = "vibub/LightNovelReader-enhanced"
    private const val REPOSITORY_PATH = "/vibub/LightNovelReader-enhanced"
    private const val LATEST_RELEASE_PATH = "$REPOSITORY_PATH/releases/latest"
    private const val BUILD_GRADLE_PATH = "app/build.gradle.kts"
    private const val DEFAULT_BRANCH = "refactoring"
    private const val WORKFLOW_FILE = "marge.yml"
    private val versionCodeRegex = Regex("versionCode = ([0-9_]+)")
    private val versionNameRegex = Regex("versionName = (.*)")
    private val artifactNameRegex = Regex("""(?:^|/)LightNovelReader-([^/\s]+)-([0-9]+)-release(?:\.(?:zip|apk))?(?:$|[?#])""")
    private val regex = Regex("([0-9]*\\.[0-9]*\\.[0-9]*\\.[0-9]*).*[^.]github.com\\n")
    private const val RAW_HOST = "https://github.com"
    private const val PROXY_HOST = "https://dgithub.xyz"
    private var host = RAW_HOST

    internal fun rawGithubUrlCandidatesForTest(ref: String, path: String): List<String> =
        rawGithubUrlCandidates(ref, path)

    internal fun githubAssetUrlCandidatesForTest(href: String): List<String> =
        githubAssetUrlCandidates(href)

    internal fun latestReleasePathForTest(): String = LATEST_RELEASE_PATH

    private fun rawGithubUrlCandidates(ref: String, path: String): List<String> {
        val normalizedPath = path.trimStart('/')
        return listOf(
            "https://raw.githubusercontent.com/$REPOSITORY_SLUG/$ref/$normalizedPath",
            "https://gh-proxy.com/raw.githubusercontent.com/$REPOSITORY_SLUG/$ref/$normalizedPath"
        )
    }

    private fun githubAssetUrlCandidates(href: String): List<String> {
        val normalizedHref = when {
            href.startsWith("https://github.com") -> href.removePrefix("https://github.com")
            href.startsWith("/") -> href
            else -> "/$href"
        }
        return listOf(
            "https://github.com$normalizedHref",
            "https://gh-proxy.com/github.com$normalizedHref"
        )
    }

    private fun fetchTextFromCandidates(urls: List<String>): String {
        var lastError: Exception? = null
        urls.forEach { url ->
            try {
                return Jsoup
                    .connect(url)
                    .ignoreContentType(true)
                    .get()
                    .outputSettings(
                        Document.OutputSettings()
                            .prettyPrint(false)
                            .syntax(Document.OutputSettings.Syntax.xml)
                    )
                    .toString()
            } catch (e: Exception) {
                lastError = e
                Log.w("GithubParser", "failed to fetch $url: ${e.message}")
            }
        }
        throw lastError ?: IOException("No URL candidates provided")
    }

    private fun fetchRawGithubText(ref: String): String =
        fetchTextFromCandidates(rawGithubUrlCandidates(ref, BUILD_GRADLE_PATH))

    private fun updateHost(): String {
        try {
            Jsoup.connect(host).timeout(1500).get()
            return host
        } catch (_: Exception) { }
        try {
            Jsoup.connect(RAW_HOST).timeout(1500).get()
            return RAW_HOST
        } catch (_: Exception) { }
        try {
            Jsoup.connect(PROXY_HOST).timeout(1500).get()
            return PROXY_HOST
        } catch (_: Exception) {}
        try {
            fetchTextFromCandidates(
                listOf(
                    "https://raw.githubusercontent.com/frankwuzp/github-host/main/hosts",
                    "https://gh-proxy.com/raw.githubusercontent.com/frankwuzp/github-host/main/hosts"
                )
            )
                .let(regex::find)
                ?.groups
                ?.get(1)
                ?.value
                ?.let { return "http://$it" }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return RAW_HOST
    }

    class GithubRelease(
        override val version: Int,
        override val versionName: String,
        override val releaseNotes: String,
        override val downloadUrl: String,
        override val downloadUrls: List<String> = listOf(downloadUrl),
        override val downloadFileProgress: ((File, File) -> Unit)? = null
    ): Release

    private fun normalizeGithubUrl(href: String): String =
        when {
            href.startsWith("https://github.com") -> href.replace("https://github.com", host)
            href.startsWith("http") -> href
            else -> host + href
        }

    private fun connectGithubPage(url: String) =
        Jsoup.connect(url).also {
            if (url.startsWith("http://")) it.header("Host", "github.com")
        }

    private fun parseCommitReleaseNotes(commitHref: String): String? =
        try {
            val commitDocument = connectGithubPage(normalizeGithubUrl(commitHref)).get()
            val commitSha = commitHref.substringBefore('?').substringAfterLast('/').take(7)
            val commitTitle = commitDocument
                .selectFirst("div[class*=CommitHeader-module__commitMessageContainer] span.ws-pre-wrap div")
                ?.text()
                ?: commitDocument.selectFirst("span.ws-pre-wrap div")?.text()
                ?: commitDocument.title().substringBefore(" · ").trim().takeIf { it.isNotBlank() }
            val commitDescription = commitDocument
                .selectFirst("span.extended-commit-description-container, pre.commit-desc")
                ?.wholeText()
                ?.trim()

            commitTitle?.let {
                buildString {
                    append("本次 CI 构建来源提交")
                    if (commitSha.isNotBlank()) append(" `$commitSha`")
                    append(": \n\n")
                    appendLine(it)
                    if (!commitDescription.isNullOrBlank()) {
                        appendLine()
                        append(commitDescription)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GithubParser", "failed to get commit release notes: ${e.message}")
            null
        }

    private fun parseWorkflowRunCommitReleaseNotes(
        actionRunHref: String,
        workflowRunTitle: String?
    ): String? =
        try {
            val runDocument = connectGithubPage(normalizeGithubUrl(actionRunHref)).get()
            val commitReleaseNotes = runDocument
                .select("""a[href*="$REPOSITORY_PATH/commit/"]""")
                .firstOrNull()
                ?.attr("href")
                ?.let(::parseCommitReleaseNotes)
            commitReleaseNotes
                ?: runDocument.title()
                    .substringBefore(" · ")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { "本次 CI 构建来源: $it" }
                ?: workflowRunTitle?.let { "本次 CI 构建来源: $it" }
        } catch (e: Exception) {
            Log.e("GithubParser", "failed to get workflow run commit release notes: ${e.message}")
            workflowRunTitle?.let { "本次 CI 构建来源: $it" }
        }

    private fun progressReleasePage(url: String, updatePhase: MutableStateFlow<String>): Release? {
        Jsoup
            .connect(url)
            .also {
                if (url.startsWith("http://")) it.header("Host", "github.com")
            }
            .get()
            .let { releaseDocument ->
                updatePhase.tryEmit("GitHub步骤: 获取apk下载链接")
                val downloadHref = releaseDocument
                    .select("include-fragment")
                    .fastFilter { it.attr("src").contains("releases") }
                    .first()
                    .attr("src")
                    .replace("https://github.com", host)
                    .let(Jsoup::connect)
                    .also {
                        if (host.startsWith("http://")) it.header("Host", "github.com")
                    }
                    .get()
                    .select("""a[href^="$REPOSITORY_PATH/releases/download/"]""")
                    .map { it.attr("href") }
                    .firstOrNull { it.endsWith("apk") }
                    ?: Log.e("GithubParser", "failed to get downloadUrl").let { return null }
                val downloadUrls = githubAssetUrlCandidates(downloadHref)
                val downloadUrl = downloadUrls.first()
                updatePhase.tryEmit("GitHub步骤: 拉取远程分支版本号")
                val tag = releaseDocument
                    .select("""a[href^="$REPOSITORY_PATH/tree/"]""")
                    .attr("href")
                    .replace("$REPOSITORY_PATH/tree/", "")
                val gradle = fetchRawGithubText("refs/tags/$tag")
                val versionCode = versionCodeRegex.find(gradle)?.groups?.get(1)?.value?.replace("_", "")?.toIntOrNull() ?: Log.e("GithubParser", "failed to get versionCode").also { return null }
                val versionName = versionNameRegex.find(gradle)?.groups?.get(1)?.value?.replace("\"", "") ?: Log.e("GithubParser", "failed to get versionName").also { return null }
                updatePhase.tryEmit("GitHub步骤: 解析更新日志")
                val releaseNotes = releaseDocument
                    .selectFirst("div.markdown-body")
                    .toString()
                    .let(HtmlToMdUtil::convertHtml)
                return GithubRelease(
                    versionCode,
                    versionName.toString(),
                    releaseNotes,
                    downloadUrl,
                    downloadUrls
                )
            }
    }

    object ReleaseParser: UpdateParser {
        override fun parser(updatePhase: MutableStateFlow<String>): Release? {
            System.setProperty("sun.net.http.allowRestrictedHeaders", "true")
            host = updateHost()
            updatePhase.tryEmit("GitHub步骤: 获取最新Release")
            return progressReleasePage(host + LATEST_RELEASE_PATH, updatePhase)
        }
    }
    object DevelopmentParser: UpdateParser {
        private const val URL = "$REPOSITORY_PATH/releases"
        override fun parser(updatePhase: MutableStateFlow<String>): Release? {
            System.setProperty("sun.net.http.allowRestrictedHeaders", "true")
            host = updateHost()
            val releasePath = Jsoup
                .connect(host+ URL)
                .also {
                    if (host.startsWith("http://")) it.header("Host", "github.com")
                }
                .get()
                .selectFirst("""a[href^="$REPOSITORY_PATH/releases/tag/"]""")
                ?.attr("href")
                ?: return null
            updatePhase.tryEmit("GitHub步骤: 获取最新Release")
            return progressReleasePage(host + releasePath, updatePhase)
        }
    }
    object CIParser: UpdateParser {
        private const val URL = "$REPOSITORY_PATH/actions/workflows/$WORKFLOW_FILE"
        private val prIdRegex = Regex("""(?:(?:Merge pull request|pull request)\s*#|/pull/)([0-9]+)""", RegexOption.IGNORE_CASE)
        override fun parser(updatePhase: MutableStateFlow<String>): Release? {
            System.setProperty("sun.net.http.allowRestrictedHeaders", "true")
            host = updateHost()
            updatePhase.tryEmit("GitHub步骤: 获取最新Release")
            val downloadUrl: String?
            updatePhase.tryEmit("GitHub步骤: 拉取远程分支版本号")
            val gradle = fetchRawGithubText("refs/heads/$DEFAULT_BRANCH")
            val fallbackVersionCode = versionCodeRegex.find(gradle)?.groups?.get(1)?.value?.replace("_", "")?.toIntOrNull() ?: Log.e("GithubParser", "failed to get versionCode").also { return null }
            val fallbackVersionName = versionNameRegex.find(gradle)?.groups?.get(1)?.value?.replace("\"", "") ?: Log.e("GithubParser", "failed to get versionName").also { return null }
            val connection = Jsoup.connect(host + URL)
            if (host.startsWith("http://")) {
                connection.header("Host", "github.com")
            }
            val document = connection.get()
            updatePhase.tryEmit("Github步骤: 获取apk下载链接")
            val apkLinkElement = document.select(
                "div[id^=check_suite_]:contains(ReleaseApkBuild) > div > div.d-table-cell.v-align-top.col-11.col-md-6.position-relative > a"
            ).first()
                ?: Log.e("GithubParser", "failed to get action run link").let { return null }
            val actionRunHref = apkLinkElement.attr("href")
                .takeIf { it.isNotBlank() }
                ?: Log.e("GithubParser", "failed to get action run link").let { return null }
            val actionUrl = "https://nightly.link$actionRunHref"
            val fileDocument = Jsoup.connect(actionUrl).get()
            val artifactLinkElement = fileDocument
                .select("body > article > table > tbody > tr > td > a")
                .first()
                ?: Log.e("GithubParser", "failed to get artifact link").let { return null }
            val apkDownloadHref = artifactLinkElement.attr("href")
            val artifactFileName = apkDownloadHref.substringBefore('?').substringAfterLast('/')
            val artifactMatch = artifactNameRegex.find(artifactLinkElement.text())
                ?: artifactNameRegex.find(artifactFileName)
            val versionCode = artifactMatch?.groups?.get(2)?.value?.toIntOrNull() ?: fallbackVersionCode
            val versionName = artifactMatch?.groups?.get(1)?.value ?: fallbackVersionName
            val downloadUrls = listOf(apkDownloadHref)
            downloadUrl = downloadUrls.first()
            val downloadFileProgress: ((File, File) -> Unit) = { zipFile, targetApk ->
                try {

                    ZipFile(zipFile).use { zip ->
                        val apkEntry = zip.entries().asSequence()
                            .filterNot { it.isDirectory }
                            .find { entry ->
                                entry.name.endsWith(".apk") &&
                                        "release" in entry.name
                            }

                            ?: throw IOException("failed to extract apk file from archive [${zipFile.name}]")

                        targetApk.parentFile?.mkdirs()
                        if (targetApk.exists()) targetApk.delete()

                        zip.getInputStream(apkEntry).use { input ->
                            targetApk.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Unzip", "failed to extract: ${e.message}")
                    targetApk.delete()
                    throw e
                }
            }
            updatePhase.tryEmit("GitHub步骤: 获取更新日志")

            val workflowRunTitle = apkLinkElement.text()
                .trim()
                .takeIf { it.isNotBlank() && it != "ReleaseApkBuild" }
            val prId = document.select("""a[href*="$REPOSITORY_PATH/pull/"]""")
                .firstNotNullOfOrNull { prIdRegex.find(it.attr("href"))?.groups?.get(1)?.value }
                ?: prIdRegex.find(document.text())?.groups?.get(1)?.value
                ?: workflowRunTitle?.let { prIdRegex.find(it)?.groups?.get(1)?.value }

            val prUrl = prId?.let { "$host$REPOSITORY_PATH/pull/$it" }
            val prConnection = prUrl?.let { Jsoup.connect(it) }
            if (host.startsWith("http://")) {
                prConnection?.header("Host", "github.com")
            }

            val prReleaseNotes = try {
                prConnection?.get()
                    ?.selectFirst("div.js-comment-body.markdown-body, div.js-comment-body, td.comment-body, div.comment-body.markdown-body, div.markdown-body")
                    ?.html()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(HtmlToMdUtil::convertHtml)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Log.e("GithubParser", "failed to get PR release notes: ${e.message}")
                null
            }
            val fallbackReleaseNotes = if (prReleaseNotes == null) {
                parseWorkflowRunCommitReleaseNotes(actionRunHref, workflowRunTitle)
                    ?: workflowRunTitle?.let { "本次 CI 构建来源: $it" }
                    ?: "暂无可用更新日志。"
            } else null
            val releaseNotes = "**注意! 这是一个由 GitHub Actions 构建出来的版本, 此版本未经过严格测试**\n\n${prReleaseNotes ?: fallbackReleaseNotes}"

            updatePhase.tryEmit("GitHub步骤: 比对版本号")
            val lastReleaseRelease = ReleaseParser.parser(MutableStateFlow(""))
            return if (lastReleaseRelease == null || lastReleaseRelease.version < versionCode)
                GithubRelease(
                    versionCode,
                    versionName.toString() ,
                    releaseNotes,
                    downloadUrl,
                    downloadUrls,
                    downloadFileProgress
                )
            else lastReleaseRelease
        }
    }
}
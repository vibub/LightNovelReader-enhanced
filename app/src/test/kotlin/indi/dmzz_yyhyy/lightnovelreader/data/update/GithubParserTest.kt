package indi.dmzz_yyhyy.lightnovelreader.data.update

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GithubParserTest {
    @Test
    fun workflowRunNotesDoNotUsePullRequestsFromOlderRuns() {
        val workflowDocument = Jsoup.parse(
            """
            <div id="check_suite_current">
                <a href="/vibub/LightNovelReader-enhanced/actions/runs/36703513649">
                    feat(reader): 统一正文排版并修复空白叠加与文本裁切
                </a>
            </div>
            <div id="check_suite_old">
                <a href="/vibub/LightNovelReader-enhanced/actions/runs/35198532565">
                    Merge pull request #1 from dmzz-yyhyy/refactoring
                </a>
            </div>
            """.trimIndent()
        )
        val workflowRunTitle = workflowDocument.selectFirst("#check_suite_current a")!!.text()
        assertNull(GithubParser.workflowRunPullRequestId(workflowRunTitle))
        assertEquals("1", GithubParser.workflowRunPullRequestId("Merge pull request #1 from dmzz-yyhyy/refactoring"))

        val runDocument = Jsoup.parse(
            """<a href="/vibub/LightNovelReader-enhanced/commit/ded081de679499ee005322463272c929444da03a">ded081d</a>"""
        )
        assertEquals(
            "/vibub/LightNovelReader-enhanced/commit/ded081de679499ee005322463272c929444da03a",
            GithubParser.workflowRunCommitHref(runDocument)
        )
    }

    @Test
    fun pullRequestNotesRejectPlaceholderInsteadOfReadingLaterComments() {
        val prDocument = Jsoup.parse(
            """
            <div class="js-comment-body markdown-body">
                <p class="color-fg-muted"><em> No description provided. </em></p>
            </div>
            <div class="js-comment-body markdown-body"><p>自动审查评论，不是更新日志</p></div>
            """.trimIndent()
        )
        assertNull(GithubParser.pullRequestReleaseNotes(prDocument))
        assertNull(GithubParser.pullRequestReleaseNotes(Jsoup.parse("<div class='js-comment-body'></div>")))
        assertEquals(
            "更新内容",
            GithubParser.pullRequestReleaseNotes(Jsoup.parse("<div class='js-comment-body'><p>更新内容</p></div>"))
        )
    }

    @Test
    fun commitNotesPreserveTitleBodyAndNestedListIndentation() {
        val commitDocument = Jsoup.parse(
            """
            <div class="CommitHeader-module__commitMessageContainer__Nj8bH">
                <span class="ws-pre-wrap"><div>feat(reader): 统一正文排版</div></span>
                <span class="extended-commit-description-container">
                  - 统一段距
                    - 保留小节留白
                  - 修复文本裁切
                </span>
            </div>
            """.trimIndent()
        )
        assertEquals(
            "本次 CI 构建来源提交 `ded081d`: \n\nfeat(reader): 统一正文排版\n\n- 统一段距\n  - 保留小节留白\n- 修复文本裁切",
            GithubParser.commitReleaseNotes(
                commitDocument,
                "/vibub/LightNovelReader-enhanced/commit/ded081de679499ee005322463272c929444da03a"
            )
        )
    }

    @Test
    fun latestReleasePathUsesStableGithubEndpoint() {
        assertEquals(
            "/vibub/LightNovelReader-enhanced/releases/latest",
            GithubParser.latestReleasePathForTest()
        )
    }

    @Test
    fun rawGithubUrlCandidatesPreferDirectUrlBeforeProxy() {
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/vibub/LightNovelReader-enhanced/refs/heads/refactoring/app/build.gradle.kts",
                "https://gh-proxy.com/raw.githubusercontent.com/vibub/LightNovelReader-enhanced/refs/heads/refactoring/app/build.gradle.kts"
            ),
            GithubParser.rawGithubUrlCandidatesForTest(
                ref = "refs/heads/refactoring",
                path = "app/build.gradle.kts"
            )
        )
    }

    @Test
    fun githubAssetUrlCandidatesPreferDirectUrlBeforeProxy() {
        assertEquals(
            listOf(
                "https://github.com/vibub/LightNovelReader-enhanced/releases/download/v1.0/app-release.apk",
                "https://gh-proxy.com/github.com/vibub/LightNovelReader-enhanced/releases/download/v1.0/app-release.apk"
            ),
            GithubParser.githubAssetUrlCandidatesForTest(
                "/vibub/LightNovelReader-enhanced/releases/download/v1.0/app-release.apk"
            )
        )
    }
}

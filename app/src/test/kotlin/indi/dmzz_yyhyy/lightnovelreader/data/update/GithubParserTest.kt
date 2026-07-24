package indi.dmzz_yyhyy.lightnovelreader.data.update

import org.junit.Assert.assertEquals
import org.junit.Test

class GithubParserTest {
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

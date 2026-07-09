package indi.dmzz_yyhyy.lightnovelreader.utils.network

import kotlin.random.Random

object UserAgentGenerator {

    private val androidVersions = listOf(
        "7.0", "7.1.1", "8.0.0", "8.1.0",
        "9", "10", "11", "12", "13", "14"
    )

    private val deviceModels = listOf(
        "Pixel 4", "Pixel 5", "Pixel 6", "Pixel 7",
        "SM-G991B", "SM-G996B", "SM-S908B",
        "M2102J20SG", "2201123G"
    )

    private val desktopPlatforms = listOf(
        "Windows NT 10.0; Win64; x64",
        "Macintosh; Intel Mac OS X 10_15_7",
        "X11; Linux x86_64"
    )

    private val chromeMajorVersions = (100..140).toList()

    fun generate(): String {
        val androidVersion = androidVersions.random()
        val model = deviceModels.random()
        val buildId = randomBuildId()

        val chromeMajor = chromeMajorVersions.random()
        val chromeMinor = Random.nextInt(0, 4000)
        val chromeBuild = Random.nextInt(0, 200)
        val chromePatch = Random.nextInt(0, 150)

        return "Mozilla/5.0 (Linux; Android $androidVersion; $model Build/$buildId) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$chromeMajor.0.$chromeMinor.$chromeBuild Mobile Safari/537.$chromePatch"
    }

    fun generateDesktop(): String {
        val platform = desktopPlatforms.random()
        val chromeMajor = chromeMajorVersions.random()
        val chromeMinor = Random.nextInt(0, 4000)
        val chromeBuild = Random.nextInt(0, 200)

        return "Mozilla/5.0 ($platform) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$chromeMajor.0.$chromeMinor.$chromeBuild Safari/537.36"
    }

    private fun randomBuildId(): String {
        val chars = ('A'..'Z') + ('0'..'9')
        val length = listOf(6, 7, 8, 10).random()
        return (1..length).map { chars.random() }
            .joinToString("")
    }

}

package eu.darken.porter.manager.updater

import eu.darken.porter.manager.TestApplication
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class GithubUpdateCheckerTest {
    private val requested = mutableListOf<String>()

    private fun checker(
        installer: String? = null,
        override: String? = null,
        respond: (String) -> String = { error("No request expected for $it") },
    ) = GithubUpdateChecker(
        installer = { installer },
        debugOverride = { override },
        fetch = { url -> requested += url; respond(url) },
    )

    @Test fun theLatestReleaseOffersTheManagerApk() = runTest {
        val release = checker(respond = { LATEST }).latest(includePrereleases = false)
        assertEquals(listOf("https://api.github.com/repos/d4rken-org/porter/releases/latest"), requested)
        assertEquals(
            Release(
                tag = "v0.7.0-rc0",
                name = "Porter v0.7.0-rc0",
                changelogUrl = "https://github.com/d4rken-org/porter/releases/tag/v0.7.0-rc0",
                apk = Asset(
                    fileName = "porter-v0.7.0-rc0-release.apk",
                    url = "https://github.com/d4rken-org/porter/releases/download/v0.7.0-rc0/porter-v0.7.0-rc0-release.apk",
                    size = 4702924L,
                ),
            ),
            release,
        )
    }

    @Test fun aReleaseWithoutTheManagerApkOffersNone() = runTest {
        val withoutApk = LATEST.replace("\"porter-v0.7.0-rc0-release.apk\"", "\"porter-v0.7.0-rc0-debug.apk\"")
        val release = checker(respond = { withoutApk }).latest(includePrereleases = false)
        assertEquals("v0.7.0-rc0", release?.tag)
        assertNull(release?.apk)
    }

    @Test fun aMissingNameAndBodyStillParse() = runTest {
        val bare = """
            {"tag_name": "v0.7.0-rc0", "name": null, "body": null, "draft": false, "prerelease": false,
             "html_url": "https://github.com/d4rken-org/porter/releases/tag/v0.7.0-rc0", "assets": []}
        """.trimIndent()
        val release = checker(respond = { bare }).latest(includePrereleases = false)
        assertEquals("v0.7.0-rc0", release?.tag)
        assertNull(release?.name)
        assertNull(release?.apk)
    }

    @Test fun noLatestReleaseIsNoRelease() = runTest {
        val release = checker(respond = { url -> throw GithubUpdateChecker.HttpStatusException(404, url) })
            .latest(includePrereleases = false)
        assertNull(release)
    }

    @Test fun anotherHttpFailureIsAFailure() = runTest {
        val failure = runCatching {
            checker(respond = { url -> throw GithubUpdateChecker.HttpStatusException(503, url) })
                .latest(includePrereleases = false)
        }.exceptionOrNull()
        assertEquals(503, (failure as GithubUpdateChecker.HttpStatusException).code)
    }

    @Test fun prereleasesPickTheHighestVersionThatIsNoDraft() = runTest {
        val release = checker(respond = { LIST }).latest(includePrereleases = true)
        assertEquals(listOf("https://api.github.com/repos/d4rken-org/porter/releases?per_page=20"), requested)
        assertEquals("v0.7.1-beta0", release?.tag)
        assertEquals(
            "https://github.com/d4rken-org/porter/releases/download/v0.7.1-beta0/porter-v0.7.1-beta0-release.apk",
            release?.apk?.url,
        )
    }

    @Test fun aDebugOverrideAnswersWithoutFetching() = runTest {
        val release = checker(override = LATEST).latest(includePrereleases = true)
        assertTrue(requested.isEmpty())
        assertEquals("v0.7.0-rc0", release?.tag)
        assertEquals("porter-v0.7.0-rc0-release.apk", release?.apk?.fileName)
    }

    @Test fun storesThatUpdateAppsThemselvesTurnTheCheckOffByDefault() {
        assertFalse(checker(installer = "dev.imranr.obtainium").isEnabledByDefault())
        assertFalse(checker(installer = "org.fdroid.fdroid.privileged").isEnabledByDefault())
        assertFalse(checker(installer = "org.fdroid.basic").isEnabledByDefault())
        assertFalse(checker(installer = "org.fdroid.nightly").isEnabledByDefault())
        assertTrue(checker(installer = null).isEnabledByDefault())
        assertTrue(checker(installer = "com.android.shell").isEnabledByDefault())
    }

    private companion object {
        fun asset(tag: String, id: Int, name: String, size: Long) = """
            {
              "url": "https://api.github.com/repos/d4rken-org/porter/releases/assets/$id",
              "id": $id,
              "name": "$name",
              "label": "",
              "content_type": "application/octet-stream",
              "state": "uploaded",
              "size": $size,
              "download_count": 0,
              "created_at": "2026-09-01T10:00:00Z",
              "updated_at": "2026-09-01T10:00:00Z",
              "browser_download_url": "https://github.com/d4rken-org/porter/releases/download/$tag/$name"
            }
        """.trimIndent()

        fun release(tag: String, id: Int, draft: Boolean = false, prerelease: Boolean = false, assets: List<String>) = """
            {
              "url": "https://api.github.com/repos/d4rken-org/porter/releases/$id",
              "html_url": "https://github.com/d4rken-org/porter/releases/tag/$tag",
              "id": $id,
              "tag_name": "$tag",
              "target_commitish": "main",
              "name": "Porter $tag",
              "draft": $draft,
              "prerelease": $prerelease,
              "created_at": "2026-09-01T10:00:00Z",
              "published_at": "2026-09-01T10:05:00Z",
              "assets": [${assets.joinToString(",")}],
              "body": "## Changes\n- Something"
            }
        """.trimIndent()

        fun fullAssets(tag: String, id: Int) = listOf(
            asset(tag, id + 1, "porter-compat-$tag-release.apk", 1203344),
            asset(tag, id + 2, "porter-$tag-release.apk", 4702924),
            asset(tag, id + 3, "SHA256SUMS", 312),
            asset(tag, id + 4, "build-info.json", 845),
            asset(tag, id + 5, "verification.txt", 1290),
        )

        val LATEST = release("v0.7.0-rc0", 100, assets = fullAssets("v0.7.0-rc0", 100))

        val LIST = listOf(
            release("v0.7.0-rc0", 100, assets = fullAssets("v0.7.0-rc0", 100)),
            release("v0.8.0-rc0", 200, draft = true, assets = fullAssets("v0.8.0-rc0", 200)),
            release("v0.7.1-beta0", 300, prerelease = true, assets = fullAssets("v0.7.1-beta0", 300)),
            release("v0.6.9-rc4", 400, assets = fullAssets("v0.6.9-rc4", 400)),
        ).joinToString(",", prefix = "[", postfix = "]")
    }
}

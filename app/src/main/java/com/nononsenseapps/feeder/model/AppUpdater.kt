package com.nononsenseapps.feeder.model

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.nononsenseapps.feeder.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Checks the GitHub release named "latest" of the repository this build came from, and installs
 * the APK attached to it. The release notes contain the commit the APK was built from, which is
 * compared with the commit baked into this build.
 */
object AppUpdater {
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    private const val DOWNLOAD_HOST_PREFIX = "https://github.com/"
    private val client = OkHttpClient()
    private val commitRegex = Regex("[0-9a-f]{40}")

    sealed interface CheckResult {
        data object NotConfigured : CheckResult

        data object UpToDate : CheckResult

        data class Available(
            val apkUrl: String,
        ) : CheckResult

        data class Failed(
            val message: String,
        ) : CheckResult
    }

    suspend fun check(): CheckResult =
        withContext(Dispatchers.IO) {
            val repo = BuildConfig.UPDATE_REPO
            val installedCommit = BuildConfig.BUILD_COMMIT
            if (repo.isBlank() || installedCommit.isBlank()) {
                return@withContext CheckResult.NotConfigured
            }

            try {
                val request =
                    Request
                        .Builder()
                        .url("https://api.github.com/repos/$repo/releases/tags/latest")
                        .header("Accept", "application/vnd.github+json")
                        .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext CheckResult.Failed(
                            "HTTP ${response.code} (is the repository public and does the release exist?)",
                        )
                    }

                    val json = JSONObject(response.body.string())
                    val releaseCommit =
                        commitRegex.find(json.optString("body"))?.value
                            ?: return@withContext CheckResult.Failed("The release has no commit information")

                    if (releaseCommit == installedCommit) {
                        return@withContext CheckResult.UpToDate
                    }

                    val assets = json.optJSONArray("assets")
                    var apkUrl: String? = null
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            if (asset.optString("name").endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url")
                                break
                            }
                        }
                    }

                    if (apkUrl.isNullOrBlank()) {
                        CheckResult.Failed("The release has no APK file")
                    } else {
                        CheckResult.Available(apkUrl)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CheckResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }

    /**
     * Downloads the APK to the cache directory. [onProgress] is called with 0-100.
     */
    suspend fun download(
        context: Context,
        apkUrl: String,
        onProgress: (Int) -> Unit,
    ): File =
        withContext(Dispatchers.IO) {
            if (!apkUrl.startsWith(DOWNLOAD_HOST_PREFIX)) {
                throw IOException("Refusing to download from $apkUrl")
            }

            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(directory, "update.apk")
            target.delete()

            client.newCall(Request.Builder().url(apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}")
                }

                val body = response.body
                val total = body.contentLength()
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) {
                                break
                            }
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val percent = (downloaded * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
            }

            target
        }

    /**
     * Hands the APK to the system installer. Installing over the current app keeps all data,
     * as long as the APK is signed with the same key.
     *
     * @return false if the user first has to allow this app to install apps. The settings screen
     * for that permission is opened in that case.
     */
    fun install(
        context: Context,
        apk: File,
    ): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updateprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}

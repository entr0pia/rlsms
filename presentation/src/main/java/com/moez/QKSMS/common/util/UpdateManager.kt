package com.moez.QKSMS.common.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.moez.QKSMS.BuildConfig
import com.moez.QKSMS.R
import com.moez.QKSMS.common.widget.QkDialog
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateManager @Inject constructor(
    private val context: Context
) {

    data class UpdateInfo(
        val hasUpdate: Boolean,
        val latestTag: String,
        val releaseName: String,
        val changelog: String,
        val downloadUrl: String
    )

    fun checkForUpdate(): Single<UpdateInfo> {
        return Single.fromCallable {
            val endpoint = URL("https://api.github.com/repos/entr0pia/rlsms/releases/latest")
            val conn = endpoint.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "RLSMS-Android")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("GitHub API returned $responseCode")
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val response = reader.use { it.readText() }
            val json = JSONObject(response)

            val tagName = json.optString("tag_name", "").trim()
            val releaseName = json.optString("name", tagName)
            val body = json.optString("body", "").trim()
            val htmlUrl = json.optString("html_url", "https://github.com/entr0pia/rlsms/releases")
            var downloadUrl = htmlUrl

            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val assetName = asset.optString("name", "")
                    if (assetName.endsWith(".apk", ignoreCase = true)) {
                        downloadUrl = asset.optString("browser_download_url", htmlUrl)
                        break
                    }
                }
            }

            val hasUpdate = isNewerVersion(BuildConfig.VERSION_NAME, tagName)
            UpdateInfo(
                hasUpdate = hasUpdate,
                latestTag = tagName,
                releaseName = releaseName,
                changelog = body,
                downloadUrl = downloadUrl
            )
        }.subscribeOn(Schedulers.io())
    }

    fun showUpdateDialog(activity: Activity, updateInfo: UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return

        QkDialog(activity).apply {
            title = context.getString(R.string.about_update_new_version, updateInfo.latestTag)
            subtitle = if (updateInfo.changelog.isNotBlank()) updateInfo.changelog else null
            positiveButton = R.string.about_update_download
            positiveButtonListener = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.downloadUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            }
            negativeButton = R.string.about_update_later
        }.show()
    }

    fun showNoUpdateDialog(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return

        QkDialog(activity).apply {
            titleRes = R.string.about_check_update_title
            subtitle = context.getString(R.string.about_update_current_latest, BuildConfig.VERSION_NAME)
            positiveButton = android.R.string.ok
        }.show()
    }

    fun showCheckUpdateErrorDialog(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return

        QkDialog(activity).apply {
            titleRes = R.string.about_check_update_title
            subtitleRes = R.string.about_update_failed
            positiveButton = android.R.string.ok
        }.show()
    }

    private fun isNewerVersion(currentVersion: String, latestTag: String): Boolean {
        if (latestTag.isBlank()) return false
        val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V")
        val cleanLatest = latestTag.trim().removePrefix("v").removePrefix("V")
        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }
        val length = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until length) {
            val cur = currentParts.getOrElse(i) { 0 }
            val lat = latestParts.getOrElse(i) { 0 }
            if (lat > cur) return true
            if (lat < cur) return false
        }
        return false
    }
}

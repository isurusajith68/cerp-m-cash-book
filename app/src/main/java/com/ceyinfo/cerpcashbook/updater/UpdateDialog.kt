package com.ceyinfo.cerpcashbook.updater

import android.app.Activity
import com.ceyinfo.cerpcashbook.data.model.GitHubRelease
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object UpdateDialog {

    /**
     * Show update available dialog with Update / Skip / Later options.
     */
    fun show(activity: Activity, release: GitHubRelease, updater: AppUpdater) {
        val version = release.tagName.removePrefix("v")
        val releaseNotes = release.body?.take(500) ?: "Bug fixes and improvements."

        MaterialAlertDialogBuilder(activity)
            .setTitle("Update Available (v$version)")
            .setMessage("A new version of CERP Cash Book is available.\n\n$releaseNotes")
            .setPositiveButton("Update") { _, _ ->
                updater.downloadAndInstall(activity, release)
            }
            .setNegativeButton("Skip") { _, _ ->
                updater.skipVersion(release.tagName)
            }
            .setNeutralButton("Later", null)
            .setCancelable(true)
            .show()
    }
}

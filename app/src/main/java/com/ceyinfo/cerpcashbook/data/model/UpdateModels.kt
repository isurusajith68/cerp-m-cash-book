package com.ceyinfo.cerpcashbook.data.model

import com.google.gson.annotations.SerializedName

data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String,
    val name: String?,
    val body: String?,
    val assets: List<GitHubAsset>,
    @SerializedName("html_url") val htmlUrl: String,
    val prerelease: Boolean,
    val draft: Boolean
)

data class GitHubAsset(
    val name: String,
    @SerializedName("browser_download_url") val downloadUrl: String,
    val size: Long,
    @SerializedName("content_type") val contentType: String?
)

package com.v2ray.ang.data

data class UpdateInfo(
    val isForced: Boolean,
    val versionName: String,
    val releaseNotes: String,
    val updateUrl: String
)
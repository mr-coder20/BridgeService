package bah.saj.am.data

data class UpdateInfo(
    val isForced: Boolean,
    val versionName: String,
    val releaseNotes: String,
    val updateUrl: String
)
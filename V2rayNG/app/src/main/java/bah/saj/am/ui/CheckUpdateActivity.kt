package bah.saj.am.ui

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import bah.saj.am.AppConfig
import bah.saj.am.BuildConfig
import bah.saj.am.R
import bah.saj.am.databinding.ActivityCheckUpdateBinding
import bah.saj.am.dto.CheckUpdateResult
import bah.saj.am.extension.toast
import bah.saj.am.extension.toastError
import bah.saj.am.extension.toastSuccess
import bah.saj.am.handler.MmkvManager
import bah.saj.am.handler.SpeedtestManager
import bah.saj.am.handler.UpdateCheckerManager
import bah.saj.am.util.Utils
import kotlinx.coroutines.launch

class CheckUpdateActivity : BaseActivity() {

    private val binding by lazy { ActivityCheckUpdateBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        title = getString(R.string.update_check_for_update)

        binding.layoutCheckUpdate.setOnClickListener {
            checkForUpdates(binding.checkPreRelease.isChecked)
        }

        binding.checkPreRelease.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, isChecked)
        }
        binding.checkPreRelease.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false)

        "v${BuildConfig.VERSION_NAME} (${SpeedtestManager.getLibVersion()})".also {
            binding.tvVersion.text = it
        }

        checkForUpdates(binding.checkPreRelease.isChecked)
    }

    private fun checkForUpdates(includePreRelease: Boolean) {
        toast(R.string.update_checking_for_update)

        lifecycleScope.launch {
            try {
                val result = UpdateCheckerManager.checkForUpdate(includePreRelease)
                if (result.hasUpdate) {
                    showUpdateDialog(result)
                } else {
                    toastSuccess(R.string.update_already_latest_version)
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to check for updates: ${e.message}")
                toastError(e.message ?: getString(R.string.toast_failure))
            }
        }
    }

    private fun showUpdateDialog(result: CheckUpdateResult) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_new_version_found, result.latestVersion))
            .setMessage(result.releaseNotes)
            .setPositiveButton(R.string.update_now) { _, _ ->
                result.downloadUrl?.let {
                    Utils.openUri(this, it)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
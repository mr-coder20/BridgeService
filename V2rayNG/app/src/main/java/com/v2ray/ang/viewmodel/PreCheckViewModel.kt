package com.v2ray.ang.viewmodel

import android.util.Log // <--- وارد کردن کلاس Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.data.MikroTikRepository
import com.v2ray.ang.data.UpdateInfo
import com.v2ray.ang.util.Event
import kotlinx.coroutines.launch

class PreCheckViewModel : ViewModel() {

    private val repository = MikroTikRepository()

    private val _uiState = MutableLiveData<PreCheckUiState>()
    val uiState: LiveData<PreCheckUiState> = _uiState

    private val _events = MutableLiveData<Event<PreCheckEvent>>()
    val events: LiveData<Event<PreCheckEvent>> = _events

    init {
        startInitialChecks()
    }

    private fun startInitialChecks() {
        viewModelScope.launch {
            _uiState.value = PreCheckUiState(
                isNetworkCheckInProgress = true,
                statusMessage = "Checking Internet Connection..."
            )
            val networkStatus = repository.checkInternetConnectivity()
            _uiState.value = _uiState.value?.copy(localIp = "Local IP: ${networkStatus.localIp ?: "—"}")

            if (!networkStatus.isReachable) {
                _uiState.value = _uiState.value?.copy(
                    isNetworkCheckInProgress = false,
                    statusMessage = "Internet connection failed. Please check your network and restart the app."
                )
                return@launch
            }

            _uiState.value = _uiState.value?.copy(statusMessage = "Checking for updates...")
            val config = repository.checkForUpdates()

            // =================================================================
            // ==     مهم: اضافه کردن لاگ برای دیدن محتوای دریافت شده     ==
            // =================================================================
            if (config == null) {
                Log.d("UpdateCheck", "Update config is NULL. No update dialog will be shown.")
            } else {
                Log.d("UpdateCheck", "Update config received: $config")
            }
            // =================================================================

            if (config != null) {
                val currentVersionCode = BuildConfig.VERSION_CODE.toLong()
                Log.d("UpdateCheck", "Current app version code: $currentVersionCode") // لاگ نسخه فعلی

                if (currentVersionCode < config.minRequiredVersionCode) {
                    Log.d("UpdateCheck", "Condition for FORCED update met.")
                    _events.value = Event(PreCheckEvent.ShowUpdateDialog(
                        UpdateInfo(
                            isForced = true,
                            versionName = config.latestVersionName,
                            releaseNotes = config.releaseNotes,
                            updateUrl = config.updateUrl
                        )
                    ))
                    _uiState.value = _uiState.value?.copy(isNetworkCheckInProgress = false)
                    return@launch
                } else if (currentVersionCode < config.latestVersionCode) {
                    Log.d("UpdateCheck", "Condition for OPTIONAL update met.")
                    _events.value = Event(PreCheckEvent.ShowUpdateDialog(
                        UpdateInfo(
                            isForced = false,
                            versionName = config.latestVersionName,
                            releaseNotes = config.releaseNotes,
                            updateUrl = config.updateUrl
                        )
                    ))
                } else {
                    Log.d("UpdateCheck", "No update needed. Current version is up-to-date.")
                }
            }

            _uiState.value = _uiState.value?.copy(
                isNetworkCheckInProgress = false,
                statusMessage = "Please enter your credentials.",
                isLoginContainerVisible = true
            )
        }
    }

    // ... بقیه توابع ViewModel بدون تغییر باقی می‌مانند ...
    fun authenticate(user: String, pass: String) {
        if (user.isBlank() || pass.isBlank()) {
            _uiState.value = _uiState.value?.copy(statusMessage = "Username and password cannot be empty.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value?.copy(isLoginInProgress = true, statusMessage = "Authenticating...")
            val routerIp = repository.getMikrotikIp()
            val result = repository.authenticateSSH(routerIp, user, pass)
            _uiState.value = _uiState.value?.copy(isLoginInProgress = false, statusMessage = result.message)
            if (result.success) {
                _events.value = Event(PreCheckEvent.AuthenticationSuccess)
            }
        }
    }

    fun clearCredentials() {
        _events.value = Event(PreCheckEvent.ClearFields)
        _uiState.value = _uiState.value?.copy(statusMessage = "Please enter your credentials.")
    }

    fun onVpnPermissionGranted() {
        _events.value = Event(PreCheckEvent.NavigateToMain)
    }

    fun onVpnPermissionDenied() {
        _uiState.value = _uiState.value?.copy(statusMessage = "VPN permission is required for auto-connect.")
        _events.value = Event(PreCheckEvent.ShowToast("VPN permission denied."))
    }

    fun onNotificationPermissionGranted() {
        _events.value = Event(PreCheckEvent.RequestVpnPermission)
    }

    fun onNotificationPermissionDenied() {
        _uiState.value = _uiState.value?.copy(statusMessage = "Notification permission is required to show status.")
        _events.value = Event(PreCheckEvent.ShowToast("Notification permission denied."))
    }
}

// کلاس‌های دیتا و Event
data class PreCheckUiState(
    val isNetworkCheckInProgress: Boolean = false,
    val isLoginInProgress: Boolean = false,
    val statusMessage: String = "",
    val localIp: String = "",
    val isLoginContainerVisible: Boolean = false
)

sealed class PreCheckEvent {
    object AuthenticationSuccess : PreCheckEvent()
    object RequestVpnPermission : PreCheckEvent()
    object NavigateToMain : PreCheckEvent()
    object ClearFields : PreCheckEvent()
    data class ShowToast(val message: String) : PreCheckEvent()
    data class ShowUpdateDialog(val updateInfo: UpdateInfo) : PreCheckEvent()
}

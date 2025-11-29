package com.v2ray.ang.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.data.MikroTikRepository
import com.v2ray.ang.data.UpdateInfo
import com.v2ray.ang.util.Event
import kotlinx.coroutines.delay
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
            // 1. شروع بررسی اینترنت
            _uiState.value = PreCheckUiState(
                isNetworkCheckInProgress = true,
                statusMessage = "Checking Internet Connection..."
            )

            val networkStatus = repository.checkInternetConnectivity()
            _uiState.value = _uiState.value?.copy(localIp = "Local IP: ${networkStatus.localIp ?: "—"}")

            if (!networkStatus.isReachable) {
                _uiState.value = _uiState.value?.copy(
                    isNetworkCheckInProgress = false,
                    statusMessage = "Internet connection failed."
                )
                return@launch
            }

            // 2. شروع بررسی آپدیت
            _uiState.value = _uiState.value?.copy(statusMessage = "Checking for updates...")
            val config = networkStatus.config

            // دریافت نسخه فعلی برنامه
            val currentVersionCode = BuildConfig.VERSION_CODE.toLong()

            Log.d("UpdateCheck", "App Version: $currentVersionCode")

            if (config != null) {
                Log.d("UpdateCheck", "Remote Config -> Latest: ${config.latestVersionCode}, MinRequired: ${config.minRequiredVersionCode}")

                // منطق اصلی آپدیت
                if (currentVersionCode < config.minRequiredVersionCode) {
                    // --- آپدیت اجباری ---
                    Log.d("UpdateCheck", "Status: FORCED UPDATE REQUIRED")

                    _events.value = Event(PreCheckEvent.ShowUpdateDialog(
                        UpdateInfo(
                            isForced = true,
                            versionName = config.latestVersionName,
                            releaseNotes = config.releaseNotes,
                            updateUrl = config.updateUrl
                        )
                    ))

                    _uiState.value = _uiState.value?.copy(
                        isNetworkCheckInProgress = false,
                        statusMessage = "Update Required"
                    )
                    return@launch

                } else if (currentVersionCode < config.latestVersionCode) {
                    // --- آپدیت اختیاری ---
                    Log.d("UpdateCheck", "Status: OPTIONAL UPDATE AVAILABLE")

                    _events.value = Event(PreCheckEvent.ShowUpdateDialog(
                        UpdateInfo(
                            isForced = false,
                            versionName = config.latestVersionName,
                            releaseNotes = config.releaseNotes,
                            updateUrl = config.updateUrl
                        )
                    ))
                } else {
                    Log.d("UpdateCheck", "Status: NO UPDATE NEEDED")
                }
            } else {
                Log.w("UpdateCheck", "Config was NULL. Skipping update check.")
            }

            // 3. نمایش صفحه لاگین
            _uiState.value = _uiState.value?.copy(
                isNetworkCheckInProgress = false,
                statusMessage = "Please enter your credentials.",
                isLoginContainerVisible = true
            )
        }
    }

    fun authenticate(context: Context, user: String, pass: String) {
        if (user.isBlank() || pass.isBlank()) {
            _uiState.value = _uiState.value?.copy(statusMessage = "Username and password cannot be empty.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value?.copy(isLoginInProgress = true, statusMessage = "Authenticating via SSH...")

            // --- تغییر اصلی: استفاده از authenticateSsh ---
            val result = repository.authenticateSsh(context, user, pass)

            _uiState.value = _uiState.value?.copy(isLoginInProgress = false, statusMessage = result.message)

            if (result.success) {
                _events.value = Event(PreCheckEvent.AuthenticationSuccess)
            } else if (result.message == "CONFIG_MISSING") {
                // --- مدیریت حالت اضطراری (نبودن کانفیگ و آدرس) ---
                _events.value = Event(PreCheckEvent.ShowToast("Network configuration missing. Please try again later."))
                // کمی تاخیر تا کاربر پیام را ببیند
                delay(1500)
                _events.value = Event(PreCheckEvent.CloseApp)
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
    object CloseApp : PreCheckEvent() // <--- رویداد جدید برای بستن برنامه
    data class ShowToast(val message: String) : PreCheckEvent()
    data class ShowUpdateDialog(val updateInfo: UpdateInfo) : PreCheckEvent()
}

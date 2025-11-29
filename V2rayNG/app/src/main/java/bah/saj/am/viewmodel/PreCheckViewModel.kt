package bah.saj.am.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import bah.saj.am.BuildConfig
import bah.saj.am.data.MikroTikRepository
import bah.saj.am.data.UpdateInfo
import bah.saj.am.util.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PreCheckViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MikroTikRepository(application)

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

            val networkStatus = withContext(Dispatchers.IO) {
                repository.checkInternetConnectivity()
            }

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
            val currentVersionCode = BuildConfig.VERSION_CODE.toLong()

            if (config != null) {
                if (currentVersionCode < config.minRequiredVersionCode) {
                    _events.value = Event(PreCheckEvent.ShowUpdateDialog(
                        UpdateInfo(true, config.latestVersionName, config.releaseNotes, config.updateUrl)
                    ))
                    _uiState.value = _uiState.value?.copy(isNetworkCheckInProgress = false, statusMessage = "Update Required")
                    return@launch
                } else if (currentVersionCode < config.latestVersionCode) {
                    _events.value = Event(PreCheckEvent.ShowUpdateDialog(
                        UpdateInfo(false, config.latestVersionName, config.releaseNotes, config.updateUrl)
                    ))
                }
            }

            // 3. بررسی لاگین خودکار
            checkAutoLogin()
        }
    }

    private suspend fun checkAutoLogin() {
        val (savedUser, savedPass) = withContext(Dispatchers.IO) {
            repository.getSavedCredentials()
        }

        // *** تغییر جدید: دریافت وضعیت خروج دستی ***
        val isManualLogout = repository.isManualLogout()

        // شرط را تغییر دادیم: فقط اگر اطلاعات هست AND کاربر دستی خارج نشده است
        if (!savedUser.isNullOrEmpty() && !savedPass.isNullOrEmpty() && !isManualLogout) {
            _uiState.value = _uiState.value?.copy(
                isNetworkCheckInProgress = false,
                isLoginContainerVisible = false,
                isLoginInProgress = true,
                statusMessage = "Auto-logging in..."
            )

            val result = repository.authenticateSsh(getApplication(), savedUser, savedPass)

            if (result.success) {
                _uiState.value = _uiState.value?.copy(isLoginInProgress = false, statusMessage = "Connected & Secure.")
                _events.value = Event(PreCheckEvent.AuthenticationSuccess)
            } else {
                if (result.message.contains("Invalid username", ignoreCase = true)) {
                    withContext(Dispatchers.IO) {
                        repository.clearCredentials()
                    }
                    showLoginScreen("Auth failed: Password incorrect. Please login again.")
                } else {
                    showLoginScreen("Auto-login failed: ${result.message} (Tap Login to retry)")
                }
            }
        } else {
            // اگر کاربر دستی خارج شده بود یا دیتایی نبود
            val message = if (isManualLogout) "Logged out manually." else "Please enter your credentials."
            showLoginScreen(message)
        }
    }

    private fun showLoginScreen(message: String) {
        _uiState.value = _uiState.value?.copy(
            isNetworkCheckInProgress = false,
            isLoginInProgress = false,
            statusMessage = message,
            isLoginContainerVisible = true
        )
    }

    fun authenticate(user: String, pass: String) {
        if (user.isBlank() || pass.isBlank()) {
            _uiState.value = _uiState.value?.copy(statusMessage = "Username and password cannot be empty.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value?.copy(isLoginInProgress = true, statusMessage = "Authenticating...")
            val result = repository.authenticateSsh(getApplication(), user, pass)
            _uiState.value = _uiState.value?.copy(isLoginInProgress = false, statusMessage = result.message)

            if (result.success) {
                _events.value = Event(PreCheckEvent.AuthenticationSuccess)
            } else if (result.message == "CONFIG_MISSING") {
                _events.value = Event(PreCheckEvent.ShowToast("Network configuration missing."))
                delay(1500)
                _events.value = Event(PreCheckEvent.CloseApp)
            }
        }
    }

    // *** متد کمکی برای خروج (در صورتی که بخواهید از ویو مدل استفاده کنید) ***
    fun performLogout() {
        repository.setManualLogout(true)
        _events.value = Event(PreCheckEvent.NavigateToLogin)
    }

    // ---------------------------------------------------------------------------
    //  بخش انتقال یوزر (Export/Import Logic)
    // ---------------------------------------------------------------------------

    fun generateTransferToken() {
        viewModelScope.launch {
            val token = withContext(Dispatchers.IO) {
                repository.exportTransferToken()
            }

            if (token != null) {
                _events.value = Event(PreCheckEvent.ShowTokenDialog(token))
            } else {
                _events.value = Event(PreCheckEvent.ShowToast("No saved account found to export."))
            }
        }
    }

    fun importTransferToken(token: String) {
        if (token.isBlank()) return

        viewModelScope.launch {
            val creds = withContext(Dispatchers.IO) {
                repository.importTransferToken(token)
            }

            if (creds != null) {
                val (user, pass) = creds
                authenticate(user, pass)
            } else {
                _events.value = Event(PreCheckEvent.ShowToast("Invalid or corrupted Token!"))
            }
        }
    }

    // ---------------------------------------------------------------------------

    fun clearCredentials() {
        repository.clearCredentials()
        _events.value = Event(PreCheckEvent.ClearFields)
        _uiState.value = _uiState.value?.copy(statusMessage = "Credentials cleared.")
    }

    // Permissions Events
    fun onVpnPermissionGranted() {
        _events.value = Event(PreCheckEvent.NavigateToMain)
    }

    fun onVpnPermissionDenied() {
        _uiState.value = _uiState.value?.copy(statusMessage = "VPN permission is required.")
        _events.value = Event(PreCheckEvent.ShowToast("VPN permission denied."))
    }

    fun onNotificationPermissionGranted() {
        _events.value = Event(PreCheckEvent.RequestVpnPermission)
    }

    fun onNotificationPermissionDenied() {
        _uiState.value = _uiState.value?.copy(statusMessage = "Notification permission required.")
        _events.value = Event(PreCheckEvent.ShowToast("Notification permission denied."))
    }
}

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
    object NavigateToLogin : PreCheckEvent() // برای استفاده در خروج
    object ClearFields : PreCheckEvent()
    object CloseApp : PreCheckEvent()
    data class ShowToast(val message: String) : PreCheckEvent()
    data class ShowUpdateDialog(val updateInfo: UpdateInfo) : PreCheckEvent()

    // --- ایونت‌های انتقال یوزر ---
    data class ShowCredentialsDialog(val user: String, val pass: String) : PreCheckEvent()
    data class ShowQrCodeDialog(val qrContent: String) : PreCheckEvent()
    data class ShowTokenDialog(val token: String) : PreCheckEvent()
}

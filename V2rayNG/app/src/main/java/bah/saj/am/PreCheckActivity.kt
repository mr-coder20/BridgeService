package bah.saj.am

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import bah.saj.am.data.UpdateInfo
import bah.saj.am.ui.MainActivity
import bah.saj.am.viewmodel.PreCheckEvent
import bah.saj.am.viewmodel.PreCheckViewModel
import es.dmoral.toasty.Toasty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PreCheckActivity : AppCompatActivity() {

    private val viewModel: PreCheckViewModel by viewModels()

    // ویوهای اصلی
    private lateinit var edtUsername: EditText
    private lateinit var edtPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnClear: Button
    private lateinit var txtStatus: TextView
    private lateinit var progressCheckNetwork: ProgressBar
    private lateinit var progressLogin: ProgressBar
    private lateinit var loginContainer: View

    // ویوهای جدید برای انتقال اکانت
    private lateinit var btnShowToken: Button
    private lateinit var btnEnterToken: Button

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) viewModel.onVpnPermissionGranted() else viewModel.onVpnPermissionDenied()
        }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) viewModel.onNotificationPermissionGranted() else viewModel.onNotificationPermissionDenied()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // مطمئن شوید که نام فایل XML شما activity_pre_check است (طبق کدهای قبلی)
        setContentView(R.layout.activity_precheck)

        setupViews()
        setupListeners()
        observeViewModel()
    }

    private fun setupViews() {
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        edtUsername = findViewById(R.id.edtUsername)
        edtPassword = findViewById(R.id.edtPassword)
        btnLogin = findViewById(R.id.btnLogin)
        btnClear = findViewById(R.id.btnClear)
        txtStatus = findViewById(R.id.txtStatus)
        progressCheckNetwork = findViewById(R.id.progressCheckNetwork)
        progressLogin = findViewById(R.id.progressLogin)
        loginContainer = findViewById(R.id.loginContainer)

        // دکمه‌های جدید انتقال اکانت
        btnShowToken = findViewById(R.id.btnShowToken)
        btnEnterToken = findViewById(R.id.btnEnterToken)
    }

    private fun setupListeners() {
        // دکمه لاگین
        btnLogin.setOnClickListener {
            val username = edtUsername.text.toString().trim()
            val password = edtPassword.text.toString().trim()
            lifecycleScope.launch(Dispatchers.Main) {
                viewModel.authenticate(username, password)
            }
        }

        // دکمه پاک کردن دیتا
        btnClear.setOnClickListener {
            lifecycleScope.launch(Dispatchers.Main) {
                viewModel.clearCredentials()
            }
        }

        // دکمه نمایش توکن (Export) - برای دستگاه مبدا
        btnShowToken.setOnClickListener {
            lifecycleScope.launch(Dispatchers.Main) {
                viewModel.generateTransferToken()
            }
        }

        // دکمه وارد کردن توکن (Import) - برای دستگاه مقصد
        btnEnterToken.setOnClickListener {
            showEnterTokenDialog()
        }
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(this) { state ->
            progressCheckNetwork.isVisible = state.isNetworkCheckInProgress
            progressLogin.isVisible = state.isLoginInProgress
            loginContainer.isVisible = state.isLoginContainerVisible

            // غیرفعال کردن دکمه‌ها هنگام عملیات
            val controlsEnabled = !state.isLoginInProgress
            btnLogin.isEnabled = controlsEnabled
            btnClear.isEnabled = controlsEnabled
            edtUsername.isEnabled = controlsEnabled
            edtPassword.isEnabled = controlsEnabled
            btnShowToken.isEnabled = controlsEnabled
            btnEnterToken.isEnabled = controlsEnabled

            txtStatus.text = state.statusMessage
        }

        viewModel.events.observe(this) { event ->
            event.getContentIfNotHandled()?.let { eventContent ->
                when (eventContent) {
                    is PreCheckEvent.AuthenticationSuccess -> {
                        Toasty.success(this, "Login Successful!", Toast.LENGTH_SHORT).show()
                        checkPermissionsAndProceed()
                    }
                    is PreCheckEvent.RequestVpnPermission -> checkAndRequestVpnPermission()
                    is PreCheckEvent.NavigateToMain -> navigateToMainActivity()
                    is PreCheckEvent.ClearFields -> {
                        edtUsername.text.clear()
                        edtPassword.text.clear()
                        Toasty.info(this, "Credentials cleared.", Toast.LENGTH_SHORT).show()
                    }
                    is PreCheckEvent.ShowToast -> {
                        // استفاده از Toasty برای نمایش زیباتر ارورها یا پیام‌ها
                        if (eventContent.message.contains("fail", true) || eventContent.message.contains("error", true)) {
                            Toasty.error(this, eventContent.message, Toast.LENGTH_SHORT).show()
                        } else {
                            Toasty.info(this, eventContent.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                    is PreCheckEvent.ShowUpdateDialog -> showUpdateDialog(eventContent.updateInfo)
                    is PreCheckEvent.CloseApp -> finishAffinity()

                    // نمایش دیالوگ توکن خروجی
                    is PreCheckEvent.ShowTokenDialog -> showExportTokenDialog(eventContent.token)

                    // سایر ایونت‌ها اگر وجود داشت...
                    else -> {}
                }
            }
        }
    }

    // --- دیالوگ‌های انتقال اکانت ---

    private fun showExportTokenDialog(token: String) {
        val editText = EditText(this).apply {
            setText(token)
            setPadding(50, 50, 50, 50)
            textSize = 16f
            background = null
            isFocusable = false // فقط خواندنی
            isClickable = true
            setTextIsSelectable(true) // امکان کپی کردن متن
        }

        AlertDialog.Builder(this)
            .setTitle("Transfer Code")
            .setMessage("Enter this code on your TV or other device:")
            .setView(editText)
            .setPositiveButton("Copy Code") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("V2Ray Transfer Token", token)
                clipboard.setPrimaryClip(clip)
                Toasty.success(this, "Code copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showEnterTokenDialog() {
        val input = EditText(this).apply {
            hint = "Paste code here (starts with V2R-)"
            setPadding(50, 50, 50, 50)
            textSize = 16f
        }

        AlertDialog.Builder(this)
            .setTitle("Import Account")
            .setMessage("Enter the transfer code generated from your phone:")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val token = input.text.toString().trim()
                if (token.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        viewModel.importTransferToken(token)
                    }
                } else {
                    Toasty.warning(this, "Token cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- سایر متدها ---

    private fun showUpdateDialog(info: UpdateInfo) {
        if (isFinishing || isDestroyed) return

        val builder = AlertDialog.Builder(this)
            .setTitle("New Version Available (${info.versionName})")
            .setMessage(info.releaseNotes)
            .setCancelable(false)

        builder.setPositiveButton("Download") { _, _ ->
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.updateUrl))
                startActivity(intent)
                if (info.isForced) {
                    finishAffinity()
                }
            } catch (e: Exception) {
                Toasty.error(this, "Could not open update link.", Toast.LENGTH_SHORT).show()
            }
        }

        if (info.isForced) {
            builder.setTitle("Update Required")
            builder.setNegativeButton("Exit App") { dialog, _ ->
                dialog.dismiss()
                finishAffinity()
            }
        } else {
            builder.setNegativeButton("Later") { dialog, _ ->
                dialog.dismiss()
            }
        }

        builder.create().show()
    }

    private fun checkPermissionsAndProceed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            txtStatus.text = "Requesting notification permission..."
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            checkAndRequestVpnPermission()
        }
    }

    private fun checkAndRequestVpnPermission() {
        txtStatus.text = "Checking VPN permission..."
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            requestVpnPermission.launch(vpnIntent)
        } else {
            viewModel.onVpnPermissionGranted()
        }
    }

    private fun navigateToMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("AUTO_CONNECT_RADIUS", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        finish()
    }
}

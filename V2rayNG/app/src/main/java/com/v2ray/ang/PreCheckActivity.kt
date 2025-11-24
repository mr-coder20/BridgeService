package com.v2ray.ang

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
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
import com.v2ray.ang.data.UpdateInfo // <-- اصلاح ۱: وارد کردن کلاس صحیح
import com.v2ray.ang.ui.MainActivity
import com.v2ray.ang.util.Event
import com.v2ray.ang.viewmodel.PreCheckEvent
import com.v2ray.ang.viewmodel.PreCheckViewModel

class PreCheckActivity : AppCompatActivity() {

    private val viewModel: PreCheckViewModel by viewModels()

    private lateinit var edtUsername: EditText
    private lateinit var edtPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnClear: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtLocalIp: TextView
    private lateinit var progressCheckNetwork: ProgressBar
    private lateinit var progressLogin: ProgressBar
    private lateinit var loginContainer: android.view.View

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
        setContentView(R.layout.activity_precheck)
        setupViews()
        setupListeners()
        observeViewModel()
    }

    private fun setupViews() {
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "Login"
        edtUsername = findViewById(R.id.edtUsername)
        edtPassword = findViewById(R.id.edtPassword)
        btnLogin = findViewById(R.id.btnLogin)
        btnClear = findViewById(R.id.btnClear)
        txtStatus = findViewById(R.id.txtStatus)
        progressCheckNetwork = findViewById(R.id.progressCheckNetwork)
        progressLogin = findViewById(R.id.progressLogin)
        loginContainer = findViewById(R.id.loginContainer)
    }

    private fun setupListeners() {
        btnLogin.setOnClickListener {
            viewModel.authenticate(
                this, // <-- اصلاح: اضافه کردن context
                edtUsername.text.toString(),
                edtPassword.text.toString()
            )
        }
        btnClear.setOnClickListener { viewModel.clearCredentials() }
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(this) { state ->
            progressCheckNetwork.isVisible = state.isNetworkCheckInProgress
            progressLogin.isVisible = state.isLoginInProgress
            loginContainer.isVisible = state.isLoginContainerVisible

            val loginEnabled = !state.isLoginInProgress
            btnLogin.isEnabled = loginEnabled
            btnClear.isEnabled = loginEnabled
            edtUsername.isEnabled = loginEnabled
            edtPassword.isEnabled = loginEnabled

            txtStatus.text = state.statusMessage
        }

        viewModel.events.observe(this) { event ->
            event.getContentIfNotHandled()?.let { eventContent ->
                when (eventContent) {
                    is PreCheckEvent.AuthenticationSuccess -> checkPermissionsAndProceed()
                    is PreCheckEvent.RequestVpnPermission -> checkAndRequestVpnPermission()
                    is PreCheckEvent.NavigateToMain -> navigateToMainActivity()
                    is PreCheckEvent.ClearFields -> {
                        edtUsername.text.clear()
                        edtPassword.text.clear()
                    }
                    is PreCheckEvent.ShowToast -> {
                        Toast.makeText(this, eventContent.message, Toast.LENGTH_SHORT).show()
                    }
                    is PreCheckEvent.ShowUpdateDialog -> showUpdateDialog(eventContent.updateInfo) // <-- اصلاح ۳: استفاده از نام متغیر صحیح
                }
            }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        // جلوگیری از کرش در صورت بسته بودن اکتیویتی
        if (isFinishing || isDestroyed) return

        val builder = AlertDialog.Builder(this)
            .setTitle("New Version Available (${info.versionName})")
            .setMessage(info.releaseNotes)
            .setCancelable(false) // جلوگیری از بستن با کلیک بیرون دیالوگ

        // دکمه دانلود برای هر دو حالت مشترک است
        builder.setPositiveButton("Download") { _, _ ->
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.updateUrl))
                startActivity(intent)
                // اگر اجباری باشد، بعد از زدن دانلود هم برنامه را می‌بندیم تا کاربر تا آپدیت نکند نتواند استفاده کند
                if (info.isForced) {
                    finishAffinity()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open link.", Toast.LENGTH_SHORT).show()
            }
        }

        if (info.isForced) {
            // --- حالت اجباری ---
            builder.setTitle("Update Required (${info.versionName})")
            builder.setMessage("A mandatory update is required to continue.\n\nChanges:\n${info.releaseNotes}")

            // دکمه خروج کامل از برنامه
            builder.setNegativeButton("Exit App") { dialog, _ ->
                dialog.dismiss()
                finishAffinity() // بستن کامل برنامه و همه اکتیویتی‌ها
            }
        } else {
            // --- حالت اختیاری ---
            builder.setMessage("A new version is available.\n\nChanges:\n${info.releaseNotes}")

            // دکمه ادامه دادن بدون آپدیت
            builder.setNegativeButton("Continue / Later") { dialog, _ ->
                dialog.dismiss()
                // کاربر می‌تواند با فرم لاگین کار کند
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
        txtStatus.text = "Permissions granted. Redirecting..."
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("AUTO_CONNECT_RADIUS", true)
        }
        startActivity(intent)
        finish()
    }
}
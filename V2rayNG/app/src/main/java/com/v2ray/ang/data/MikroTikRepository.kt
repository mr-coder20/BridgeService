package com.v2ray.ang.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections
import kotlin.coroutines.resume

// پیاده‌سازی ساده CookieJar برای حفظ cookieها
class SimpleCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        this.cookies.addAll(cookies)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return cookies.filter { it.matches(url) }
    }
}

data class NetworkStatus(
    val isReachable: Boolean, val pingStatus: String, val localIp: String?
)

data class AuthResult(
    val success: Boolean, val message: String
)

data class UpdateConfig(
    val latestVersionCode: Long,
    val latestVersionName: String,
    val minRequiredVersionCode: Long,
    val minRequiredVersionName: String,
    val updateUrl: String,
    val releaseNotes: String
)

class MikroTikRepository {

    suspend fun checkInternetConnectivity(): NetworkStatus = withContext(Dispatchers.IO) {
        val primaryUrl = "http://bmh7.iddns.ir:8090/"
        val fallbackUrl = "http://467a04770f3e.sn.mynetname.net:8090/"
        val timeoutMs = 4000

        var isReachable = isUrlReachable(primaryUrl, timeoutMs)
        val connectionStatus: String

        if (isReachable) {
            connectionStatus = "OK (bmh7.iddns.ir)"
        } else {
            Log.w("NetworkCheck", "Primary URL failed. Trying fallback.")
            isReachable = isUrlReachable(fallbackUrl, timeoutMs)
            connectionStatus =
                if (isReachable) "Fallback OK" else "Connection Failed"
        }

        NetworkStatus(
            isReachable = isReachable, pingStatus = connectionStatus, localIp = getLocalIpAddress()
        )
    }

    private fun isUrlReachable(urlString: String, timeout: Int): Boolean {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.useCaches = false
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            false
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces: List<NetworkInterface> =
                Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs: List<InetAddress> = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress.indexOf(':') < 0) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (ex: Exception) { /* Ignore */ }
        return null
    }

    fun getMikrotikIp(): String {
        return "192.168.162.20"
    }

    suspend fun authenticateWebView(context: Context, user: String, pass: String): AuthResult =
        withContext(Dispatchers.Main) {
            val primaryUrl = "http://bmh7.iddns.ir:8090/"
            val fallbackUrl = "http://467a04770f3e.sn.mynetname.net:8090/"

            // افزایش تایم اوت کلی چون باید صبر کنیم تا صفحه پنل و ترافیک لود شود
            val processTimeoutMs = 25000

            val urlToUse = if (withContext(Dispatchers.IO) { isUrlReachable(primaryUrl, 2000) }) {
                primaryUrl
            } else {
                fallbackUrl
            }

            tryWebViewAuth(context, urlToUse, user, pass, processTimeoutMs) ?: AuthResult(
                false,
                "Authentication failed (Unknown Error)"
            )
        }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private suspend fun tryWebViewAuth(
        context: Context,
        targetUrl: String,
        user: String,
        pass: String,
        timeout: Int
    ): AuthResult? = suspendCancellableCoroutine { continuation ->
        Log.d("AuthWebView", "Starting strict auth for: $targetUrl")

        val webView = WebView(context.applicationContext)
        // وب‌ویو مخفی (اندازه کوچک)
        webView.layout(0, 0, 1, 1)
        webView.visibility = View.INVISIBLE

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true

            // بهینه‌سازی سرعت: عدم بارگذاری تصاویر
            loadsImagesAutomatically = false
            blockNetworkImage = true

            // غیرفعال کردن کش برای جلوگیری از ذخیره لاگین قبلی
            cacheMode = WebSettings.LOAD_NO_CACHE

            allowFileAccess = false
            allowContentAccess = false

            // غیرفعال کردن ذخیره پسورد
            saveFormData = false
            savePassword = false

            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }

        // --- تابع برای پاکسازی کامل کش و کوکی‌ها ---
        fun clearAllWebViewData() {
            try {
                val cookieManager = CookieManager.getInstance()
                cookieManager.removeAllCookies(null)
                cookieManager.flush()

                webView.clearCache(true)
                webView.clearFormData()
                webView.clearHistory()
                webView.clearMatches()

                // پاک کردن Local Storage و Session Storage (بسیار مهم برای لاگ‌اوت کامل)
                WebStorage.getInstance().deleteAllData()

                Log.d("AuthWebView", "All WebView data cleared (Cache, Cookies, Storage).")
            } catch (e: Exception) {
                Log.e("AuthWebView", "Error clearing WebView data: ${e.message}")
            }
        }

        // پاکسازی قبل از شروع
        clearAllWebViewData()

        val handler = Handler(Looper.getMainLooper())
        var statusCheckRunnable: Runnable? = null
        var isCompleted = false
        var autoLoginAttempted = false

        // نابود کردن ایمن WebView
        fun destroyWebView() {
            try {
                handler.removeCallbacksAndMessages(null)
                webView.stopLoading()
                // پاکسازی مجدد در پایان کار برای اطمینان از لاگین تمیز بعدی
                clearAllWebViewData()
                webView.destroy()
            } catch (e: Exception) {
                Log.e("AuthWebView", "Error destroying WebView: ${e.message}")
            }
        }

        fun finishAuth(result: AuthResult) {
            if (!isCompleted) {
                isCompleted = true
                statusCheckRunnable?.let { handler.removeCallbacks(it) }

                if (continuation.isActive) {
                    continuation.resume(result)
                }
                // کمی تاخیر قبل از نابودی وب‌ویو
                handler.postDelayed({ destroyWebView() }, 500)
            }
        }

        // توقف بررسی وضعیت
        fun stopStatusCheck() {
            statusCheckRunnable?.let { handler.removeCallbacks(it) }
            statusCheckRunnable = null
        }

        val webAppInterface = object : Any() {
            @JavascriptInterface
            fun onLoginError(errorMsg: String) {
                // نادیده گرفتن خطاهای لودینگ معمولی
                if (errorMsg.contains("loading", true)) return

                Log.e("AuthWebView", "JS Login Error detected: $errorMsg")
                // اگر خطا دیدیم، یعنی عملیات ناموفق بوده -> توقف بررسی وضعیت و اعلام شکست
                stopStatusCheck()
                finishAuth(AuthResult(false, "Login Error: $errorMsg"))
            }

            @JavascriptInterface
            fun onLoginSuccess() {
                Log.d("AuthWebView", "JS Login Success (Traffic Element Verified)")
                stopStatusCheck()
                finishAuth(AuthResult(true, "Login Successful"))
            }
        }
        webView.addJavascriptInterface(webAppInterface, "Android")

        // اسکریپت 1: فقط و فقط برای بررسی خطا
        fun injectErrorCheckScript() {
            val jsCode = """
                (function() {
                    var errorDiv = document.getElementById('error');
                    
                    // بررسی آنی
                    if (errorDiv && errorDiv.innerText.trim().length > 0) {
                        Android.onLoginError(errorDiv.innerText);
                    }
                    
                    // بررسی در صورت تغییر (AJAX)
                    if(errorDiv) {
                        var observer = new MutationObserver(function(mutations) {
                            if (errorDiv.innerText.trim().length > 0) {
                                Android.onLoginError(errorDiv.innerText);
                            }
                        });
                        observer.observe(errorDiv, { childList: true, characterData: true, subtree: true });
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(jsCode, null)
        }

        // اسکریپت 2: لاگین خودکار
        fun performAutoLogin() {
            val jsLoginCode = """
                (function() {
                    var userField = document.getElementById('name');
                    var passField = document.getElementById('password');
                    var loginForm = document.getElementById('login');
                    
                    if (userField && passField && loginForm) {
                        userField.value = '$user';
                        passField.value = '$pass';
                        
                        // تریگر کردن ایونت‌ها برای فریم‌ورک‌های JS
                        userField.dispatchEvent(new Event('input'));
                        passField.dispatchEvent(new Event('input'));
                        userField.dispatchEvent(new Event('change'));
                        passField.dispatchEvent(new Event('change'));

                        var submitBtn = loginForm.querySelector('input[type="submit"]');
                        if (submitBtn) {
                            submitBtn.click();
                        } else {
                            loginForm.submit();
                        }
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(jsLoginCode, null)
        }

        // اسکریپت 3: بررسی موفقیت (فقط بر اساس وجود المنت خاص)
        fun startStatusCheck() {
            stopStatusCheck()
            statusCheckRunnable = object : Runnable {
                override fun run() {
                    if (isCompleted) return

                    val jsCheck = """
                        (function() { 
                            var statusDiv = document.getElementById('status');
                            
                            // 1. اگر اصلاً وجود ندارد -> هنوز لاگین نشده
                            if (!statusDiv) return false;

                            // 2. اگر کلاس 'traffic' ندارد -> احتمالاً مربوط به پنل نیست
                            if (!statusDiv.classList.contains('traffic')) return false;

                            // 3. اگر مخفی است -> هنوز کامل لود نشده
                            if (statusDiv.offsetParent === null) return false;

                            return true; 
                        })();
                    """.trimIndent()

                    webView.evaluateJavascript(jsCheck) { result ->
                        if (result == "true") {
                            // المنت ترافیک پیدا شد -> موفقیت قطعی
                            webAppInterface.onLoginSuccess()
                        } else {
                            // هنوز پیدا نشده، دوباره چک کن
                            if (!isCompleted) {
                                handler.postDelayed(this, 1000)
                            }
                        }
                    }
                }
            }
            // اولین اجرا با کمی تاخیر
            handler.postDelayed(statusCheckRunnable!!, 1000)
        }

        fun normalizeUrl(url: String): String {
            return if (url.endsWith("/")) url.dropLast(1) else url
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // اجازه لود شدن همه چیز را می‌دهیم، ما فقط ناظر هستیم
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // با شروع لود هر صفحه جدید، بررسی‌های قبلی متوقف می‌شود تا تداخل پیش نیاید
                stopStatusCheck()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (isCompleted) return

                val currentUrl = normalizeUrl(url ?: "")
                val baseUrl = normalizeUrl(targetUrl)

                // 1. اگر در صفحه لاگین هستیم، لاگین خودکار را انجام بده
                if ((currentUrl == baseUrl || currentUrl.contains("login.html")) && !autoLoginAttempted) {
                    autoLoginAttempted = true
                    handler.postDelayed({
                        performAutoLogin()
                    }, 500)
                }

                // نکته مهم: در اینجا هیچ شرطی برای اعلام موفقیت بر اساس URL وجود ندارد.
                // حتی اگر URL به webfig تغییر کرده باشد، ما صبر می‌کنیم تا startStatusCheck
                // المنت ترافیک را پیدا کند.

                // 2. مانیتور کردن خطا
                injectErrorCheckScript()

                // 3. شروع سیکل چک کردن المنت موفقیت
                startStatusCheck()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    Log.w("AuthWebView", "Main Frame Error: ${error?.description}")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }
        }

        // تایم‌اوت نهایی (اگر در مدت زمان مشخص نه لاگین شد نه خطا داد)
        handler.postDelayed({
            if (!isCompleted) {
                finishAuth(AuthResult(false, "Timeout: Login verification took too long"))
            }
        }, timeout.toLong())

        // شروع بارگذاری
        webView.loadUrl(targetUrl)

        continuation.invokeOnCancellation {
            finishAuth(AuthResult(false, "Cancelled"))
        }
    }

    suspend fun checkForUpdates(): UpdateConfig? {
        return withContext(Dispatchers.IO) {
            try {
                val gistUrl =
                    "https://gist.githubusercontent.com/mr-coder20/33e88c9783202c52b7654886b2619147/raw"
                val urlString = "$gistUrl?t=${System.currentTimeMillis()}"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.useCaches = false
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val text = connection.inputStream.bufferedReader().use { it.readText() }
                    try {
                        Gson().fromJson(text, UpdateConfig::class.java)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

package com.v2ray.ang.data

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections

// مدل داده جامع برای دریافت تنظیمات از سرور
data class AppConfig(
    val latestVersionCode: Long,
    val latestVersionName: String,
    val minRequiredVersionCode: Long,
    val updateUrl: String,
    val releaseNotes: String,
    val routerIps: List<String>? = null,
    val configList: List<String>? = null
)

// وضعیت شبکه + تنظیمات دریافت شده
data class NetworkStatus(
    val isReachable: Boolean,
    val pingStatus: String,
    val localIp: String?,
    val config: AppConfig?
)

// کلاس کمکی برای نتیجه دانلود
data class FetchResult(
    val config: AppConfig?,
    val isNetworkSuccessful: Boolean // آیا اتصال به سرور موفق بود؟ (صرف نظر از محتوای فایل)
)

class NetworkConnectivityChecker {

    private val gson = Gson()

    /**
     * بررسی اتصال اینترنت با تلاش برای دانلود تنظیمات.
     */
    suspend fun checkInternetConnectivity(): NetworkStatus = withContext(Dispatchers.IO) {
        // اولویت‌ها
        val primaryUrl = "https://gitlab.com/-/snippets/4908237/raw/main/raw"
        val fallbackUrl = "https://gist.githubusercontent.com/mr-coder20/33e88c9783202c52b7654886b2619147/raw"

        var finalConfig: AppConfig? = null
        var statusMessage = ""
        var atLeastOneServerReached = false // فلگ برای بررسی اینکه آیا اصلا به سرور وصل شدیم یا نه

        // تابع کمکی برای بررسی اعتبار کانفیگ
        fun isValidConfig(config: AppConfig?): Boolean {
            return config != null &&
                    !config.configList.isNullOrEmpty() &&
                    !config.routerIps.isNullOrEmpty()
        }

        // تلاش ۱: لینک اصلی (GitLab)
        val result1 = fetchJsonFromUrl(primaryUrl, connectTimeout = 5000, readTimeout = 5000)
        if (result1.isNetworkSuccessful) atLeastOneServerReached = true

        if (isValidConfig(result1.config)) {
            finalConfig = result1.config
            statusMessage = "Connected (Via GitLab)"
            Log.i("NetworkCheck", "Valid config loaded from GitLab.")
        } else {
            Log.w("NetworkCheck", "GitLab failed or config invalid. Trying Gist...")

            // تلاش ۲: لینک پشتیبان (Gist)
            val result2 = fetchJsonFromUrl(fallbackUrl, connectTimeout = 5000, readTimeout = 5000)
            if (result2.isNetworkSuccessful) atLeastOneServerReached = true

            if (isValidConfig(result2.config)) {
                finalConfig = result2.config
                statusMessage = "Connected (Via Gist)"
                Log.i("NetworkCheck", "Valid config loaded from Gist.")
            }
        }

        // تصمیم‌گیری نهایی برای پیام خطا
        if (finalConfig == null) {
            statusMessage = if (atLeastOneServerReached) {
                // به اینترنت وصل شدیم ولی جیسون ناقص بود (کانفیگ یا روتر نداشت)
                "سرور درحال بروز رسانی است، چند دقیقه دیگر تلاش کنید"
            } else {
                // کلا نتوانستیم به هیچ سروری وصل شویم
                "شبکه در دسترس نیست"
            }
        }

        NetworkStatus(
            isReachable = finalConfig != null,
            pingStatus = statusMessage,
            localIp = getLocalIpAddress(),
            config = finalConfig
        )
    }

    /**
     * دانلود و پارس کردن JSON
     * خروجی: یک آبجکت FetchResult که شامل کانفیگ (در صورت موفقیت) و وضعیت شبکه است.
     */
    private fun fetchJsonFromUrl(urlString: String, connectTimeout: Int, readTimeout: Int): FetchResult {
        return try {
            // اضافه کردن پارامتر زمان برای جلوگیری از کش شدن
            val urlWithNoCache = "$urlString?t=${System.currentTimeMillis()}"
            val url = URL(urlWithNoCache)
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "GET"
                this.connectTimeout = connectTimeout
                this.readTimeout = readTimeout
                useCaches = false // جلوگیری از کش
                defaultUseCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Connection", "close")
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // اتصال موفق بوده است (Network Successful = true)
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                try {
                    val config = gson.fromJson(jsonText, AppConfig::class.java)
                    FetchResult(config, isNetworkSuccessful = true)
                } catch (e: Exception) {
                    // جیسون خراب است اما شبکه وصل بوده
                    Log.e("NetworkCheck", "JSON parsing error: ${e.message}")
                    FetchResult(null, isNetworkSuccessful = true)
                }
            } else {
                Log.w("NetworkCheck", "Failed to connect to $urlString. Response Code: $responseCode")
                connection.disconnect()
                // سرور پاسخ داد اما 200 نبود (مثلا 404 یا 500) -> باز هم شبکه وصل بوده اما نتیجه نگرفتیم
                // اگر بخواهید ارورهای 404 را هم به عنوان "شبکه در دسترس نیست" در نظر بگیرید، اینجا را false کنید.
                // اما معمولاً کد غیر 200 یعنی سرور هست ولی فایل نیست (پس سرور در حال آپدیت است).
                FetchResult(null, isNetworkSuccessful = true)
            }
        } catch (e: Exception) {
            Log.e("NetworkCheck", "Error fetching from $urlString: ${e.message}")
            // خطا در اتصال (تایم اوت یا قطعی نت)
            FetchResult(null, isNetworkSuccessful = false)
        }
    }

    // توابع کمکی دیگر
    fun isUrlReachable(urlString: String, timeout: Int): Boolean {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.useCaches = false
            val code = connection.responseCode
            connection.disconnect()
            code == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            false
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress.indexOf(':') < 0) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (ex: Exception) { /* Ignore */ }
        return null
    }
}

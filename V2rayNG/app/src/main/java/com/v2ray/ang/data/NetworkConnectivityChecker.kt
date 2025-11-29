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
    // تغییر: به جای یک رشته، حالا لیستی از آدرس‌های روتر داریم
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

class NetworkConnectivityChecker {

    private val gson = Gson()

    /**
     * بررسی اتصال اینترنت با تلاش برای دانلود تنظیمات.
     * شرط موفقیت: دانلود موفقیت‌آمیز JSON + وجود داشتن لیست کانفیگ و لیست روترها در آن.
     */
    suspend fun checkInternetConnectivity(): NetworkStatus = withContext(Dispatchers.IO) {
        // اولویت‌ها (گیت‌لب اول چون در شبکه شما بهتر جواب داد)
        val primaryUrl = "https://gitlab.com/-/snippets/4908237/raw/main/raw"
        val fallbackUrl = "https://gist.githubusercontent.com/mr-coder20/33e88c9783202c52b7654886b2619147/raw"

        var fetchedConfig: AppConfig? = null
        var connectionStatus = "Connection Failed"

        // تابع کمکی برای بررسی اینکه آیا جیسون معتبر است یا خیر
        fun isValidConfig(config: AppConfig?): Boolean {
            return config != null &&
                    !config.configList.isNullOrEmpty() && // لیست کانفیگ نباید خالی باشد
                    !config.routerIps.isNullOrEmpty()     // لیست روتر نباید خالی باشد
        }

        // تلاش ۱: لینک اصلی (GitLab)
        val tempConfig1 = fetchJsonFromUrl(primaryUrl)
        if (isValidConfig(tempConfig1)) {
            fetchedConfig = tempConfig1
            connectionStatus = "Connected (Via GitLab)"
            Log.i("NetworkCheck", "Valid config loaded from GitLab.")
        } else {
            Log.w("NetworkCheck", "GitLab failed or config was invalid/empty. Trying Gist...")

            // تلاش ۲: لینک پشتیبان (Gist)
            val tempConfig2 = fetchJsonFromUrl(fallbackUrl)
            if (isValidConfig(tempConfig2)) {
                fetchedConfig = tempConfig2
                connectionStatus = "Connected (Via Gist)"
                Log.i("NetworkCheck", "Valid config loaded from Gist.")
            } else {
                Log.e("NetworkCheck", "Both URLs failed or returned invalid config.")
            }
        }

        NetworkStatus(
            isReachable = fetchedConfig != null, // فقط اگر کانفیگ معتبر گرفتیم true می‌شود
            pingStatus = connectionStatus,
            localIp = getLocalIpAddress(),
            config = fetchedConfig
        )
    }

    /**
     * دانلود و پارس کردن JSON از یک لینک مشخص
     */
    private fun fetchJsonFromUrl(urlString: String): AppConfig? {
        return try {
            val urlWithNoCache = "$urlString?t=${System.currentTimeMillis()}"
            val url = URL(urlWithNoCache)
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Connection", "close")
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                // اگر جیسون ناقص باشد Gson نال برمی‌گرداند یا کرش نمی‌کند، اما در تابع بالا بررسی می‌کنیم که لیست‌ها پر باشند
                gson.fromJson(jsonText, AppConfig::class.java)
            } else {
                Log.w("NetworkCheck", "Failed to connect to $urlString. Response Code: $responseCode")
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            Log.e("NetworkCheck", "Error fetching from $urlString: ${e.message}")
            null
        }
    }

    // توابع کمکی دیگر بدون تغییر...
    fun isUrlReachable(urlString: String, timeout: Int): Boolean {
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

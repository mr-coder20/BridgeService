package bah.saj.am.data

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.tencent.mmkv.MMKV // اضافه شد برای مدیریت حافظه
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
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

    @SerializedName("ri")
    val routerIps: List<String>? = null,

    @SerializedName("cl")
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
    val isNetworkSuccessful: Boolean
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
        var atLeastOneServerReached = false

        // تابع کمکی برای بررسی اعتبار کانفیگ (حداقل یکی از لیست‌ها باید باشد)
        fun isValidConfig(config: AppConfig?): Boolean {
            return config != null &&
                    (!config.configList.isNullOrEmpty() || !config.routerIps.isNullOrEmpty())
        }

        // تلاش ۱: لینک اصلی
        val result1 = fetchJsonFromUrl(primaryUrl, connectTimeout = 5000, readTimeout = 5000)
        if (result1.isNetworkSuccessful) atLeastOneServerReached = true

        if (isValidConfig(result1.config)) {
            finalConfig = result1.config
            statusMessage = "Connected (Via GitLab)"
            Log.i("NetworkCheck", "Valid config loaded from GitLab.")
        } else {
            Log.w("NetworkCheck", "GitLab failed or config invalid. Trying Gist...")

            // تلاش ۲: لینک پشتیبان
            val result2 = fetchJsonFromUrl(fallbackUrl, connectTimeout = 5000, readTimeout = 5000)
            if (result2.isNetworkSuccessful) atLeastOneServerReached = true

            if (isValidConfig(result2.config)) {
                finalConfig = result2.config
                statusMessage = "Connected (Via Gist)"
                Log.i("NetworkCheck", "Valid config loaded from Gist.")
            }
        }

        // -----------------------------------------------------------------------
        // شروع منطق مدیریت Router IP و Config List طبق دستور شما
        // -----------------------------------------------------------------------
        if (finalConfig != null) {
            val kv = MMKV.defaultMMKV()

            // --- بخش 1: مدیریت Router IPs (ri) ---
            // قانون: اگر جدید بود اضافه کن، اگر تعداد > 2 شد، قدیمی را پاک کن.
            // --- بخش 1: مدیریت Router IPs (ri) ---
            // --- بخش 1: مدیریت Router IPs (ri) ---
            val newIpsFromServer = finalConfig.routerIps ?: emptyList()

            // حتی اگر لیست سرور خالی باشد، باید چک کنیم (اما معمولا وقتی جیسون آپدیت شده خالی نیست)
            if (newIpsFromServer.isNotEmpty()) {

                // 1. دریافت لیست قدیمی از حافظه
                val savedJson = kv.decodeString("PREF_MANAGED_ROUTER_IPS", "[]")
                val savedList = gson.fromJson(savedJson, Array<String>::class.java)?.toList() ?: emptyList()

                // 2. ساخت لیست نهایی با اولویت قطعی برای سرور
                // فرمول: [لیست سرور] + [لیست قدیمی] -> حذف تکراری -> انتخاب 2 تای اول
                val combinedList = (newIpsFromServer + savedList)
                    .distinct() // حذف موارد تکراری (اولویت با اولی‌هاست)
                    .take(2)    // فقط 2 تای اول را نگه دار

                Log.d("NetworkCheck", "Final Router Order (Top is Newest): $combinedList")

                // 3. ذخیره در حافظه
                kv.encode("PREF_MANAGED_ROUTER_IPS", gson.toJson(combinedList))

                // 4. آپدیت کردن کانفیگ جاری
                finalConfig = finalConfig!!.copy(routerIps = combinedList)
            } else {
                // اگر سرور آی‌پی نفرستاد (لیست خالی)، از حافظه بخوان
                val savedJson = kv.decodeString("PREF_MANAGED_ROUTER_IPS", "[]")
                val savedList = gson.fromJson(savedJson, Array<String>::class.java)?.toList() ?: emptyList()

                if (savedList.isNotEmpty()) {
                    finalConfig = finalConfig!!.copy(routerIps = savedList)
                }
            }



            // --- بخش 2: مدیریت Config List (cl) ---
            // قانون: اگر جدید بود به برنامه اضافه کن.
            // نکته: خود NetworkStatus کانفیگ‌ها را برمی‌گرداند و MainActivity (در کد قبلی شما)
            // وظیفه دارد با AngConfigManager.importBatchConfig آنها را ایمپورت کند.
            // بنابراین همین که finalConfig شامل لیست cl باشد کافیست.
        }
        // -----------------------------------------------------------------------

        // تصمیم‌گیری نهایی برای پیام خطا
        if (finalConfig == null) {
            statusMessage = if (atLeastOneServerReached) {
                "سرور درحال بروز رسانی است، چند دقیقه دیگر تلاش کنید"
            } else {
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
     */
    private fun fetchJsonFromUrl(urlString: String, connectTimeout: Int, readTimeout: Int): FetchResult {
        return try {
            val urlWithNoCache = "$urlString?t=${System.currentTimeMillis()}"
            val url = URL(urlWithNoCache)
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "GET"
                this.connectTimeout = connectTimeout
                this.readTimeout = readTimeout
                useCaches = false
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
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                try {
                    val config = gson.fromJson(jsonText, AppConfig::class.java)
                    FetchResult(config, isNetworkSuccessful = true)
                } catch (e: Exception) {
                    Log.e("NetworkCheck", "JSON parsing error: ${e.message}")
                    FetchResult(null, isNetworkSuccessful = true)
                }
            } else {
                Log.w("NetworkCheck", "Failed to connect to $urlString. Response Code: $responseCode")
                connection.disconnect()
                FetchResult(null, isNetworkSuccessful = true)
            }
        } catch (e: Exception) {
            Log.e("NetworkCheck", "Error fetching from $urlString: ${e.message}")
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

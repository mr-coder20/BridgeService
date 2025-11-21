package com.v2ray.ang.data

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.Collections

// ... (Data classes remain unchanged) ...
data class NetworkStatus(
    val isReachable: Boolean,
    val pingStatus: String,
    val localIp: String?
)

data class AuthResult(
    val success: Boolean,
    val message: String
)

data class UpdateConfig(
    val latestVersionCode: Long,
    val latestVersionName: String,
    val minRequiredVersionCode: Long,
    val updateUrl: String,
    val releaseNotes: String
)

class MikroTikRepository {

    /**
     * این تابع اتصال اینترنت را با یک روش بسیار قابل اعتماد (TCP Socket) بررسی می‌کند
     * که روی دستگاه‌هایی مانند تلویزیون که پینگ (ICMP) را مسدود می‌کنند نیز کار می‌کند.
     */
    suspend fun checkInternetConnectivity(): NetworkStatus = withContext(Dispatchers.IO) {
        val primaryHost = "gist.githubusercontent.com"
        val fallbackHost = "1.1.1.1" // Cloudflare DNS
        val httpsPort = 443 // پورت استاندارد HTTPS
        val timeoutMs = 7000

        // ابتدا اتصال به سرور اصلی (Gist) را با سوکت TCP تست می‌کنیم
        var isReachable = isHostReachableWithSocket(primaryHost, httpsPort, timeoutMs)
        val connectionStatus: String

        if (isReachable) {
            connectionStatus = "OK ($primaryHost)"
        } else {
            // اگر اتصال به Gist شکست خورد، یک سرور عمومی دیگر را تست می‌کنیم
            Log.w("NetworkCheck", "Could not connect to Gist host via socket. Trying fallback.")
            isReachable = isHostReachableWithSocket(fallbackHost, httpsPort, timeoutMs)
            connectionStatus = if (isReachable) "Fallback OK ($fallbackHost)" else "Connection Failed"
        }

        NetworkStatus(
            isReachable = isReachable,
            pingStatus = connectionStatus,
            localIp = getLocalIpAddress()
        )
    }

    /**
     * یک اتصال TCP به هاست و پورت مشخص شده برقرار می‌کند تا اتصال را تست کند.
     * این روش از isReachable قابل اعتمادتر است.
     */
    private fun isHostReachableWithSocket(host: String, port: Int, timeout: Int): Boolean {
        Log.d("NetworkCheck", "Attempting TCP connection to $host on port $port with timeout $timeout ms")
        return try {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), timeout)
                Log.d("NetworkCheck", "TCP connection to $host successful.")
                true
            }
        } catch (e: Exception) {
            // این خطا در تلویزیون طبیعی است اگر دسترسی به هاست مسدود باشد
            Log.e("NetworkCheck", "TCP connection to $host failed: ${e.message}")
            false
        }
    }


    // ... (بقیه توابع شما بدون هیچ تغییری باقی می‌مانند) ...
    private fun getLocalIpAddress(): String? {
        try {
            val interfaces: List<NetworkInterface> = Collections.list(NetworkInterface.getNetworkInterfaces())
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

    suspend fun authenticateSSH(routerIp: String, user: String, pass: String): AuthResult {
        return withContext(Dispatchers.IO) {
            if (user == "test" && pass == "test") {
                AuthResult(true, "Authentication successful!")
            } else {
                AuthResult(false, "Authentication failed: Invalid credentials.")
            }
        }
    }

    suspend fun checkForUpdates(): UpdateConfig? {
        return withContext(Dispatchers.IO) {
            try {
                val gistUrl = "https://gist.githubusercontent.com/mr-coder20/33e88c9783202c52b7654886b2619147/raw"

                // 1. اضافه کردن پارامتر زمان برای فریب دادن کش سرور/کلاینت
                val urlString = "$gistUrl?t=${System.currentTimeMillis()}"

                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection

                // 2. تنظیم هدرهای اجباری برای جلوگیری از کش
                connection.requestMethod = "GET"
                connection.useCaches = false
                connection.defaultUseCaches = false
                connection.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                connection.setRequestProperty("Pragma", "no-cache")
                connection.setRequestProperty("Expires", "0")

                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val text = connection.inputStream.bufferedReader().use { it.readText() }
                    Log.d("UpdateCheck", "JSON Downloaded: $text")
                    try {
                        Gson().fromJson(text, UpdateConfig::class.java)
                    } catch (e: Exception) {
                        Log.e("UpdateCheck", "JSON Parsing Error: ${e.message}")
                        null
                    }
                } else {
                    Log.e("UpdateCheck", "Connection Failed: ${connection.responseCode}")
                    null
                }
            } catch (e: Exception) {
                Log.e("UpdateCheck", "Update Check Exception: ${e.message}")
                null
            }
        }
    }


}

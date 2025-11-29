package com.v2ray.ang.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.Properties
import kotlin.coroutines.resume

data class AuthResult(val success: Boolean, val message: String)

class MikroTikRepository {

    private val networkChecker = NetworkConnectivityChecker()
    private var cachedConfig: AppConfig? = null

    // تایم‌اوت‌ها را کاهش دادم برای سرعت بالاتر (تشخیص سریع‌تر auth fail)
    private val SSH_TIMEOUT = 8000  // کاهش از 30000 به 8000 میلی‌ثانیه
    private val CONNECTION_TIMEOUT = 3000  // کاهش از 10000 به 3000 میلی‌ثانیه
    private val SOCKET_TIMEOUT = 5000  // کاهش از 15000 به 5000 میلی‌ثانیه

    suspend fun checkInternetConnectivity(): NetworkStatus {
        val status = networkChecker.checkInternetConnectivity()
        if (status.isReachable && status.config != null) {
            cachedConfig = status.config
        }
        return status
    }

    suspend fun authenticateSsh(context: Context, user: String, pass: String): AuthResult =
        withContext(Dispatchers.IO) {
            val currentConfig = cachedConfig
            if (currentConfig == null || currentConfig.routerIps.isNullOrEmpty()) {
                return@withContext AuthResult(false, "CONFIG_MISSING")
            }

            val routerList = currentConfig.routerIps

            // تلاش برای یافتن شبکه فیزیکی (برای دور زدن باگ‌های احتمالی روتینگ اندروید)
            val physicalNetwork = findPhysicalNetwork(context)

            if (physicalNetwork != null) {
                Log.i("SSHAuth", "Physical network acquired: $physicalNetwork")
            } else {
                Log.w("SSHAuth", "No physical network found! Using default route.")
            }

            // متغیری برای نگهداری آخرین خطای واقعی دریافت شده
            var lastErrorMessage = "All routers are unreachable"

            for (routerAddress in routerList) {
                val (host, port) = parseHostAndPort(routerAddress)
                Log.d("SSHAuth", "Attempting connection to: $host:$port")

                val result = trySshConnectJSch(host, port, user, pass, physicalNetwork)

                if (result.success) {
                    return@withContext result
                } else {
                    // خطای واقعی (ترجمه شده) را ذخیره می‌کنیم
                    lastErrorMessage = result.message
                    Log.w("SSHAuth", "Failed to connect to $host: ${result.message}")

                    // --- تغییر جدید: اگر auth fail باشد، دیگر روترها را تست نکن ---
                    if (result.message == "Invalid username or password") {
                        Log.i("SSHAuth", "Auth failed, stopping further attempts as password is likely incorrect for all routers.")
                        return@withContext AuthResult(false, lastErrorMessage)
                    }
                }
            }

            // در اینجا آخرین خطای واقعی (مثل Invalid Password یا Connection Refused) برگردانده می‌شود
            return@withContext AuthResult(false, lastErrorMessage)
        }

    private suspend fun findPhysicalNetwork(context: Context): Network? =
        suspendCancellableCoroutine { cont ->
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (cont.isActive) {
                        cont.resume(network)
                        // جلوگیری از کرش در APIهای قدیمی هنگام Unregister
                        try { cm.unregisterNetworkCallback(this) } catch (e: Exception) {}
                    }
                }

                override fun onUnavailable() {
                    if (cont.isActive) cont.resume(null)
                }
            }

            // بررسی ورژن اندروید برای استفاده از متد مناسب
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                // برای اندروید 8 (API 26) به بالا
                cm.requestNetwork(request, callback, 2000)  // کاهش تایم‌اوت از 3000 به 2000
            } else {
                // برای اندرویدهای قدیمی‌تر (API 21 تا 25)
                // این نسخه تایم‌اوت ندارد، پس دستی تایم‌اوت می‌گذاریم
                cm.requestNetwork(request, callback)

                // هندل کردن تایم‌اوت به صورت دستی با Coroutine
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (cont.isActive) {
                        cont.resume(null)
                        try { cm.unregisterNetworkCallback(callback) } catch (e: Exception) {}
                    }
                }, 2000)  // کاهش تایم‌اوت از 3000 به 2000
            }
        }


    private fun trySshConnectJSch(
        host: String,
        port: Int,
        user: String,
        pass: String,
        network: Network?
    ): AuthResult {
        var session: Session? = null
        return try {
            val jsch = JSch()
            session = jsch.getSession(user, host, port)
            session.setPassword(pass)

            session.setSocketFactory(NetworkBoundSocketFactory(network, CONNECTION_TIMEOUT, SOCKET_TIMEOUT))

            val config = Properties()
            config["StrictHostKeyChecking"] = "no"
            config["PreferredAuthentications"] = "password"
            config["kex"] = "diffie-hellman-group1-sha1,diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha1,diffie-hellman-group-exchange-sha256,ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521"
            config["cipher.s2c"] = "aes128-ctr,aes128-cbc,3des-ctr,3des-cbc,blowfish-cbc,aes192-cbc,aes256-cbc"
            config["cipher.c2s"] = "aes128-ctr,aes128-cbc,3des-ctr,3des-cbc,blowfish-cbc,aes192-cbc,aes256-cbc"

            session.setConfig(config)
            session.timeout = SSH_TIMEOUT

            Log.d("SSHAuth", "Connecting JSch to $host:$port...")
            session.connect(SSH_TIMEOUT)  // استفاده از تایم‌اوت برای connect

            if (session.isConnected) {
                Log.i("SSHAuth", "SSH Login Successful on $host")
                session.disconnect()
                AuthResult(true, "Login Successful")
            } else {
                AuthResult(false, "Session created but not connected")
            }

        } catch (e: Exception) {
            Log.e("SSHAuth", "JSch Error on $host: ${e.message}")
            val errorMsg = e.message?.lowercase() ?: ""

            // منطق ترجمه خطا که درخواست کردید
            val userMessage = when {
                errorMsg.contains("auth fail") -> "Invalid username or password"
                errorMsg.contains("timeout") -> "Connection timed out"
                errorMsg.contains("connection refused") -> "Connection refused (Port $port blocked)"
                errorMsg.contains("network is unreachable") -> "Network unreachable"
                else -> "Connection failed: ${e.message}"
            }
            AuthResult(false, userMessage)
        } finally {
            try {
                session?.disconnect()
            } catch (e: Exception) { }
        }
    }

    private fun parseHostAndPort(rawAddress: String): Pair<String, Int> {
        val clean = rawAddress.trim().replace("/", "").replace("http://", "").replace("https://", "")
        return if (clean.contains(":")) {
            val parts = clean.split(":")
            val host = parts[0]
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 2224
            Pair(host, port)
        } else {
            Pair(clean, 2224)
        }
    }

    class NetworkBoundSocketFactory(
        private val network: Network?,
        private val connectionTimeout: Int,
        private val socketTimeout: Int
    ) : SocketFactory {
        override fun createSocket(host: String, port: Int): Socket {
            // 1. تلاش اول: استفاده از شبکه فیزیکی
            if (network != null) {
                try {
                    val socket = Socket(Proxy.NO_PROXY)
                    network.bindSocket(socket)

                    val addresses = network.getAllByName(host)
                    val ipv4Address = addresses.firstOrNull { it is java.net.Inet4Address }
                        ?: throw Exception("No IPv4 address found for $host")

                    socket.soTimeout = socketTimeout
                    socket.keepAlive = true
                    socket.tcpNoDelay = true

                    socket.connect(InetSocketAddress(ipv4Address, port), connectionTimeout)
                    return socket

                } catch (e: Exception) {
                    Log.w("SSHAuth", "Direct connection failed (${e.message}). Switching to default route.")
                }
            }

            // 2. تلاش دوم (Fallback): اتصال استاندارد
            // اگر VPN روشن باشد از VPN می‌رود، اگر خاموش باشد از نت معمولی تلاش می‌کند.
            val fallbackSocket = Socket(Proxy.NO_PROXY)
            try {
                val fallbackAddress = InetAddress.getAllByName(host).firstOrNull { it is java.net.Inet4Address }
                    ?: InetAddress.getByName(host)

                fallbackSocket.soTimeout = socketTimeout
                fallbackSocket.connect(InetSocketAddress(fallbackAddress, port), connectionTimeout)
                return fallbackSocket
            } catch (e: Exception) {
                Log.e("SSHAuth", "System Route connection also failed: ${e.message}")
                throw e
            }
        }

        override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
        override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
    }
}

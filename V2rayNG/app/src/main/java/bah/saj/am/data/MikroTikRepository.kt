package bah.saj.am.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Properties
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class AuthResult(val success: Boolean, val message: String)

class MikroTikRepository(private val context: Context) {

    private val networkChecker = NetworkConnectivityChecker()
    private var cachedConfig: AppConfig? = null

    // تایم‌اوت‌ها
    private val SSH_TIMEOUT = 10000
    private val CONNECTION_TIMEOUT = 5000
    private val SOCKET_TIMEOUT = 10000

    private val PREF_FILE = "mikrotik_secure_prefs"
    private val PREF_USERNAME = "secure_username"
    private val PREF_PASSWORD = "secure_password"
    private val PREF_IV_USER = "secure_iv_user"
    private val PREF_IV_PASS = "secure_iv_pass"
    private val PREF_LEGACY_KEY = "secure_legacy_key"

    // کلید برای وضعیت خروج دستی
    private val PREF_IS_MANUAL_LOGOUT = "pref_is_manual_logout"

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    private val ANDROID_KEY_STORE = "AndroidKeyStore"
    private val KEY_ALIAS = "MikroTik_Hardware_Key_v2"

    // --- بخش اول کلید (به صورت معکوس ذخیره شده تا در جستجو پیدا نشود) ---
    private val PART_A_REVERSED = "9EF3JYzwxh8T"

    // --- تابع سرهم‌کردن کلید در زمان اجرا (Runtime) ---
    private fun reassembleSecretKey(): String {
        try {
            // 1. بازیابی بخش اول از متغیر کلاس (Reversed)
            val part1 = StringBuilder(PART_A_REVERSED).reverse().toString()

            // 2. بازیابی بخش دوم از آبجکت کمکی (Byte Array)
            val part2 = SecurityConstants.getPartB()

            // 3. بازیابی بخش سوم از تابع محاسباتی محلی
            val part3 = getPartC()

            // ترکیب نهایی
            return part1 + part2 + part3
        } catch (e: Exception) {
            return ""
        }
    }

    // تابع تولید بخش سوم کلید
    private fun getPartC(): String {
        return String(charArrayOf(
            'I', 'u', '3', 'a', '7', 'I', 'q', '2', 'W', 'Z', '/', 'U',
            '6', 'w', '2', 'J', 'X', 'O', 'U', '='
        ))
    }

    // --- مدیریت کلید رمزنگاری (ضد دیکامپایل) ---
    private fun getSecretKey(): SecretKey {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getKeystoreSecretKey()
        } else {
            getLegacySecretKey()
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun getKeystoreSecretKey(): SecretKey {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE)
            keyStore.load(null)
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
                keyGenerator.init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
                return keyGenerator.generateKey()
            }
            return keyStore.getKey(KEY_ALIAS, null) as SecretKey
        } catch (e: Exception) {
            throw e
        }
    }

    private fun getLegacySecretKey(): SecretKey {
        val storedKey = prefs.getString(PREF_LEGACY_KEY, null)
        return if (storedKey != null) {
            val decodedKey = Base64.decode(storedKey, Base64.DEFAULT)
            SecretKeySpec(decodedKey, "AES")
        } else {
            val secureRandom = SecureRandom()
            val keyBytes = ByteArray(32)
            secureRandom.nextBytes(keyBytes)
            val encodedKey = Base64.encodeToString(keyBytes, Base64.DEFAULT)
            prefs.edit().putString(PREF_LEGACY_KEY, encodedKey).apply()
            SecretKeySpec(keyBytes, "AES")
        }
    }

    // --- تابع دیکریپت کردن AES + GZIP ---
    // --- تابع دیکریپت امن (با بافر برای جلوگیری از EOF) ---
    // --- تابع دیکریپت امن (نسخه نهایی و قطعی) ---

    // --- تابع دریافت کانفیگ (با قابلیت تک‌خطی کردن JSON) ---
    // --- تابع دیکریپت امن (نسخه نهایی و قطعی) ---
    // --- تابع دیکریپت امن (نسخه نهایی و قطعی) ---
    // --- تابع دیکریپت امن (نسخه نهایی و اصلاح شده) ---
    // --- تابع دیکریپت امن (نسخه نهایی و قطعی) ---
    private fun decryptAesGzip(encryptedBase64: String): String {
        // حذف کلمه return از اینجا تا خطای Unreachable ندهد
        try {
            // 1. ساخت کلید
            val safeKey = reassembleSecretKey()
            if (safeKey.isEmpty()) throw Exception("Key generation failed")
            val keyBytes = Base64.decode(safeKey, Base64.DEFAULT)
            val secretKeySpec = SecretKeySpec(keyBytes, "AES")

            // 2. دیکد Base64 ورودی
            val combined = Base64.decode(encryptedBase64, Base64.DEFAULT)

            // 3. جدا کردن IV و متن رمز شده
            val iv = combined.copyOfRange(0, 16)
            val encryptedBytes = combined.copyOfRange(16, combined.size)

            // 4. دیکریپت AES
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, IvParameterSpec(iv))
            val compressedBytes = cipher.doFinal(encryptedBytes)

            // 5. آنزیپ کردن (GZIP) با استفاده از متد copyTo برای اطمینان از خواندن کامل
            GZIPInputStream(ByteArrayInputStream(compressedBytes)).use { gzipStream ->
                ByteArrayOutputStream().use { outStream ->
                    // این خط معجزه می‌کند: تمام استریم را تا انتها می‌خواند و در outStream می‌ریزد
                    gzipStream.copyTo(outStream)

                    // تبدیل به String و بازگرداندن
                    return outStream.toString("UTF-8").trim()
                }
            }
        } catch (e: Exception) {
            Log.e("Decrypt", "Failed: ${e.message}")
            throw e
        }
    }


    fun getDecryptedConfigs(): List<String> {
        val list = cachedConfig?.configList ?: return emptyList()
        val decryptedList = mutableListOf<String>()

        Log.d("ConfigImport", "Processing ${list.size} configs...")

        for (enc in list) {
            try {
                val decrypted = decryptAesGzip(enc)

                if (decrypted.trim().startsWith("{")) {
                    // استراتژی جدید و ایمن برای حذف کامنت‌ها و فشرده‌سازی:
                    // 1. خط به خط جدا می‌کنیم
                    val lines = decrypted.lines()
                    val sb = StringBuilder()

                    for (line in lines) {
                        var cleanLine = line.trim()

                        // اگر خط خالی است رد شو
                        if (cleanLine.isEmpty()) continue

                        // شناسایی و حذف کامنت‌های //
                        // اما مواظب هستیم https:// را حذف نکنیم
                        val commentIndex = cleanLine.indexOf("//")
                        if (commentIndex != -1) {
                            // چک می‌کنیم آیا این // بخشی از http:// یا https:// است؟
                            val isUrl = (commentIndex > 0 && cleanLine[commentIndex - 1] == ':')

                            if (!isUrl) {
                                // اگر URL نبود، از اینجا به بعد کامنت است، حذفش کن
                                cleanLine = cleanLine.substring(0, commentIndex).trim()
                            }
                        }

                        sb.append(cleanLine)
                    }

                    val minified = sb.toString()

                    decryptedList.add(minified)
                    Log.d("ConfigImport", "JSON Minified Safe. Length: ${minified.length}")
                } else {
                    decryptedList.add(decrypted.trim())
                }
            } catch (e: Exception) {
                Log.e("ConfigImport", "Skipping invalid config: ${e.message}")
            }
        }
        return decryptedList
    }




    suspend fun checkInternetConnectivity(): NetworkStatus {
        val status = networkChecker.checkInternetConnectivity()

        Log.d("MikroTikRepo", "--------------------------------------------------")
        Log.d("MikroTikRepo", "Network Check Result: Reachable=${status.isReachable}")
        if (status.config != null) {
            Log.d("MikroTikRepo", "Router IPs (Encrypted Count): ${status.config.routerIps?.size ?: 0}")
            Log.d("MikroTikRepo", "Config List (Encrypted Count): ${status.config.configList?.size ?: 0}")
        }
        Log.d("MikroTikRepo", "--------------------------------------------------")

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

            val encryptedRouterList = currentConfig.routerIps
            var lastErrorMessage = "All routers are unreachable"

            for (encryptedIp in encryptedRouterList) {
                // *** دیکریپت کردن IP روتر قبل از استفاده ***
                val hostAddress = try {
                    decryptAesGzip(encryptedIp)
                } catch (e: Exception) {
                    Log.e("SSHAuth", "Failed to decrypt router IP. Skipping...", e)
                    continue
                }

                Log.d("SSHAuth", "Trying router: $hostAddress")
                val (host, originalPort) = parseHostAndPort(hostAddress)
                val portsToTry = if (originalPort != 22) listOf(originalPort, 22) else listOf(22)

                for (port in portsToTry) {
                    var attempts = 0
                    var loginResult: AuthResult

                    do {
                        attempts++
                        loginResult = trySshConnectJSch(host, port, user, pass)

                        if (loginResult.success || loginResult.message == "Invalid username or password") break
                        if (loginResult.message.contains("Connection refused")) break

                        if (attempts < 2) Thread.sleep(1000)
                    } while (attempts < 2)

                    if (loginResult.success) {
                        val newPassword = generateStrongPassword()
                        changePasswordViaSsh(host, port, user, pass, newPassword)
                        saveCredentialsSecurely(user, newPassword)
                        return@withContext AuthResult(true, "Login Successful")
                    } else {
                        lastErrorMessage = loginResult.message
                        if (loginResult.message == "Invalid username or password") {
                            return@withContext AuthResult(false, loginResult.message)
                        }
                    }
                }
            }
            return@withContext AuthResult(false, lastErrorMessage)
        }

    private suspend fun changePasswordViaSsh(
        host: String, port: Int, user: String, oldPass: String, newPass: String
    ) {
        withContext(Dispatchers.IO) {
            var session: Session? = null
            var channel: ChannelExec? = null
            try {
                val jsch = JSch()
                session = jsch.getSession(user, host, port)
                session.setPassword(oldPass)
                session.setSocketFactory(SimpleSocketFactory(CONNECTION_TIMEOUT, SOCKET_TIMEOUT))

                val config = Properties()
                config["StrictHostKeyChecking"] = "no"
                config["PreferredAuthentications"] = "password"
                config["kex"] = "diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha256,ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521"
                config["cipher.s2c"] = "aes256-ctr,aes192-ctr,aes128-ctr"
                config["cipher.c2s"] = "aes256-ctr,aes192-ctr,aes128-ctr"
                session.setConfig(config)

                session.connect(SSH_TIMEOUT)

                if (session.isConnected) {
                    val command = "/user-manager user set [find name=\"$user\"] password=\"$newPass\""
                    channel = session.openChannel("exec") as ChannelExec
                    channel.setCommand(command)
                    channel.inputStream = null
                    channel.connect()
                    Thread.sleep(1500)
                    channel.disconnect()
                    session.disconnect()
                }
            } catch (e: Exception) {
                Log.e("SSHAuth", "Command execution ended.")
            }
        }
    }

    private fun trySshConnectJSch(
        host: String, port: Int, user: String, pass: String
    ): AuthResult {
        var session: Session? = null
        return try {
            val jsch = JSch()
            session = jsch.getSession(user, host, port)
            session.setPassword(pass)
            session.setSocketFactory(SimpleSocketFactory(CONNECTION_TIMEOUT, SOCKET_TIMEOUT))

            val config = Properties()
            config["StrictHostKeyChecking"] = "no"
            config["PreferredAuthentications"] = "password"
            config["kex"] = "diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha256,ecdh-sha2-nistp256"
            config["cipher.s2c"] = "aes256-ctr,aes192-ctr,aes128-ctr"
            config["cipher.c2s"] = "aes256-ctr,aes192-ctr,aes128-ctr"
            session.setConfig(config)
            session.timeout = SSH_TIMEOUT

            session.connect(SSH_TIMEOUT)
            if (session.isConnected) {
                session.disconnect()
                AuthResult(true, "Login Successful")
            } else {
                AuthResult(false, "Session created but not connected")
            }
        } catch (e: Exception) {
            val errorMsg = e.message?.lowercase() ?: ""
            val userMessage = when {
                errorMsg.contains("auth fail") -> "Invalid username or password"
                errorMsg.contains("timeout") -> "Connection timed out"
                errorMsg.contains("connection refused") -> "Connection refused (Port closed)"
                errorMsg.contains("network is unreachable") -> "Network unreachable"
                else -> "Connection failed"
            }
            AuthResult(false, userMessage)
        } finally {
            try { session?.disconnect() } catch (e: Exception) { }
        }
    }

    private fun generateStrongPassword(length: Int = 16): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = SecureRandom()
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            sb.append(chars[random.nextInt(chars.length)])
        }
        return sb.toString()
    }

    // --- Encryption Logic ---
    private fun saveCredentialsSecurely(username: String, password: String) {
        try {
            val secretKey = getSecretKey()
            val isM = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            val transformation = if (isM) "AES/GCM/NoPadding" else "AES/ECB/PKCS5Padding"

            val cipher = Cipher.getInstance(transformation)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val ivUser = if (isM) cipher.iv else ByteArray(0)
            val encUser = cipher.doFinal(username.toByteArray(Charsets.UTF_8))

            val cipher2 = Cipher.getInstance(transformation)
            cipher2.init(Cipher.ENCRYPT_MODE, secretKey)
            val ivPass = if (isM) cipher2.iv else ByteArray(0)
            val encPass = cipher2.doFinal(password.toByteArray(Charsets.UTF_8))

            val editor = prefs.edit()
                .putString(PREF_USERNAME, Base64.encodeToString(encUser, Base64.NO_WRAP))
                .putString(PREF_PASSWORD, Base64.encodeToString(encPass, Base64.NO_WRAP))

            if (isM) {
                editor.putString(PREF_IV_USER, Base64.encodeToString(ivUser, Base64.NO_WRAP))
                editor.putString(PREF_IV_PASS, Base64.encodeToString(ivPass, Base64.NO_WRAP))
            }

            editor.putBoolean(PREF_IS_MANUAL_LOGOUT, false)
            editor.apply()
        } catch (e: Exception) {
            Log.e("MikroTikRepo", "Enc Error")
        }
    }

    fun getSavedCredentials(): Pair<String?, String?> {
        try {
            val encUser = prefs.getString(PREF_USERNAME, null)
            val encPass = prefs.getString(PREF_PASSWORD, null)
            if (encUser == null || encPass == null) return Pair(null, null)

            val secretKey = getSecretKey()
            val isM = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            val transformation = if (isM) "AES/GCM/NoPadding" else "AES/ECB/PKCS5Padding"

            val cipher = Cipher.getInstance(transformation)
            if (isM) {
                val ivStr = prefs.getString(PREF_IV_USER, null) ?: return Pair(null, null)
                val spec = GCMParameterSpec(128, Base64.decode(ivStr, Base64.NO_WRAP))
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            } else {
                cipher.init(Cipher.DECRYPT_MODE, secretKey)
            }
            val user = String(cipher.doFinal(Base64.decode(encUser, Base64.NO_WRAP)), Charsets.UTF_8)

            val cipher2 = Cipher.getInstance(transformation)
            if (isM) {
                val ivStr = prefs.getString(PREF_IV_PASS, null) ?: return Pair(null, null)
                val spec = GCMParameterSpec(128, Base64.decode(ivStr, Base64.NO_WRAP))
                cipher2.init(Cipher.DECRYPT_MODE, secretKey, spec)
            } else {
                cipher2.init(Cipher.DECRYPT_MODE, secretKey)
            }
            val pass = String(cipher2.doFinal(Base64.decode(encPass, Base64.NO_WRAP)), Charsets.UTF_8)

            return Pair(user, pass)
        } catch (e: Exception) {
            clearCredentials()
            return Pair(null, null)
        }
    }

    fun clearCredentials() = prefs.edit().clear().apply()

    fun setManualLogout(isLogout: Boolean) {
        prefs.edit().putBoolean(PREF_IS_MANUAL_LOGOUT, isLogout).apply()
    }

    fun isManualLogout(): Boolean {
        return prefs.getBoolean(PREF_IS_MANUAL_LOGOUT, false)
    }

    fun exportTransferToken(): String? {
        val (user, pass) = getSavedCredentials()
        if (user != null && pass != null) {
            return TokenManager.generateToken(user, pass)
        }
        return null
    }

    fun importTransferToken(token: String): Pair<String, String>? {
        return TokenManager.decodeToken(token)
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

    class SimpleSocketFactory(
        private val connectionTimeout: Int,
        private val socketTimeout: Int
    ) : SocketFactory {
        override fun createSocket(host: String, port: Int): Socket {
            val socket = Socket()
            try {
                val address = InetAddress.getByName(host)
                socket.soTimeout = socketTimeout
                socket.keepAlive = true
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(address, port), connectionTimeout)
                return socket
            } catch (e: Exception) {
                try { socket.close() } catch (ignored: Exception) {}
                throw e
            }
        }
        override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
        override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
    }
}

// --- آبجکت امنیتی برای نگهداری بخشی از کلید ---
object SecurityConstants {
    // بخش دوم کلید: x0c7P1W5XDWb
    private val partB_bytes = byteArrayOf(
        120, 48, 99, 55, 80, 49, 87, 53, 88, 68, 87, 98
    )
    fun getPartB(): String {
        return String(partB_bytes)
    }
}

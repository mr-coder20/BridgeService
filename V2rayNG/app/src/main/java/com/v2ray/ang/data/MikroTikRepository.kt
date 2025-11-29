package com.v2ray.ang.data

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
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
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

    // *** تغییر جدید: کلید برای وضعیت خروج دستی ***
    private val PREF_IS_MANUAL_LOGOUT = "pref_is_manual_logout"

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    private val ANDROID_KEY_STORE = "AndroidKeyStore"
    private val KEY_ALIAS = "MikroTik_Hardware_Key_v2"

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
            var lastErrorMessage = "All routers are unreachable"

            for (routerAddress in routerList) {
                val (host, originalPort) = parseHostAndPort(routerAddress)
                val portsToTry = if (originalPort != 22) listOf(originalPort, 22) else listOf(22)

                for (port in portsToTry) {
                    Log.d("SSHAuth", "Checking router: $host:$port")

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
                        Log.i("SSHAuth", "Login OK on $host:$port. Secure rotation started...")

                        val newPassword = generateStrongPassword()
                        changePasswordViaSsh(host, port, user, pass, newPassword)

                        // ذخیره امن (این متد حالا وضعیت خروج دستی را هم ریست می‌کند)
                        saveCredentialsSecurely(user, newPassword)

                        return@withContext AuthResult(true, "Login Successful")

                    } else {
                        lastErrorMessage = loginResult.message
                        Log.w("SSHAuth", "Failed on $host:$port: ${loginResult.message}")

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
        host: String,
        port: Int,
        user: String,
        pass: String
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

            Log.d("SSHAuth", "Connecting...")
            session.connect(SSH_TIMEOUT)

            if (session.isConnected) {
                session.disconnect()
                AuthResult(true, "Login Successful")
            } else {
                AuthResult(false, "Session created but not connected")
            }

        } catch (e: Exception) {
            val errorMsg = e.message?.lowercase() ?: ""
            Log.e("SSHAuth", "Conn Error: ${e.javaClass.simpleName}")

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

            // *** تغییر جدید: چون اطلاعات جدید ذخیره شده (لاگین موفق)، وضعیت خروج دستی را ریست میکنیم ***
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

    // *** تغییر جدید: متدهای مدیریت وضعیت خروج ***
    fun setManualLogout(isLogout: Boolean) {
        prefs.edit().putBoolean(PREF_IS_MANUAL_LOGOUT, isLogout).apply()
    }

    fun isManualLogout(): Boolean {
        return prefs.getBoolean(PREF_IS_MANUAL_LOGOUT, false)
    }

    // --- توابع انتقال یوزر (Export/Import) ---
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

    // --------------------------------------------------
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

package bah.saj.am.data

import android.util.Base64
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object TokenManager {

    // این کلید ثابت برای انتقال بین دو دستگاه آفلاین استفاده می‌شود.
    // تغییر دادن این کلید باعث می‌شود نسخه‌های قدیمی نتوانند به نسخه‌های جدید کد بدهند.
    private const val STATIC_KEY = "V2Ray_Bridge_Transfer_Key_2025!!" // دقیقاً 32 بایت یا 16 بایت نباشد مشکلی نیست چون MD5 می‌گیریم یا مستقیم استفاده می‌کنیم

    private fun getKey(): SecretKeySpec {
        // برای سادگی و کوتاه شدن کد خروجی، از 16 بایت اول کلید استفاده می‌کنیم
        val keyBytes = STATIC_KEY.toByteArray(StandardCharsets.UTF_8).copyOf(16)
        return SecretKeySpec(keyBytes, "AES")
    }

    fun generateToken(user: String, pass: String): String {
        return try {
            // ترکیب یوزر و پسورد
            val data = "$user::::$pass"

            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, getKey())

            val encrypted = cipher.doFinal(data.toByteArray(StandardCharsets.UTF_8))

            // تبدیل به Base64 بدون خط جدید تا راحت کپی شود
            val base64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)

            // اضافه کردن پیشوند برای شناسایی
            "V2R-$base64"
        } catch (e: Exception) {
            ""
        }
    }

    fun decodeToken(token: String): Pair<String, String>? {
        return try {
            if (!token.startsWith("V2R-")) return null

            val realToken = token.removePrefix("V2R-")
            val encrypted = Base64.decode(realToken, Base64.NO_WRAP)

            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, getKey())

            val decryptedBytes = cipher.doFinal(encrypted)
            val decryptedString = String(decryptedBytes, StandardCharsets.UTF_8)

            val parts = decryptedString.split("::::")
            if (parts.size == 2) {
                Pair(parts[0], parts[1])
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}

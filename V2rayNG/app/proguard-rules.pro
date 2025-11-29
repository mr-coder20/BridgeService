# --- JSch Protection (حیاتی برای SSH) ---
-keep class com.jcraft.jsch.** { *; }
-keep class com.jcraft.jsch.jce.** { *; }

# --- Security Classes ---
# جلوگیری از حذف کلاس‌های رمزنگاری که با String صدا زده می‌شوند
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }

# --- Data Models (Gson) ---
# مدل‌های دیتا که با Gson پارس می‌شوند نباید تغییر نام دهند
-keep class com.v2ray.ang.data.** { *; }
-keep class com.v2ray.ang.dto.** { *; }

# --- Android Components ---
-keep class * extends android.app.Activity
-keep class * extends android.app.Application
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.content.ContentProvider

# --- Coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# --- MMKV (ذخیره‌سازی) ---
-keep class com.tencent.mmkv.** { *; }

# --- Toasty (کتابخانه نمایش پیام) ---
-keep class es.dmoral.toasty.** { *; }

# --- Quickie (QR Code Scanner) ---
-keep class com.github.T8RIN.** { *; }

# --- EditorKit & Flexbox ---
-keep class com.blacksquircle.ui.** { *; }
-keep class com.google.android.flexbox.** { *; }

# --- Retrofit / OkHttp (اگر استفاده می‌کنید) ---
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**

# Fix for JSch missing JZlib dependency
-dontwarn com.jcraft.jzlib.**

# Fix for JSch missing Java GSS-API (Kerberos) dependency
-dontwarn org.ietf.jgss.**

# --- V2RayNG Core Specifics ---
# اگر کلاس‌های خاصی دارید که نباید obfuscate شوند (مثلاً Interfaceهای JNI)
-keepclasseswithmembernames class * {
    native <methods>;
}

# بهینه سازی بیشتر (اختیاری)
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

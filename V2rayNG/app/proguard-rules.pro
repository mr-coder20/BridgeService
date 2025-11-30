#proguard
# ----------------------------------------------------------------------------
# Security: Log Removal (حذف کامل لاگ‌ها برای امنیت)
# این بخش تمام دستورات Log.d, Log.e و System.out را از خروجی نهایی حذف می‌کند.
# ----------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}

# حذف متادیتای نام فایل و شماره خط (برای جلوگیری از مهندسی معکوس دقیق)
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ----------------------------------------------------------------------------
# Library Rules (قوانین کتابخانه‌ها)
# ----------------------------------------------------------------------------

# --- JSch Protection (SSH) ---
-keep class com.jcraft.jsch.** { *; }
-keep class com.jcraft.jsch.jce.** { *; }
-dontwarn com.jcraft.jzlib.**
-dontwarn org.ietf.jgss.**

# --- Crypto & Security ---
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }

# --- Data Models (Gson - حیاتی برای پارس کردن کانفیگ‌ها) ---
-keep class bah.saj.am.data.** { *; }
-keep class bah.saj.am.dto.** { *; }

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

# --- MMKV (Storage) ---
-keep class com.tencent.mmkv.** { *; }

# --- Toasty ---
-keep class es.dmoral.toasty.** { *; }

# --- Quickie (QR Scanner) ---
-keep class com.github.T8RIN.** { *; }

# --- EditorKit & Flexbox ---
-keep class com.blacksquircle.ui.** { *; }
-keep class com.google.android.flexbox.** { *; }

# --- OkHttp / Retrofit ---
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# --- AndroidX & Material ---
-keep class com.google.android.material.** { *; }
-keep class androidx.** { *; }
-dontwarn com.google.android.material.**
-dontwarn androidx.**

# --- V2RayNG Core Specifics ---
-keepclasseswithmembernames class * {
    native <methods>;
}

# ----------------------------------------------------------------------------
# Optimization Settings (تنظیمات بهینه‌سازی)
# ----------------------------------------------------------------------------
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

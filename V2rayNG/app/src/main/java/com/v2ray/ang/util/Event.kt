package com.v2ray.ang.util

/**
 * برای مدیریت رویدادهایی که فقط یک بار باید مصرف شوند (مانند ناوبری یا نمایش Toast).
 */
open class Event<out T>(private val content: T) {

    var hasBeenHandled = false
        private set // Allow external read but not write

    /**
     * محتوا را برمی‌گرداند و از استفاده مجدد آن جلوگیری می‌کند.
     */
    fun getContentIfNotHandled(): T? {
        return if (hasBeenHandled) {
            null
        } else {
            hasBeenHandled = true
            content
        }
    }

    /**
     * محتوا را برمی‌گرداند، حتی اگر قبلا استفاده شده باشد.
     */
    fun peekContent(): T = content
}

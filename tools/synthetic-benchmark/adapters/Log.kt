@file:Suppress("UNUSED_PARAMETER")
package android.util

/** JVM-only logging adapter. No production numerical behavior is replaced. */
object Log {
    @JvmStatic fun d(tag: String, message: String): Int = 0
    @JvmStatic fun i(tag: String, message: String): Int = 0
    @JvmStatic fun w(tag: String, message: String, error: Throwable? = null): Int = 0
    @JvmStatic fun e(tag: String, message: String, error: Throwable? = null): Int = 0
}

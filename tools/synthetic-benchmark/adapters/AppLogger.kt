@file:Suppress("UNUSED_PARAMETER")
package com.vivopulse.signal

/** File/logging adapter for a standalone JVM. */
object AppLogger {
    fun log(tag: String, message: String) {}
    fun warn(tag: String, message: String) {}
    fun error(tag: String, message: String, throwable: Throwable? = null) {}
}

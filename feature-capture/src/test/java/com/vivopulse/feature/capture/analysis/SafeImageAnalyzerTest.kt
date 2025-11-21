package com.vivopulse.feature.capture.analysis

import androidx.camera.core.ImageProxy
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

class SafeImageAnalyzerTest {

    @Test
    fun closesImageOnException() {
        val analyzer = SafeImageAnalyzer { throw RuntimeException("boom") }
        val closed = AtomicBoolean(false)
        val proxy = proxyImageProxy { closed.set(true) }
        try {
            analyzer.analyze(proxy)
        } catch (_: RuntimeException) {
        }
        assertTrue(closed.get())
    }

    private fun proxyImageProxy(onClose: () -> Unit): ImageProxy {
        val handler = java.lang.reflect.InvocationHandler { _, method, _ ->
            when (method.name) {
                "close" -> {
                    onClose()
                    Unit
                }
                "toString" -> "FakeImageProxy"
                "hashCode" -> 0
                "equals" -> false
                else -> {
                    when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        java.lang.Float.TYPE -> 0f
                        java.lang.Double.TYPE -> 0.0
                        else -> null
                    }
                }
            }
        }
        return Proxy.newProxyInstance(
            ImageProxy::class.java.classLoader,
            arrayOf(ImageProxy::class.java),
            handler
        ) as ImageProxy
    }
}



package android.os

/** Explicit test fixture device identity; no hardware capability is simulated. */
object Build {
    const val MANUFACTURER = "JVM_TEST"
    const val MODEL = "NO_CAMERA"
    object VERSION { const val RELEASE = "JVM_TEST" }
}

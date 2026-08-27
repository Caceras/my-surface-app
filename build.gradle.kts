plugins {
    // AGP 9 has built-in Kotlin support, so the separate
    // org.jetbrains.kotlin.android plugin is gone -- applying it now fails
    // the build outright.
    id("com.android.application") version "9.3.2" apply false
}

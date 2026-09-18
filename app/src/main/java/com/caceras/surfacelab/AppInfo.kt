package com.caceras.surfacelab

import android.content.Context
import android.os.Build

/** An explicit allowlist for owner-requested feedback; never reads chat storage. */
object AppInfo {
    fun summary(context: Context): String {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        return listOf(
            "Ægentica AI",
            "Version: ${info?.versionName ?: "unknown"} (${info?.longVersionCode ?: 0})",
            "Package: ${context.packageName}",
            "Phone: ${Build.MANUFACTURER} ${Build.MODEL}",
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "App language: ${context.resources.configuration.locales.toLanguageTags()}"
        ).joinToString("\n")
    }
}

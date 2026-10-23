package com.caceras.surfacelab

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Glimt (the chat bar from AwesomeSEO, as a web app) full screen: browsing with an assistant
 * that reads the page, until it joins Ægentica's own brain. Opened from the Phone menu in
 * Ægentica; OpenRouter's sign-in, finished in Chrome, links back here.
 */
class GlimtActivity : ShellActivity() {
    override val site get() = SITE

    override fun menuItems() =
        listOf<Pair<String, () -> Unit>>("Ægentica home" to { startActivity(AegenticaActivity.intent(this)) }) + phoneTools()

    companion object {
        /** Glimt Web on the owner's server (Dokploy project awesomeseo, compose glimt-web). */
        const val URL = "https://glimt-136-148-209-184.sslip.io/"
        val SITE = ShellSite(
            name = "Glimt",
            home = URL,
            hosts = setOf("glimt-136-148-209-184.sslip.io"),
            tag = "glimt",
            microphone = false,
            lightCanvas = "#F5F5F7",
            darkCanvas = "#000000",
        )

        fun isGlimt(url: Uri): Boolean = SITE.keeps(url)

        fun intent(context: Context) = Intent(context, GlimtActivity::class.java)
    }
}

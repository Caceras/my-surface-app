package com.caceras.surfacelab

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The Ægentica bar full screen: aegentica.se/bar, browsing with an assistant that reads the page
 * (Glimt's bar, moved into Caceras/aegentica-app, `docs/BAR.md`). It uses the sign-in this app's
 * aegentica.se screen already has, so no key reaches the phone; model calls go through the server.
 * Opened from the Phone menu in Ægentica. Only the bar stays here; other aegentica.se pages open
 * in Ægentica's own screen and every other link opens in the browser.
 */
class BarActivity : ShellActivity() {
    override val site get() = SITE

    override fun menuItems() =
        listOf<Pair<String, () -> Unit>>("Ægentica home" to { startActivity(AegenticaActivity.intent(this)) }) + phoneTools()

    override fun openOutside(url: String) {
        if (AegenticaActivity.isAegentica(Uri.parse(url))) startActivity(AegenticaActivity.open(this, url)) else super.openOutside(url)
    }

    companion object {
        const val URL = "https://aegentica.se/bar"
        val SITE = ShellSite(
            name = "the Ægentica bar",
            home = URL,
            hosts = setOf("aegentica.se", "www.aegentica.se"),
            path = "/bar",
            tag = "bar",
            microphone = false,
            lightCanvas = "#FFFFFF",
            darkCanvas = "#0A0A0A",
        )

        fun isBar(url: Uri): Boolean = SITE.keeps(url)

        fun intent(context: Context) = Intent(context, BarActivity::class.java)
    }
}

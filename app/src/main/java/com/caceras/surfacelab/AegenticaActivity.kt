package com.caceras.surfacelab

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Ægentica's home: aegentica.se, the owner's own agent on their server, full screen. The same
 * chats, agents, tasks and memory as on the computer, with the phone's powers lent to the site
 * (`lib/phone-app.ts` in Caceras/aegentica-app): the phone's offline voices for read aloud,
 * on-device dictation, its voice picker, shares from other apps, the microphone for live voice,
 * and the app's own tools behind the site's Phone row.
 */
class AegenticaActivity : ShellActivity() {
    override val site get() = SITE

    override fun menuItems() =
        listOf<Pair<String, () -> Unit>>("Browse the web with the bar" to { startActivity(BarActivity.intent(this)) }) + phoneTools()

    companion object {
        const val URL = "https://aegentica.se/"
        val SITE = ShellSite(
            name = "aegentica.se",
            home = URL,
            hosts = setOf("aegentica.se", "www.aegentica.se"),
            tag = "aegentica",
            microphone = true,
            lightCanvas = "#FFFFFF",
            darkCanvas = "#0A0A0A",
        )

        fun isAegentica(url: Uri): Boolean = SITE.keeps(url)

        fun intent(context: Context) =
            Intent(context, AegenticaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** One page of aegentica.se: a chat, a file, or Settings → Security to connect this phone. */
        fun open(context: Context, url: String) = intent(context).putExtra(ShellActivity.EXTRA_URL, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Connect this phone to Ægentica's back office: aegentica.se makes an access token and hands it over. */
        fun connect(context: Context) = open(context, Backend.CONNECT)
    }
}

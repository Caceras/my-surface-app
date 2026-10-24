package com.caceras.surfacelab

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Opens the genie sheet for the keyboard in a task of its own (an empty task affinity), so the sheet
 * rises over the app being typed in and never brings Ægentica's own screens forward. The sheet's
 * Replace goes to [KeyboardInbox] for the ticket.
 */
class GenieKeyboardBridge : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val ticket = KeyboardInbox.ticket(intent.getStringExtra(TICKET))
        if (ticket == null) { finish(); return }
        val id = ticket.id
        @Suppress("DEPRECATION")
        startActivityForResult(Genie.intent(this, ticket.source?.text.orEmpty(), editable = true, origin = Genie.Origin.KEYBOARD,
            app = ticket.app, request = ticket.request, handback = { text -> KeyboardInbox.answer(id, text) }), REQUEST)
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    @Deprecated("Framework callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    companion object {
        const val TICKET = "ticket"
        private const val REQUEST = 48
    }
}

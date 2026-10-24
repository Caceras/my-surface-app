package com.caceras.surfacelab

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.text.InputType
import android.util.Base64
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NFC tags that start the genie: hold the phone to a tag on the fridge or the car's dashboard and the
 * genie starts on what the owner wrote on it ("Plan my morning", "Start my drive home").
 *
 * A tag holds `aegentica://genie?do=<request>&sig=<signature>`. The signature is an HMAC with a key
 * that never leaves this phone, so only tags written here start at once; a tag from anyone else
 * only opens the genie with its words shown, waiting for the owner. Acting steps still ask as they
 * always do: a tag never approves anything.
 */
object GenieTags {
    const val SCHEME = "aegentica"
    const val HOST = "genie"
    /** Requests longer than this would not fit small tags. */
    const val MAX = 300

    class Request(val text: String, val signed: Boolean)

    private fun key(context: Context): ByteArray {
        val prefs = context.getSharedPreferences("genie-tags", 0)
        prefs.getString("key", null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString("key", Base64.encodeToString(fresh, Base64.NO_WRAP)).apply()
        return fresh
    }

    fun sign(context: Context, request: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key(context), "HmacSHA256")) }
        val digest = mac.doFinal(("genie-tag-v1\n" + request).toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest.copyOf(16), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun uri(context: Context, request: String): Uri {
        val text = request.trim().take(MAX)
        return Uri.Builder().scheme(SCHEME).authority(HOST).appendQueryParameter("do", text).appendQueryParameter("sig", sign(context, text)).build()
    }

    /** The tag's request, and whether this phone signed it; null for anything else. */
    fun read(context: Context, uri: Uri?): Request? {
        if (uri == null || uri.scheme != SCHEME || uri.host != HOST) return null
        val text = uri.getQueryParameter("do")?.trim()?.take(MAX)?.takeIf { it.isNotEmpty() } ?: return null
        val sig = uri.getQueryParameter("sig").orEmpty()
        val signed = sig.isNotEmpty() && MessageDigest.isEqual(sig.toByteArray(), sign(context, text).toByteArray())
        return Request(text, signed)
    }

    /** Settings: type what the tag should start, then hold a tag to the back of the phone. */
    fun write(activity: Activity) {
        val adapter = NfcAdapter.getDefaultAdapter(activity)
        if (adapter == null) { Toast.makeText(activity, "This phone has no NFC.", Toast.LENGTH_LONG).show(); return }
        if (!adapter.isEnabled) { Toast.makeText(activity, "Turn on NFC in Android's settings first.", Toast.LENGTH_LONG).show(); return }
        val input = EditText(activity).apply {
            hint = "What should the genie do? (e.g. Plan my morning)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            styleField()
        }
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; padDp(20, 8, 20, 0); addView(input) }
        AlertDialog.Builder(activity).setTitle("NFC tag for the genie")
            .setMessage("Tap the tag later and the genie starts on these words, on this phone only. Anything it wants to do still asks you first.")
            .setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("Write a tag") { _, _ ->
                val request = input.text.toString().trim()
                if (request.isEmpty()) return@setPositiveButton
                hold(activity, adapter, request)
            }.showProtected(activity)
    }

    private fun hold(activity: Activity, adapter: NfcAdapter, request: String) {
        val message = NdefMessage(arrayOf(NdefRecord.createUri(uri(activity, request))))
        val waiting = AlertDialog.Builder(activity).setTitle("Hold a tag to the back of the phone").setMessage("“$request”")
            .setNegativeButton("Cancel", null).setOnDismissListener { runCatching { adapter.disableReaderMode(activity) } }.showProtected(activity)
        adapter.enableReaderMode(activity, { tag: Tag ->
            val result = runCatching { writeTag(tag, message) }
            activity.runOnUiThread {
                waiting.dismiss()
                Toast.makeText(activity, result.fold({ "The tag is ready." }, { it.message ?: "The tag could not be written." }), Toast.LENGTH_LONG).show()
            }
        }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
    }

    private fun writeTag(tag: Tag, message: NdefMessage) {
        Ndef.get(tag)?.let { ndef ->
            ndef.connect()
            try {
                require(ndef.isWritable) { "This tag is locked." }
                require(ndef.maxSize >= message.byteArrayLength) { "The words do not fit this tag; use fewer." }
                ndef.writeNdefMessage(message)
            } finally { runCatching { ndef.close() } }
            return
        }
        val blank = NdefFormatable.get(tag) ?: error("This tag cannot hold a request.")
        blank.connect()
        try { blank.format(message) } finally { runCatching { blank.close() } }
    }
}

package com.caceras.surfacelab

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast

/**
 * The genie's doors outside any app's text: the share sheet (text, links, pictures, screenshots,
 * PDFs and text files), the launcher shortcut, the Quick Settings tile, the home-screen widget and
 * NFC tags. Each opens the same genie ([Genie.summon]) over what is on screen, with what came
 * through the door as its context. It runs in a task of its own, so Ægentica's own screens never
 * come forward, and shows nothing itself.
 */
class GenieEntryActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return }
        val intent = intent
        when (intent?.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> share(intent)
            NfcAdapter.ACTION_NDEF_DISCOVERED -> tag(intent)
            else -> {
                val origin = runCatching { Genie.Origin.valueOf(intent?.getStringExtra(Genie.EXTRA_ORIGIN).orEmpty()) }.getOrDefault(Genie.Origin.SHORTCUT)
                done { Genie.summon(this, origin) }
            }
        }
    }

    private fun done(open: () -> Unit) {
        runCatching(open).onFailure { Toast.makeText(this, "Ægentica could not open.", Toast.LENGTH_SHORT).show() }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    /** Shared things are read off the main thread (a big picture or PDF), then the genie opens. */
    private fun share(intent: Intent) {
        val app = referrer?.takeIf { it.scheme == "android-app" }?.host.orEmpty()
        Thread({
            val shared = runCatching { Shared.read(this, intent) }.getOrNull()
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (shared == null || shared.empty) { Toast.makeText(this, "Nothing to share with Ægentica.", Toast.LENGTH_SHORT).show(); finish(); return@post }
                done { Genie.summon(this, Genie.Origin.SHARE, shared.text, shared.picture, app) }
            }
        }, "genie-share").start()
    }

    /** An NFC tag: a request this phone signed starts at once; anything else is shown first and waits for the owner. */
    private fun tag(intent: Intent) {
        val uri = intent.data ?: run {
            @Suppress("DEPRECATION")
            val messages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            messages?.filterIsInstance<NdefMessage>()?.flatMap { it.records.toList() }?.firstNotNullOfOrNull { it.toUri() }
        }
        val request = GenieTags.read(this, uri)
        if (request == null) { Toast.makeText(this, "This tag has no Ægentica request.", Toast.LENGTH_SHORT).show(); finish(); return }
        if (request.signed) { done { Genie.ask(this, request.text, Genie.Origin.TAG) }; return }
        android.app.AlertDialog.Builder(this).setTitle("A tag asks Ægentica")
            .setMessage("“${request.text}”\n\nThis tag was not written on this phone. Start Ægentica on these words?")
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setPositiveButton("Start") { _, _ -> done { Genie.ask(this, request.text, Genie.Origin.TAG) } }
            .setOnCancelListener { finish() }
            .showProtected(this)
    }

    /** What a share carried, ready for the genie: text (with links) and at most one picture. */
    class Shared(val text: String, val picture: Bitmap?) {
        val empty get() = text.isBlank() && picture == null

        companion object {
            private const val SIDE = 1568

            fun read(context: Context, intent: Intent): Shared {
                val parts = mutableListOf<String>()
                intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }?.let { parts += it.trim() }
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { parts += it.trim() }
                val uris = streams(intent)
                var picture: Bitmap? = null
                var more = 0
                for (uri in uris.take(8)) {
                    val type = context.contentResolver.getType(uri).orEmpty()
                    val name = name(context, uri)
                    when {
                        type.startsWith("image/") -> if (picture == null) picture = image(context, uri) else more++
                        type == "application/pdf" -> if (picture == null) { picture = pdf(context, uri); parts += "PDF: ${name ?: "document"}" } else more++
                        type.startsWith("text/") -> parts += (name?.let { "$it:\n" } ?: "") + text(context, uri)
                        else -> parts += "A file: ${name ?: uri.lastPathSegment ?: "unnamed"} ($type)"
                    }
                }
                if (more > 0) parts += "And $more more " + if (more == 1) "picture." else "pictures."
                return Shared(parts.joinToString("\n\n").take(Genie.MAX_TEXT), picture)
            }

            private fun streams(intent: Intent): List<Uri> {
                val found = mutableListOf<Uri>()
                @Suppress("DEPRECATION")
                if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { found += it }
                else (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { found += it }
                intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.forEach { if (it !in found) found += it } }
                return found
            }

            private fun name(context: Context, uri: Uri): String? = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()

            /** A picture scaled so its long edge is at most [SIDE] pixels. */
            private fun image(context: Context, uri: Uri): Bitmap? {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0) return null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIDE) sample *= 2
                return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            }

            /** The first page of a PDF, drawn on white. */
            private fun pdf(context: Context, uri: Uri): Bitmap? = context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                PdfRenderer(fd).use { pdf ->
                    if (pdf.pageCount == 0) return null
                    pdf.openPage(0).use { page ->
                        val scale = SIDE.toFloat() / maxOf(page.width, page.height)
                        val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        Canvas(bitmap).drawColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }

            private fun text(context: Context, uri: Uri): String =
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = CharArray(Genie.MAX_TEXT)
                    val read = input.reader(Charsets.UTF_8).read(buffer)
                    if (read > 0) String(buffer, 0, read) else ""
                }.orEmpty()
        }
    }
}

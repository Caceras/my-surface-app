package com.caceras.surfacelab

import android.app.assist.AssistStructure
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.text.InputType
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference

/**
 * What the assistant saw when the owner invoked it on top of another app:
 * the app's package, the visible text and a screenshot. Held in memory for
 * the open assistant panel only; never stored, exported or synced.
 */
data class ScreenContext(val app: String = "", val text: String = "", val screenshot: Bitmap? = null, val arrived: Boolean = false) {
    val empty get() = text.isBlank() && screenshot == null
}

object ScreenContexts {
    /** Enough for a spreadsheet or a long message thread, small enough for a quick request. */
    const val MAX_TEXT = 6000
    /** Gemini Nano takes under 4,000 tokens in total, so it gets a shorter excerpt. */
    const val NANO_TEXT = 2400
    private const val MAX_SIDE = 1280

    @Volatile var current = ScreenContext(); private set
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    private var session: WeakReference<android.service.voice.VoiceInteractionSession>? = null

    /** Increases with each invocation, so a closing older panel cannot clear a newer screen. */
    @Volatile var invocation = 0; private set

    /** A new invocation: forget the previous screen before the new one arrives. */
    fun begin(owner: android.service.voice.VoiceInteractionSession? = null) {
        invocation++
        session = owner?.let { WeakReference(it) }
        update { ScreenContext() }
    }
    fun text(app: String, text: String) = update { it.copy(app = app.ifBlank { it.app }, text = text.take(MAX_TEXT), arrived = true) }
    fun screenshot(bitmap: Bitmap) { val small = scaled(bitmap); update { it.copy(screenshot = small, arrived = true) } }
    /** The panel of invocation [owner] closed: drop the screenshot and end the system session. */
    fun clear(owner: Int = invocation) {
        if (owner != invocation) return
        session?.get()?.let { runCatching { it.hide() } }
        session = null
        update { ScreenContext() }
    }
    /** Main-thread change notifications for the open panel; returns the unsubscribe call. */
    fun listen(listener: () -> Unit): () -> Unit { synchronized(listeners) { listeners += listener }; return { synchronized(listeners) { listeners -= listener } } }

    private fun update(change: (ScreenContext) -> ScreenContext) {
        synchronized(this) { current = change(current) }
        main.post { synchronized(listeners) { listeners.toList() }.forEach { it() } }
    }

    /** One node of a screen's view tree; [from] adapts Android's AssistStructure. */
    interface Node {
        val text: CharSequence?
        val description: CharSequence?
        val password: Boolean
        val children: List<Node>
    }

    private class ViewNodeAdapter(private val node: AssistStructure.ViewNode) : Node {
        override val text get() = node.text
        override val description get() = node.contentDescription
        override val password get() = secret(node.inputType)
        override val children get() = (0 until node.childCount).map { ViewNodeAdapter(node.getChildAt(it)) }
    }

    fun secret(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    fun from(structure: AssistStructure): String =
        read((0 until structure.windowNodeCount).map { ViewNodeAdapter(structure.getWindowNodeAt(it).rootViewNode) })

    /**
     * Visible text in reading order. Password fields are skipped, a label is
     * used only when a view has no text, and repeated lines are dropped.
     */
    fun read(roots: List<Node>, limit: Int = MAX_TEXT): String {
        val lines = mutableListOf<String>()
        var size = 0
        fun visit(node: Node, depth: Int) {
            if (size >= limit || depth > 60) return
            if (!node.password) {
                val value = (node.text?.toString()?.takeIf { it.isNotBlank() } ?: node.description?.toString()).orEmpty()
                    .replace(Regex("\\s+"), " ").trim()
                if (value.isNotEmpty() && lines.lastOrNull() != value) { lines += value; size += value.length + 1 }
            }
            node.children.forEach { visit(it, depth + 1) }
        }
        roots.forEach { visit(it, 0) }
        return lines.joinToString("\n").take(limit)
    }

    /** Software copy at most [MAX_SIDE] pixels on the long side: system screenshots may be hardware bitmaps. */
    fun scaled(bitmap: Bitmap, max: Int = MAX_SIDE): Bitmap {
        val soft = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val long = maxOf(soft.width, soft.height)
        if (long <= max) return soft
        val ratio = max.toFloat() / long
        return Bitmap.createScaledBitmap(soft, (soft.width * ratio).toInt().coerceAtLeast(1), (soft.height * ratio).toInt().coerceAtLeast(1), true)
    }

    fun jpeg(bitmap: Bitmap, quality: Int = 80): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /** Longest screenshot data URL handed to aegentica.se: about 1.5 MB of message. */
    const val MAX_DATA_URL = 1_500_000
    private const val JPEG_URL = "data:image/jpeg;base64,"

    /**
     * The screenshot as a JPEG data URL for a page in the app (aegentica.se's new chat): at most
     * [MAX_SIDE] pixels on the long side and [maxChars] characters. A busy screen that does not
     * fit is compressed harder, then made smaller; null when even that does not fit.
     */
    fun dataUrl(bitmap: Bitmap, maxChars: Int = MAX_DATA_URL): String? {
        var image = scaled(bitmap)
        repeat(4) {
            for (quality in intArrayOf(80, 65, 50)) {
                val bytes = jpeg(image, quality)
                // Base64 writes four characters for every three bytes.
                if (JPEG_URL.length + (bytes.size + 2) / 3 * 4 <= maxChars) {
                    return JPEG_URL + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                }
            }
            image = scaled(image, maxOf(image.width, image.height) * 3 / 4)
        }
        return null
    }
}

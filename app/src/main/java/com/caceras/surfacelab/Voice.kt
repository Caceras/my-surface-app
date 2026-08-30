package com.caceras.surfacelab

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice as TtsVoice
import java.util.Locale

/**
 * Speech in and speech out, with framework APIs only.
 *
 * Two small classes rather than an interface: unlike SurfaceBrain there is
 * only ever one implementation, and a seam with one side is just ceremony.
 *
 * The rule the rest of the app follows is that modality is inherited, not
 * configured. Ask by voice and the answer is spoken; type and it is not.
 * There is no setting, because there is nothing to set.
 *
 * See docs/voice.md for why each of the guards below exists.
 */

/** Words in. Wraps the on-device recogniser, and only the on-device one. */
class Ears(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    /**
     * True when speech can be recognised entirely on this phone.
     *
     * createSpeechRecognizer() is deliberately never used: its own class
     * documentation says the implementation "is likely to stream audio to
     * remote servers", which would quietly break the promise the whole app
     * is built on. No on-device recogniser means no microphone, not a
     * fallback.
     */
    fun available(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** The locale the recogniser is asked for, and the one TTS answers in. */
    fun locale(): Locale = Locale.getDefault()

    /**
     * Start listening. [onPartial] fires repeatedly as words are recognised,
     * [onFinal] once with the finished transcript, [onStop] whenever the
     * session ends for any reason -- including the ordinary case of the user
     * saying nothing at all, which is not an error and gets no message.
     */
    fun listen(
        onLevel: (Float) -> Unit = {},
        onPartial: (String) -> Unit = {},
        onFinal: (String) -> Unit = {},
        onStop: (String?) -> Unit = {}
    ) {
        if (!available() || listening) return

        // Every SpeechRecognizer method must run on the main thread, and the
        // instance must be destroyed or the microphone stays held after this
        // activity is gone -- which breaks recognition in other apps too.
        cancel()

        val client = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer = client
        listening = true

        client.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = onLevel(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.let(onPartial)
            }

            override fun onResults(results: Bundle?) {
                listening = false
                first(results)?.takeIf { it.isNotBlank() }?.let(onFinal)
                onStop(null)
            }

            override fun onError(error: Int) {
                listening = false
                // Saying nothing is the normal way a session ends, not a
                // failure. Reporting it produces a toast storm.
                val quiet = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                onStop(if (quiet) null else describe(error))
            }
        })

        client.startListening(intent())
    }

    /** Stop listening but keep whatever was recognised so far. */
    fun stop() {
        if (listening) recognizer?.stopListening()
    }

    /** Release the microphone. Mandatory; see the note in listen(). */
    fun cancel() {
        listening = false
        recognizer?.destroy()
        recognizer = null
    }

    private fun intent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            // The reference marks the language model extra required, and the
            // same intent is what a support query would apply to.
            .putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

    private fun first(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission is needed to listen."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            "This language is not installed for offline speech. " +
                "Add it in Settings, Languages, Voice input."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "The recogniser is busy. Try again in a moment."
        else -> "Could not listen just now."
    }
}

/**
 * Words out. Speech starts at the first sentence rather than at the end of
 * the answer, which is the single thing that makes this feel immediate.
 */
class Mouth(context: Context) {

    private var engine: TextToSpeech? = null
    private var ready = false
    private var muted = false
    private var spoken = 0
    private var utterance = 0

    /** Chunks that arrived before the engine finished starting up. */
    private val pending = ArrayDeque<String>()

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                pickVoice()
                // A queue, not one slot: several sentences can complete
                // before onInit lands, and dropping the earlier ones starts
                // the answer from the middle.
                while (pending.isNotEmpty()) enqueue(pending.removeFirst())
            } else {
                pending.clear()
            }
        }
    }

    /** Start a fresh spoken answer. */
    fun begin(locale: Locale) {
        muted = false
        spoken = 0
        pending.clear()
        if (ready) engine?.setLanguage(locale)
    }

    /**
     * Speak whatever complete sentences [text] has gained since last time.
     * Safe to call on every streamed update.
     */
    fun follow(text: String) {
        if (muted) return
        val (chunk, cursor) = Speech.nextChunk(text, spoken)
        if (chunk.isEmpty()) return
        spoken = cursor
        say(chunk)
    }

    /**
     * Speak the tail that never got a full stop. Without this, "Sure" and
     * every answer whose last sentence lacks punctuation is printed and
     * never spoken.
     */
    fun finish(text: String) {
        if (muted) return
        val tail = text.substring(minOf(spoken, text.length)).trim()
        spoken = text.length
        if (tail.isNotEmpty()) say(tail)
    }

    /**
     * Stop talking, and stay stopped for this answer.
     *
     * stop() alone only clears what is already queued. The brain is very
     * likely still streaming, so without the flag the next sentence boundary
     * starts it talking again half a second later.
     */
    fun hush() {
        muted = true
        pending.clear()
        engine?.stop()
    }

    fun close() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
    }

    private fun say(chunk: String) {
        if (ready) enqueue(chunk) else pending.addLast(chunk)
    }

    private fun enqueue(chunk: String) {
        // A null utterance id means the engine dispatches no progress
        // callbacks at all, so anything watching for the end never hears it.
        engine?.speak(chunk, TextToSpeech.QUEUE_ADD, null, "sl-" + (utterance++))
    }

    /**
     * Prefer a voice that can speak with the radios off. isNetworkConnection
     * Required is not enough on its own: a voice can be local and still not
     * downloaded, which fails at synthesis time rather than here.
     */
    private fun pickVoice() {
        val tts = engine ?: return
        val usable = try {
            tts.voices?.firstOrNull { candidate: TtsVoice ->
                !candidate.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in
                    (candidate.features ?: emptySet())
            }
        } catch (e: Exception) {
            null  // some engines throw here rather than returning null
        }
        if (usable != null) tts.setVoice(usable)
    }
}

/** Sentence splitting, kept pure so it can be tested without a device. */
object Speech {

    private const val ENDINGS = ".!?\n"

    /**
     * The complete sentences in [text] beyond [from], and the new cursor.
     * Returns an empty chunk when nothing has finished yet.
     */
    fun nextChunk(text: String, from: Int): Pair<String, Int> {
        if (from >= text.length) return "" to from
        val tail = text.substring(from)
        val end = tail.indexOfLast { it in ENDINGS }
        if (end < 0) return "" to from
        return tail.take(end + 1).trim() to from + end + 1
    }
}

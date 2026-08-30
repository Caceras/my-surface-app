package com.caceras.surfacelab

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

/**
 * Why a listening session ended badly.
 *
 * A message rather than an error code, because every caller only wants to
 * show it -- and one flag, because the missing-language case is the only one
 * with an action attached to it.
 */
data class VoiceProblem(val message: String, val languageMissing: Boolean = false)

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
        onStop: (VoiceProblem?) -> Unit = {}
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

    private fun describe(error: Int): VoiceProblem = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            VoiceProblem("Microphone permission is needed to listen.")
        // ERROR_LANGUAGE_UNAVAILABLE exists from API 31, but the APIs that
        // could do something about it do not arrive until 33. Which of the
        // two messages below is the honest one is decided in fetchLanguage().
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            VoiceProblem(missingLanguage(), languageMissing = true)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            VoiceProblem("The recogniser is busy. Try again in a moment.")
        else -> VoiceProblem("Could not listen just now.")
    }

    private fun missingLanguage(): String =
        if (canFetchLanguage()) "No offline speech for ${locale().displayLanguage} yet."
        else settingsHint()

    private fun settingsHint(): String =
        "${locale().displayLanguage} is not installed for offline speech. " +
            "Add it in Settings, Languages and input, Voice input."

    /**
     * True when this device can be asked to fetch a language pack.
     *
     * Both checkRecognitionSupport() and triggerModelDownload() land in API
     * 33. On 31 and 32 the recogniser exists, ERROR_LANGUAGE_UNAVAILABLE
     * exists, and there is no way to ask for the missing pack -- so the
     * honest move there is the settings hint, not a button that does nothing.
     */
    fun canFetchLanguage(): Boolean =
        available() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Ask the system for the offline pack of the locale this app keeps
     * requesting, and report what actually happened.
     *
     * The API 33 overload of triggerModelDownload() takes an intent and
     * nothing else -- the progress-and-completion listener is API 34 -- and
     * the reference says to verify the outcome by calling
     * checkRecognitionSupport() again. So: trigger, then re-check, then
     * report. Call startListening() straight after the trigger instead and it
     * still fails with ERROR_LANGUAGE_UNAVAILABLE.
     *
     * One intent instance serves the trigger and the check, because that is
     * the request each of them answers for. Check for one language and
     * download another and the Swedish speaker is exactly where they started.
     */
    fun fetchLanguage(onOutcome: (String) -> Unit) {
        if (!canFetchLanguage()) {
            onOutcome(settingsHint())
            return
        }

        val request = intent()
        val tag = locale().toLanguageTag()
        val client = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (e: Exception) {
            onOutcome(settingsHint())
            return
        }

        // Every SpeechRecognizer method is main-thread only, this one
        // included, and the instance has to be destroyed either way.
        try {
            client.triggerModelDownload(request)
            client.checkRecognitionSupport(
                request,
                context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        val ready = support.installedOnDeviceLanguages
                            .any { it.equals(tag, ignoreCase = true) }
                        client.destroy()
                        onOutcome(
                            if (ready) "Offline speech is ready. Tap the microphone."
                            else "Downloading offline speech for " +
                                "${locale().displayLanguage}. Try again shortly."
                        )
                    }

                    override fun onError(error: Int) {
                        client.destroy()
                        onOutcome(settingsHint())
                    }
                }
            )
        } catch (e: Exception) {
            client.destroy()
            onOutcome(settingsHint())
        }
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

    /** Utterances handed to the engine, and how many it has finished with. */
    private var queued = 0
    private var settled = 0

    /** Chunks that arrived before the engine finished starting up. */
    private val pending = ArrayDeque<String>()

    private val main = Handler(Looper.getMainLooper())

    /** The language to answer in, remembered until the engine can take it. */
    private var voice: Locale? = null

    /**
     * Called on the main thread once the engine has nothing left to say.
     *
     * This is the only way back out of the SPEAKING state, which is why
     * every utterance below is given an id: with a null id the engine still
     * makes noise but dispatches no progress callbacks at all, so this never
     * fires and a hands-free session never ends.
     */
    var onIdle: (() -> Unit)? = null

    /**
     * True while something is queued, waiting for the engine to start, or
     * being spoken. The pending queue counts: an answer that arrives before
     * onInit is still going to be read out.
     */
    fun speaking(): Boolean = settled < queued || pending.isNotEmpty()

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            // An engine is entitled to call this back from inside its own
            // constructor, and then the assignment above has not happened
            // yet -- so every line of the real work is posted, by which time
            // the field is set. Silently losing the whole answer to that is
            // a five-minute debugging session at best.
            main.post { started(status == TextToSpeech.SUCCESS) }
        }
    }

    private fun started(ok: Boolean) {
        ready = ok
        if (!ok) {
            pending.clear()
            return
        }
        val engine = engine ?: return
        engine.setOnUtteranceProgressListener(progress)
        pickVoice()
        voice?.let { engine.setLanguage(it) }
        // A queue, not one slot: several sentences can complete before onInit
        // lands, and dropping the earlier ones starts the answer from the
        // middle. Order matters as much as arrival.
        while (pending.isNotEmpty()) enqueue(pending.removeFirst())
    }

    /**
     * UtteranceProgressListener callbacks do not arrive on the main thread,
     * unlike the recogniser's -- so everything here is posted back before it
     * touches a view, exactly as the nano brain does with DownloadCallback.
     */
    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) = settle()

        @Deprecated("Kept because the engine may still call the old overload.")
        override fun onError(utteranceId: String?) = settle()
        override fun onError(utteranceId: String?, errorCode: Int) = settle()

        private fun settle() {
            main.post {
                settled++
                if (settled >= queued) onIdle?.invoke()
            }
        }
    }

    /**
     * Start a fresh spoken answer, in the language that was actually asked
     * for -- not the device default, or Swedish comes back in an English
     * accent.
     */
    fun begin(locale: Locale) {
        muted = false
        spoken = 0
        queued = 0
        settled = 0
        pending.clear()
        voice = locale
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
        val wasSpeaking = speaking() || pending.isNotEmpty()
        muted = true
        pending.clear()
        engine?.stop()
        // stop() dispatches no callbacks for what it dropped, so the state
        // machine would sit in SPEAKING forever waiting for an onDone that
        // is never coming.
        settled = queued
        if (wasSpeaking) main.post { onIdle?.invoke() }
    }

    fun close() {
        onIdle = null
        muted = true
        pending.clear()
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
        queued++
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

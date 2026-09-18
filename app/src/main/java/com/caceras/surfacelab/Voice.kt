package com.caceras.surfacelab

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Offline recognition and playback through Android framework APIs. */

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
    private var session = 0
    private var setupClient: SpeechRecognizer? = null
    private var setupGeneration = 0
    private val handler = Handler(Looper.getMainLooper())
    private var setupTimeout: Runnable? = null

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
    fun locale(): Locale = context.getSharedPreferences("surfacelab", Context.MODE_PRIVATE)
        .getString("speech_language", null)?.let { Locale.forLanguageTag(it) }
        ?: Locale.getDefault()

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
        if (Build.VERSION.SDK_INT < 31 || !available() || listening) return
        ReadingService.pauseForCapture()

        // Every SpeechRecognizer method must run on the main thread, and the
        // instance must be destroyed or the microphone stays held after this
        // activity is gone -- which breaks recognition in other apps too.
        cancel()

        val token = session
        val client = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (e: Exception) {
            onStop(VoiceProblem("Offline speech could not start. Try typing instead."))
            return
        }
        recognizer = client
        listening = true

        client.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                if (token == session && listening) onLevel(rmsdB)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                if (token == session && listening) first(partialResults)?.let(onPartial)
            }

            override fun onResults(results: Bundle?) {
                if (token != session || !listening) return
                listening = false
                recognizer = null
                client.destroy()
                first(results)?.takeIf { it.isNotBlank() }?.let(onFinal)
                onStop(null)
            }

            override fun onError(error: Int) {
                if (token != session || !listening) return
                listening = false
                recognizer = null
                client.destroy()
                // Saying nothing is the normal way a session ends, not a
                // failure. Reporting it produces a toast storm.
                val quiet = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                onStop(if (quiet) null else describe(error))
            }
        })

        try {
            client.startListening(intent())
        } catch (e: Exception) {
            cancel()
            onStop(VoiceProblem("Offline speech could not start. Check microphone permission."))
        }
    }

    /** Stop listening but keep whatever was recognised so far. */
    fun stop() {
        if (listening) recognizer?.stopListening()
    }

    /** Release the microphone. Mandatory; see the note in listen(). */
    fun cancel() {
        session++
        listening = false
        recognizer?.destroy()
        recognizer = null
        cancelSetup()
    }

    private fun cancelSetup() {
        setupGeneration++
        setupTimeout?.let { handler.removeCallbacks(it) }
        setupTimeout = null
        setupClient?.destroy()
        setupClient = null
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
        if (canFetchLanguage()) "Offline speech for ${locale().displayName} is not ready yet."
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
        if (Build.VERSION.SDK_INT < 33 || !canFetchLanguage()) { onOutcome(settingsHint()); return }
        cancelSetup()
        val token = setupGeneration
        val client = try { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
            catch (_: Exception) { onOutcome(settingsHint()); return }
        setupClient = client
        fun report(message: String, terminal: Boolean = false) {
            if (token != setupGeneration) return
            if (terminal) cancelSetup()
            onOutcome(message)
        }
        setupTimeout = Runnable {
            report("Android has not confirmed this download yet. Check your connection, open voice input settings, or try again. You can keep typing.", true)
        }.also { handler.postDelayed(it, 120_000) }
        val requested = locale()
        fun download(tag: String) {
            val request = intent().putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    client.triggerModelDownload(request, context.mainExecutor, object : ModelDownloadListener {
                        override fun onProgress(completedPercent: Int) {
                            report("Downloading " + Locale.forLanguageTag(tag).displayName + ": " + completedPercent.coerceIn(0, 100) + "%")
                        }
                        override fun onSuccess() { report("Offline speech is ready. Tap Talk to try it.", true) }
                        override fun onScheduled() { report("Android has queued the speech download. Keep a connection, then return and tap Talk. The download is not ready yet.", true) }
                        override fun onError(error: Int) { report("Android could not complete the speech download (" + error + "). Try another language or open voice input settings.", true) }
                    })
                } else {
                    client.triggerModelDownload(request)
                    report("Speech download requested. Android does not report progress on this version. Return shortly and tap Talk to check it.", true)
                }
            } catch (_: Exception) { report(settingsHint(), true) }
        }
        try {
            client.checkRecognitionSupport(intent(), context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    if (token != setupGeneration) return
                    val installed = bestLanguage(requested, support.installedOnDeviceLanguages)
                    val target = installed ?: bestLanguage(requested, support.supportedOnDeviceLanguages)
                        ?: bestLanguage(requested, support.pendingOnDeviceLanguages)
                    if (target == null) {
                        report("Android does not offer an offline pack for " + requested.displayName + ". Choose another speaking language in Voice setup.", true)
                        return
                    }
                    // Persist the supported regional tag so the next listen and TTS request agree.
                    context.getSharedPreferences("surfacelab", Context.MODE_PRIVATE).edit()
                        .putString("speech_language", target).apply()
                    if (installed != null) report("Offline speech is ready in " + Locale.forLanguageTag(target).displayName + ". Tap Talk.", true)
                    else download(target)
                }
                override fun onError(error: Int) {
                    if (token == setupGeneration) download(requested.toLanguageTag())
                }
            })
        } catch (_: Exception) { download(requested.toLanguageTag()) }
    }

    companion object {
        /** Exact region wins; a same-language installed pack beats an unavailable locale. */
        fun bestLanguage(requested: Locale, tags: List<String>): String? =
            tags.firstOrNull { it.equals(requested.toLanguageTag(), ignoreCase = true) }
                ?: tags.firstOrNull { Locale.forLanguageTag(it).language == requested.language }
    }

}

/**
 * Words out. Speech starts at the first sentence rather than at the end of
 * the answer, which is the single thing that makes this feel immediate.
 */
class Mouth(context: Context) {
    private val appContext = context.applicationContext
    private var engine: TextToSpeech? = null
    private var initialized = false
    private var ready = false
    private var closed = false
    private var muted = false
    private var finished = false
    private var spoken = 0
    private var utterance = 0
    private var generation = 0
    private val active = mutableSetOf<String>()
    private val pending = ArrayDeque<String>()
    private val main = Handler(Looper.getMainLooper())
    private var locale = Locale.getDefault()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            if (change < 0) fail("Playback paused because another app needs audio. Your answer stays on screen.")
        }.build()
    private var hasFocus = false
    private val controls = SpeechControls(context) { fail(it) }

    var onIdle: (() -> Unit)? = null
    var onProblem: ((String) -> Unit)? = null

    fun speaking(): Boolean = active.isNotEmpty() || pending.isNotEmpty()

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            main.post { started(status == TextToSpeech.SUCCESS) }
        }
    }

    private fun started(ok: Boolean) {
        if (closed) return
        initialized = true
        if (!ok) {
            fail("Speech output could not start. The answer is still available as text.")
            return
        }
        engine?.setAudioAttributes(attributes)
        engine?.setOnUtteranceProgressListener(progress)
        ready = pickVoice()
        if (!ready) {
            fail("No installed offline voice for ${locale.displayLanguage}. Add one in Text-to-speech settings.")
            return
        }
        while (pending.isNotEmpty() && !muted) enqueue(pending.removeFirst())
        idleIfFinished()
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) = settle(utteranceId, false)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = settle(utteranceId, false)
        @Deprecated("Required by the framework")
        override fun onError(utteranceId: String?) = settle(utteranceId, true)
        override fun onError(utteranceId: String?, errorCode: Int) = settle(utteranceId, true)

        private fun settle(id: String?, error: Boolean) {
            main.post {
                // An old utterance must never finish a newer answer.
                if (closed || !active.remove(id)) return@post
                if (error) fail("Could not read aloud. The answer is still available as text.")
                else idleIfFinished()
            }
        }
    }

    fun begin(locale: Locale) {
        ReadingService.pauseForCapture()
        hush()
        generation++
        muted = false
        finished = false
        spoken = 0
        this.locale = locale
        if (initialized) {
            ready = pickVoice()
            if (!ready) fail("No installed offline voice for ${locale.displayLanguage}. Add one in Text-to-speech settings.")
        }
    }

    fun follow(text: String) {
        if (muted || closed) return
        val (chunk, cursor) = Speech.nextChunk(text, spoken)
        if (chunk.isEmpty()) return
        spoken = cursor
        say(chunk)
    }

    fun finish(text: String) {
        if (closed) return
        if (!muted) {
            val tail = text.substring(minOf(spoken, text.length)).trim()
            spoken = text.length
            if (tail.isNotEmpty()) say(tail)
        }
        finished = true
        idleIfFinished()
    }

    fun hush() {
        generation++
        muted = true
        pending.clear()
        active.clear()
        engine?.stop()
        controls.stopped()
        releaseFocus()
        // Do not signal conversation completion between streamed sentences.
    }

    fun close() {
        if (closed) return
        closed = true
        onIdle = null
        onProblem = null
        hush()
        controls.close()
        engine?.shutdown()
        engine = null
        ready = false
    }

    private fun say(text: String) {
        // TTS rejects requests longer than getMaxSpeechInputLength().
        val limit = TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(1)
        var rest = text
        while (rest.isNotEmpty() && !muted) {
            var end = minOf(limit, rest.length)
            if (end < rest.length && end > 1 && rest[end - 1].isHighSurrogate()) end--
            val chunk = rest.take(end)
            rest = rest.drop(end)
            if (ready) enqueue(chunk) else if (!initialized) pending.addLast(chunk)
        }
    }

    private fun enqueue(chunk: String) {
        if (!ready || muted || closed) return
        if (!hasFocus) {
            hasFocus = audio?.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!hasFocus) {
                fail("Audio is in use. Try Read aloud again when it is free.")
                return
            }
        }
        val id = "sl-$generation-${utterance++}"
        active.add(id)
        controls.playing()
        val accepted = engine?.speak(chunk, TextToSpeech.QUEUE_ADD, null, id)
        if (accepted != TextToSpeech.SUCCESS) {
            active.remove(id)
            fail("Could not read aloud. The answer is still available as text.")
        }
    }

    private fun idleIfFinished() {
        if (finished && !speaking()) {
            controls.stopped()
            releaseFocus()
            onIdle?.invoke()
        }
    }

    private fun fail(message: String) {
        hush()
        onProblem?.invoke(message)
        idleIfFinished()
    }

    private fun releaseFocus() {
        if (hasFocus) audio?.abandonAudioFocusRequest(focus)
        hasFocus = false
    }

    private fun pickVoice(): Boolean {
        val tts = engine ?: return false
        return SpeechVoices.apply(appContext, tts, locale)
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

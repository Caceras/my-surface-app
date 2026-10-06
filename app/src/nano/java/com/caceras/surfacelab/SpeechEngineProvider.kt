package com.caceras.surfacelab

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Transcription engines for the Nano build.
 *
 * Google's GenAI Speech Recognition "Advanced" mode is the only first-party
 * on-device recogniser that documents Swedish on Pixel 10. It is an alpha SDK
 * and, like other GenAI APIs, may refuse to run while the app is not on
 * screen, so Android's own recogniser stays the fallback rather than being
 * replaced. Any SDK failure falls back instead of crashing a capture.
 */
object SpeechEngineProvider {
    private val ADVANCED = setOf("en-US","ko-KR","es-ES","fr-FR","de-DE","it-IT","pt-PT","cmn-Hans-CN","cmn-Hant-TW","ja-JP","th-TH","ru-RU","nl-NL","da-DK","sv-SE","pl-PL","hi-IN","vi-VN","id-ID","ar-SA","tr-TR")
    fun supports(locale: Locale) = locale.toLanguageTag() in ADVANCED || ADVANCED.any { Locale.forLanguageTag(it).language == locale.language && locale.country.isEmpty() }
    private fun advancedLocale(locale: Locale): Locale = Locale.forLanguageTag(ADVANCED.firstOrNull { it.equals(locale.toLanguageTag(), true) }
        ?: ADVANCED.first { Locale.forLanguageTag(it).language == locale.language })
    fun preferAndroid(context: Context) = context.getSharedPreferences("surfacelab", 0).getBoolean("transcribe_android", false)
    fun create(context: Context, locale: Locale): SpeechEngine =
        if (android.os.Build.VERSION.SDK_INT < 31 || preferAndroid(context) || !supports(locale)) PlatformSpeechEngine(context)
        else GenAiSpeechEngine(context.applicationContext, advancedLocale(locale))
    fun choice(context: Context): String = if (preferAndroid(context)) "Android on-device speech" else "Gemini on-device speech when available"
}

@android.annotation.TargetApi(31)
private class GenAiSpeechEngine(private val context: Context, private val modelLocale: Locale) : SpeechEngine {
    override val label = "Gemini on-device speech"
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Executor { main.post(it) }.asCoroutineDispatcher())
    private var job: Job? = null
    private var client: SpeechRecognizer? = null
    private var fallback: SpeechEngine? = null
    private var listener: SpeechEngine.Listener? = null
    private var running = false
    private var partial = ""
    private var lastFinal = ""
    private var failures = 0
    private var session = 0

    private fun options() = speechRecognizerOptions { locale = modelLocale; preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED }

    override fun start(locale: Locale, listener: SpeechEngine.Listener) {
        cancel(); this.listener = listener; running = true; failures = 0
        val token = ++session
        job = scope.launch {
            val recognizer = try { SpeechRecognition.getClient(options()) } catch (e: CancellationException) { throw e } catch (_: Throwable) {
                useFallback(locale, ""); return@launch
            }
            client = recognizer
            val status = try { recognizer.checkStatus() } catch (e: CancellationException) { throw e } catch (_: Throwable) { FeatureStatus.UNAVAILABLE }
            if (status != FeatureStatus.AVAILABLE) {
                if (status == FeatureStatus.DOWNLOADABLE) download()
                useFallback(locale, if (status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING) "Google's ${modelLocale.displayLanguage} speech model is downloading. Using Android speech until it is ready." else "")
                return@launch
            }
            while (running && token == session) {
                lastFinal = ""
                var heard = false
                val began = SystemClock.elapsedRealtime()
                try {
                    recognizer.startRecognition(speechRecognizerRequest { audioSource = AudioSource.fromMic() }).collect { response ->
                        if (token != session) return@collect
                        when (response) {
                            is SpeechRecognizerResponse.PartialTextResponse -> if (response.text.isNotBlank()) { heard = true; partial = response.text; listener.partial(response.text) }
                            is SpeechRecognizerResponse.FinalTextResponse -> {
                                heard = true; partial = ""; failures = 0
                                // Undocumented whether finals are cumulative; never store the same words twice.
                                val text = response.text.trim()
                                val fresh = if (lastFinal.isNotEmpty() && text.startsWith(lastFinal)) text.removePrefix(lastFinal).trim() else text
                                if (text.isNotEmpty()) lastFinal = text
                                if (fresh.isNotEmpty()) listener.final(fresh)
                            }
                            is SpeechRecognizerResponse.ErrorResponse -> throw response.e
                            else -> {}
                        }
                    }
                    flush()
                    if (!heard && SystemClock.elapsedRealtime() - began < PlatformSpeechEngine.QUICK_MS) failures++
                } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                    flush()
                    if (token != session) return@launch
                    val code = (e as? GenAiException)?.errorCode
                    if (code == GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED || code == GenAiException.ErrorCode.NOT_SUPPORTED || code == GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED) {
                        useFallback(locale, if (code == GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED) "Google's model only runs while Ægentica is on screen. Continuing with Android speech." else "Continuing with Android speech.")
                        return@launch
                    }
                    failures++
                }
                if (failures >= PlatformSpeechEngine.MAX_FAILURES) { running = false; closeClient(); listener.stopped(PlatformSpeechEngine.BLOCKED, true); return@launch }
                if (failures > 0) delay((400L shl (failures - 1).coerceIn(0, 4)).coerceAtMost(6400L))
            }
        }
    }

    private fun flush() { val heard = partial; partial = ""; if (heard.isNotBlank()) listener?.final(heard.trim()) }

    private fun useFallback(locale: Locale, note: String) {
        closeClient()
        if (!running) return
        val target = listener ?: return
        val next = PlatformSpeechEngine(context)
        fallback = next
        target.engine(next.label, note)
        next.start(locale, target)
    }

    /** A separate client so the download outlives this session's recogniser. */
    private fun download() {
        val downloader = try { SpeechRecognition.getClient(options()) } catch (_: Throwable) { return }
        CoroutineScope(SupervisorJob() + Executor { main.post(it) }.asCoroutineDispatcher()).launch {
            try { downloader.download().collect { if (it is DownloadStatus.DownloadCompleted || it is DownloadStatus.DownloadFailed) return@collect } }
            catch (e: CancellationException) { throw e } catch (_: Throwable) {}
            finally { runCatching { downloader.close() } }
        }
    }

    override fun finish(done: () -> Unit) {
        fallback?.let { running = false; fallback = null; it.finish(done); return }
        val active = job
        if (!running || active == null) { cancel(); done(); return }
        running = false
        var completed = false
        val complete = { if (!completed) { completed = true; flush(); cancel(); done() } }
        main.postDelayed({ complete() }, PlatformSpeechEngine.FINISH_MS)
        scope.launch {
            try { client?.stopRecognition() } catch (e: CancellationException) { throw e } catch (_: Throwable) {}
            active.join(); complete()
        }
    }

    override fun cancel() {
        running = false; session++
        job?.cancel(); job = null
        fallback?.cancel(); fallback = null
        closeClient()
    }

    private fun closeClient() { client?.let { runCatching { it.close() } }; client = null }
}

package com.caceras.surfacelab

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import java.util.Locale

/** Shared quality-first, installed-only policy for chat, Voice and the saved-text reader. */
object SpeechVoices {
    /**
     * Engines name languages differently: "en", "eng", or a malformed "en_US".
     * Offline engines built on older APIs (for example sherpa-onnx based ones)
     * often report three-letter codes, which a plain comparison never matched.
     */
    fun sameLanguage(a: Locale, b: Locale): Boolean = language(a) == language(b)
    private fun language(locale: Locale): String {
        val code = locale.language.lowercase(Locale.ROOT).substringBefore('_').substringBefore('-')
        return runCatching { Locale.forLanguageTag(code).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() } ?: code
    }

    fun ranked(voices: Collection<Voice>, locale: Locale): List<Voice> = voices.filter {
        sameLanguage(it.locale, locale) && !it.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
    }.sortedWith(compareByDescending<Voice> { it.quality }
        .thenBy { family(it) }
        .thenByDescending { it.locale == locale }
        .thenByDescending { it.locale.country == locale.country }
        .thenBy { it.latency }.thenBy { it.name })

    /**
     * sherpa-onnx engines such as HayaiTTS report every voice as normal
     * quality; prefer the stronger open model families by name.
     */
    private val families = listOf("kokoro", "supertonic", "kitten", "zipvoice", "pocket", "matcha", "piper")
    private fun family(voice: Voice): Int =
        families.indexOfFirst { it in voice.name.lowercase(Locale.ROOT) }.let { if (it < 0) families.size else it }

    fun choose(voices: Collection<Voice>, locale: Locale, preferred: String? = null): Voice? {
        val available = ranked(voices, locale)
        return available.firstOrNull { it.name == preferred } ?: available.firstOrNull()
    }

    private fun key(engine: String, locale: Locale) = "reading-voice:$engine:${locale.toLanguageTag()}"
    private fun preferred(context: Context, engine: String, locale: Locale) =
        context.getSharedPreferences("surfacelab", Context.MODE_PRIVATE).getString(key(engine, locale), null)
    private fun engineName(tts: TextToSpeech) = runCatching { tts.defaultEngine }.getOrNull().orEmpty()

    /** Sets the best installed offline voice; [engine] is the package [tts] is bound to. */
    fun apply(context: Context, tts: TextToSpeech, locale: Locale, engine: String = engineName(tts)): Boolean = try {
        val voice = choose(runCatching { tts.voices }.getOrNull().orEmpty(), locale, preferred(context, engine, locale))
        if (voice != null) tts.setVoice(voice) == TextToSpeech.SUCCESS else engineDefault(tts, locale)
    } catch (_: Exception) { false }

    /**
     * Some engines list no voices at all and only answer language checks. Use
     * the engine's own voice for the language then, unless it reports that the
     * voice is missing or needs the network.
     */
    fun engineDefault(tts: TextToSpeech, locale: Locale): Boolean {
        if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) return false
        val voice = runCatching { tts.voice }.getOrNull() ?: return true
        return !voice.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()
    }

    /** Preview is explicit, uses no user text, and stops on pause, dismissal or loss of focus. */
    fun showPicker(activity: Activity, changed: () -> Unit = {}) {
        ReadingService.pauseForCapture()
        val locale = Ears(activity).locale()
        val main = Handler(Looper.getMainLooper())
        val engines = SpeechEngines.installed(activity).sortedBy { it.name == SpeechEngines.GOOGLE }
        val info = activity.label("Loading installed voices...", 14f, true)
        val apps = RadioGroup(activity)
        val list = ListView(activity).apply { choiceMode = ListView.CHOICE_MODE_SINGLE }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            padDp(20, 8, 20, 0)
            // More than one voice app: let the owner pick, since one app failing should not end reading.
            if (engines.size > 1) {
                addView(activity.label("Voice app", 13f, true))
                engines.forEach { app -> apps.addView(RadioButton(activity).apply { id = View.generateViewId(); text = app.label; tag = app.name }) }
                addView(apps)
            }
            addView(info)
            addView(list, LinearLayout.LayoutParams(-1, activity.dp(220)))
            addView(activity.pill("Android voice settings") {
                runCatching { activity.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                    .onFailure { info.text = activity.getString(R.string.settings_unavailable) }
            })
        }
        var engine: TextToSpeech? = null
        var engineName: String? = null
        var voices: List<Voice> = emptyList()
        var selected: Voice? = null
        var ownVoice = false
        var loads = 0
        var closed = false
        var focusHeld = false
        var sampleGeneration = 0
        val audio = activity.getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        lateinit var focus: AudioFocusRequest
        fun stopSample() {
            sampleGeneration++
            engine?.stop()
            if (focusHeld) audio.abandonAudioFocusRequest(focus)
            focusHeld = false
        }
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { if (it < 0) stopSample() }.build()
        val dialog = AlertDialog.Builder(activity).setTitle("Reading voice").setView(body)
            .setPositiveButton("Use voice", null).setNeutralButton("Play sample", null)
            .setNegativeButton("Cancel", null).create()
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(a: Activity) { if (a === activity) stopSample() }
            override fun onActivityDestroyed(a: Activity) { if (a === activity) dialog.dismiss() }
            override fun onActivityCreated(a: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = Unit
        }
        dialog.setOnDismissListener {
            closed = true
            stopSample()
            engine?.shutdown()
            activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
        }
        activity.application.registerActivityLifecycleCallbacks(lifecycle)
        dialog.showProtected(activity)
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply { isEnabled = false }
        val sample = dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply { isEnabled = false }
        save.setOnClickListener {
            val name = engineName ?: return@setOnClickListener
            val voice = selected
            if (!ownVoice && voice == null) return@setOnClickListener
            val prefs = activity.getSharedPreferences("surfacelab", Context.MODE_PRIVATE).edit()
            if (voice == null) prefs.remove(key(name, locale)) else prefs.putString(key(name, locale), voice.name)
            prefs.apply()
            SpeechEngines.choose(activity, name)
            dialog.dismiss()
            changed()
        }
        sample.setOnClickListener {
            val tts = engine ?: return@setOnClickListener
            stopSample()
            focusHeld = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!focusHeld) { info.text = "Audio is in use. Try again when it is free."; return@setOnClickListener }
            val text = if (locale.language == "sv") "Det h\u00e4r \u00e4r ett r\u00f6stprov. Du kan \u00e4ndra l\u00e4shastigheten."
                else "This is a voice preview. You can change the reading speed."
            val voice = selected
            if ((voice != null && tts.setVoice(voice) != TextToSpeech.SUCCESS) ||
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice-preview:$sampleGeneration") != TextToSpeech.SUCCESS) {
                stopSample(); info.text = "This voice could not play. Try another voice or voice app."
            }
        }
        list.setOnItemClickListener { _, _, position, _ -> stopSample(); selected = voices.getOrNull(position) }
        fun loaded(tts: TextToSpeech, requested: String?, code: Int) {
            val name = requested ?: runCatching { tts.defaultEngine }.getOrNull().orEmpty()
            engineName = name
            val app = engines.firstOrNull { it.name == name }?.label ?: "Your voice app"
            apps.findViewWithTag<RadioButton>(name)?.isChecked = true
            tts.setAudioAttributes(attributes)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) { main.post { if (!closed && id == "voice-preview:$sampleGeneration") stopSample() } }
                @Deprecated("Framework callback") override fun onError(id: String?) { main.post { if (!closed && id == "voice-preview:$sampleGeneration") { stopSample(); info.text = "This voice could not play. Try another voice or voice app." } } }
            })
            voices = if (code == TextToSpeech.SUCCESS) runCatching { ranked(tts.voices.orEmpty(), locale) }.getOrDefault(emptyList()) else emptyList()
            if (voices.isEmpty()) {
                if (code == TextToSpeech.SUCCESS && runCatching { engineDefault(tts, locale) }.getOrDefault(false)) {
                    // The engine speaks the language but lists no voices: offer its own voice.
                    ownVoice = true
                    info.text = "${locale.displayName}. $app uses its own voice for this language."
                    save.isEnabled = true; sample.isEnabled = true
                    return
                }
                val other = if (engines.size > 1) " Choose another voice app above, or add a voice in $app." else " Add a voice in $app."
                val listed = runCatching { tts.voices?.size ?: 0 }.getOrDefault(0)
                info.text = (if (code != TextToSpeech.SUCCESS) "$app did not start."
                    else "$app lists $listed ${if (listed == 1) "voice" else "voices"}, none offline for ${locale.displayLanguage}.") + other
                return
            }
            selected = choose(voices, locale, preferred(activity, name, locale))
            info.text = "$app \u00b7 ${voices.size} offline ${if (voices.size == 1) "voice" else "voices"} for ${locale.displayLanguage}"
            list.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_single_choice,
                voices.map { voice -> "${displayName(voice)}\n${voice.locale.displayName} \u00b7 ${quality(voice)}" })
            list.setItemChecked(voices.indexOf(selected), true)
            save.isEnabled = true; sample.isEnabled = true
        }
        fun load(requested: String?) {
            stopSample()
            engine?.shutdown()
            val attempt = ++loads
            engineName = null; voices = emptyList(); selected = null; ownVoice = false
            list.adapter = null
            save.isEnabled = false; sample.isEnabled = false
            info.text = "Loading installed voices..."
            lateinit var created: TextToSpeech
            val listener = TextToSpeech.OnInitListener { code -> main.post { if (!closed && attempt == loads) loaded(created, requested, code) } }
            created = if (requested == null) TextToSpeech(activity.applicationContext, listener)
                else TextToSpeech(activity.applicationContext, listener, requested)
            engine = created
        }
        apps.setOnCheckedChangeListener { group, id ->
            // Checking the engine that just loaded must not load it again.
            (group.findViewById<RadioButton>(id)?.tag as? String)?.let { if (it != engineName) load(it) }
        }
        load(SpeechEngines.order(engines.map { it.name }, SpeechEngines.chosen(activity)).firstOrNull())
    }

    /** A readable name. HayaiTTS names multi-speaker voices "model#id#speaker". */
    fun displayName(voice: Voice): String {
        val parts = voice.name.split("#", limit = 3)
        val model = parts[0].replace('_', ' ').replace('-', ' ').trim()
        val speaker = parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
        return if (speaker != null) "$speaker \u00b7 $model" else model
    }

    private fun quality(voice: Voice) = when {
        voice.quality >= Voice.QUALITY_HIGH -> "High quality"
        voice.quality >= Voice.QUALITY_NORMAL -> "Standard quality"
        else -> "Basic quality"
    }
}

/** The speech engines installed on the phone, and which one Ægentica uses. */
object SpeechEngines {
    const val GOOGLE = "com.google.android.tts"
    private const val KEY = "speech-engine"

    data class Engine(val name: String, val label: String)

    fun installed(context: Context): List<Engine> = runCatching {
        val packages = context.packageManager
        packages.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .mapNotNull { it.serviceInfo }.distinctBy { it.packageName }
            .map { Engine(it.packageName, it.loadLabel(packages).toString()) }
    }.getOrDefault(emptyList())

    /** The engine picked under Reading voice, if it is still installed; null means Android's default. */
    fun chosen(context: Context): String? = context.getSharedPreferences("surfacelab", Context.MODE_PRIVATE)
        .getString(KEY, null)?.takeIf { chosen -> installed(context).any { it.name == chosen } }

    fun choose(context: Context, engine: String) {
        context.getSharedPreferences("surfacelab", Context.MODE_PRIVATE).edit().putString(KEY, engine).apply()
    }

    /**
     * Engines to try, best first: the one chosen in Ægentica, then the
     * open-source engines the owner installed (HayaiTTS and the like). Google's
     * engine is used only when chosen, or when it is the only one installed.
     */
    fun order(installed: List<String>, chosen: String?): List<String> {
        val open = installed.filter { it != GOOGLE }
        return when {
            chosen != null && chosen in installed -> listOf(chosen) + open.filter { it != chosen }
            open.isNotEmpty() -> open
            else -> installed
        }
    }
}

/**
 * Opens a speech engine that can speak offline, in [SpeechEngines.order]: the
 * engine chosen in Ægentica, then the installed open-source engines. When one
 * fails to start or has no usable voice for the language, the next is tried.
 * Android's own default is used only when no engine can be listed. [done] runs
 * once per opening, on the main thread, with the working engine, or with null
 * and whether any engine started at all.
 */
class VoiceOutput(
    context: Context,
    private val locale: () -> Locale,
    private val done: (TextToSpeech?, Boolean) -> Unit,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val tried = mutableSetOf<String>()
    private var started = false
    private var closed = false
    var tts: TextToSpeech? = null; private set
    /** Package of the engine in use. */
    var name: String? = null; private set

    private var plan = emptyList<String>()

    init { start() }

    private fun start() {
        plan = SpeechEngines.order(SpeechEngines.installed(app).map { it.name }, SpeechEngines.chosen(app))
        open(plan.firstOrNull())
    }

    private fun open(engine: String?) {
        lateinit var created: TextToSpeech
        // An engine that cannot bind reports from inside the constructor; posting waits for `created`.
        val listener = TextToSpeech.OnInitListener { code -> main.post { opened(created, engine, code) } }
        created = if (engine == null) TextToSpeech(app, listener) else TextToSpeech(app, listener, engine)
        tts = created
    }

    private fun opened(engine: TextToSpeech, requested: String?, code: Int) {
        if (closed || engine !== tts) return
        val actual = requested ?: runCatching { engine.defaultEngine }.getOrNull().orEmpty()
        if (code == TextToSpeech.SUCCESS) {
            started = true
            if (SpeechVoices.apply(app, engine, locale(), actual)) { name = actual; done(engine, true); return }
        }
        tried += actual
        engine.shutdown()
        tts = null
        val next = plan.firstOrNull { it !in tried }
        if (next != null) open(next) else done(null, started)
    }

    /** Start over, for a new language or a newly chosen voice. */
    fun reopen() {
        if (closed) return
        tts?.shutdown()
        tts = null; name = null; started = false
        tried.clear()
        start()
    }

    fun close() {
        closed = true
        tts?.shutdown()
        tts = null
    }
}

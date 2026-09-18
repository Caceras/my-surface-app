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
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import java.util.Locale

/** Shared quality-first, installed-only policy for chat, Voice and the saved-text reader. */
object SpeechVoices {
    fun ranked(voices: Collection<Voice>, locale: Locale): List<Voice> = voices.filter {
        it.locale.language == locale.language && !it.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
    }.sortedWith(compareByDescending<Voice> { it.quality }
        .thenByDescending { it.locale == locale }
        .thenByDescending { it.locale.country == locale.country }
        .thenBy { it.latency }.thenBy { it.name })

    fun choose(voices: Collection<Voice>, locale: Locale, preferred: String? = null): Voice? {
        val available = ranked(voices, locale)
        return available.firstOrNull { it.name == preferred } ?: available.firstOrNull()
    }

    private fun key(tts: TextToSpeech, locale: Locale) = "reading-voice:${tts.defaultEngine}:${locale.toLanguageTag()}"
    private fun preferred(context: Context, tts: TextToSpeech, locale: Locale) =
        context.getSharedPreferences("surfacelab", Context.MODE_PRIVATE).getString(key(tts, locale), null)

    fun apply(context: Context, tts: TextToSpeech, locale: Locale): Boolean = try {
        val voice = choose(tts.voices.orEmpty(), locale, preferred(context, tts, locale))
        voice != null && tts.setVoice(voice) == TextToSpeech.SUCCESS
    } catch (_: Exception) { false }

    /** Preview is explicit, uses no user text, and stops on pause, dismissal or loss of focus. */
    fun showPicker(activity: Activity, changed: () -> Unit = {}) {
        ReadingService.pauseForCapture()
        val locale = Ears(activity).locale()
        val main = Handler(Looper.getMainLooper())
        val info = activity.label("Loading installed voices...", 14f, true)
        val list = ListView(activity).apply { choiceMode = ListView.CHOICE_MODE_SINGLE }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            padDp(20, 8, 20, 0)
            addView(info)
            addView(list, LinearLayout.LayoutParams(-1, activity.dp(240)))
            addView(activity.flatButton("Android voice settings") {
                runCatching { activity.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                    .onFailure { info.text = activity.getString(R.string.settings_unavailable) }
            })
        }
        var engine: TextToSpeech? = null
        var selected: Voice? = null
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
            val voice = selected ?: return@setOnClickListener
            val tts = engine ?: return@setOnClickListener
            activity.getSharedPreferences("surfacelab", Context.MODE_PRIVATE).edit()
                .putString(key(tts, locale), voice.name).apply()
            dialog.dismiss()
            changed()
        }
        sample.setOnClickListener {
            val tts = engine ?: return@setOnClickListener
            val voice = selected ?: return@setOnClickListener
            stopSample()
            focusHeld = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!focusHeld) { info.text = "Audio is in use. Try again when it is free."; return@setOnClickListener }
            val text = if (locale.language == "sv") "Det h\u00e4r \u00e4r ett r\u00f6stprov. Du kan \u00e4ndra l\u00e4shastigheten."
                else "This is a voice preview. You can change the reading speed."
            if (tts.setVoice(voice) != TextToSpeech.SUCCESS ||
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice-preview:$sampleGeneration") != TextToSpeech.SUCCESS) {
                stopSample(); info.text = "This voice could not play. Try another installed voice."
            }
        }
        engine = TextToSpeech(activity.applicationContext) { code -> main.post {
            if (closed) return@post
            val tts = engine ?: return@post
            val voices = if (code == TextToSpeech.SUCCESS) runCatching { ranked(tts.voices.orEmpty(), locale) }.getOrDefault(emptyList()) else emptyList()
            if (voices.isEmpty()) {
                info.text = "No installed offline voice for ${locale.displayLanguage}. Add a voice in Android voice settings."
                return@post
            }
            selected = choose(voices, locale, preferred(activity, tts, locale))
            info.text = "${locale.displayName}. These voices work offline."
            list.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_single_choice,
                voices.mapIndexed { n, voice -> "Voice ${n + 1} \u00b7 ${quality(voice)}\n${voice.locale.displayName}" })
            list.setItemChecked(voices.indexOf(selected), true)
            list.setOnItemClickListener { _, _, position, _ -> stopSample(); selected = voices[position] }
            tts.setAudioAttributes(attributes)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) { main.post { if (!closed && id == "voice-preview:$sampleGeneration") stopSample() } }
                @Deprecated("Framework callback") override fun onError(id: String?) { main.post { if (!closed && id == "voice-preview:$sampleGeneration") { stopSample(); info.text = "This voice could not play. Try another voice." } } }
            })
            save.isEnabled = true; sample.isEnabled = true
        } }
    }

    private fun quality(voice: Voice) = when {
        voice.quality >= Voice.QUALITY_HIGH -> "High quality"
        voice.quality >= Voice.QUALITY_NORMAL -> "Standard quality"
        else -> "Basic quality"
    }
}

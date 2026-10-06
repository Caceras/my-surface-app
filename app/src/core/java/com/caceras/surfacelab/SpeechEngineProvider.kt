package com.caceras.surfacelab

import android.content.Context
import java.util.Locale

/** The framework build transcribes with Android's on-device recogniser only. */
object SpeechEngineProvider {
    fun create(context: Context, locale: Locale): SpeechEngine = PlatformSpeechEngine(context)
    fun choice(context: Context): String = "Android on-device speech"
}

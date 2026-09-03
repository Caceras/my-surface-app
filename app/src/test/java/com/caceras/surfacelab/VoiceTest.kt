package com.caceras.surfacelab

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSpeechRecognizer
import org.robolectric.shadows.ShadowTextToSpeech

/**
 * The voice path, on the JVM.
 *
 * This is the half of the app that can actually be tested: the nano brain
 * needs AICore, which exists on no CI runner and no emulator, but speech is
 * framework API all the way down and Robolectric shadows both ends of it.
 *
 * The brain here is a stand-in that streams on demand, because the two
 * behaviours most worth proving -- that the first sentence is spoken before
 * the answer finishes, and that barge-in survives the rest of it arriving --
 * need a stream, and CoreBrain answers in one go through onResult.
 */
@RunWith(AndroidJUnit4::class)
class VoiceTest {

    private val brain = StreamingBrain()

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        Brains.useForTest(brain)
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @After
    fun tearDown() {
        Brains.useForTest(null)
        ShadowTextToSpeech.reset()
        ShadowSpeechRecognizer.reset()
    }

    // ------------------------------------------------------------- fixture

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private fun open(): ActivityController<VoiceActivity> =
        Robolectric.buildActivity(VoiceActivity::class.java).setup()

    private fun descendants(view: View): List<View> =
        if (view !is ViewGroup) listOf(view)
        else listOf(view) + (0 until view.childCount).flatMap {
            descendants(view.getChildAt(it))
        }

    private fun texts(activity: VoiceActivity) =
        descendants(activity.findViewById<View>(android.R.id.content))
            .filterIsInstance<TextView>()
            .map { it.text.toString() }

    private fun recognizer() = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())

    private fun bundle(text: String) = Bundle().apply {
        putStringArrayList(
            SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)
        )
    }

    /**
     * Finish a spoken turn: the recogniser hands over the whole transcript.
     *
     * The engine is brought up straight afterwards because TTS init is
     * asynchronous on a real phone and Robolectric never fires onInit at
     * all -- so a test that skipped this would be testing an engine that
     * never started.
     */
    private fun say(text: String) {
        recognizer().triggerOnResults(bundle(text))
        drain()
        engineReady()
    }

    private fun engineReady() {
        val engine = ShadowTextToSpeech.getLastTextToSpeechInstance() ?: return
        shadowOf(engine).onInitListener.onInit(TextToSpeech.SUCCESS)
        drain()
    }

    private fun spoken(): List<String> =
        shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).spokenTextList

    /** Robolectric holds posted work until asked; Mouth posts back to main. */
    private fun drain() = shadowOf(Looper.getMainLooper()).idle()

    private fun tap(activity: VoiceActivity) {
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
        activity.dispatchTouchEvent(down)
        down.recycle()
        drain()
    }

    // --------------------------------------------------------------- tests

    @Test
    fun `the recogniser asked for is the on-device one, with partial results`() {
        open()
        val asked = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        assertTrue("no recogniser was created at all", asked != null)

        val intent = recognizer().lastRecognizerIntent
        assertTrue(
            "partial results were not requested, so the screen stays blank " +
                "while you talk",
            intent.getBooleanExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        )
        assertEquals(
            "the language model extra is marked required by the reference",
            android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            intent.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL)
        )
    }

    @Test
    fun `there is no microphone when speech cannot stay on the device`() {
        // No on-device recogniser means an honest status, not a fallback that
        // ships audio to a server. A shortcut promising "tap, talk" on a
        // phone that cannot is worse than no shortcut.
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        val activity = open().get()

        assertNull(
            "a recogniser was created anyway",
            ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        )
        assertTrue(
            "the screen did not say why there is no microphone",
            texts(activity).any {
                it == activity.getString(R.string.voice_unavailable)
            }
        )
    }

    @Test
    fun `partials reach the screen while you are still talking`() {
        val activity = open().get()
        recognizer().triggerOnPartialResults(bundle("what is a"))
        drain()

        assertTrue(
            "nothing was shown while listening",
            texts(activity).any { it == "what is a" }
        )
        assertEquals("a partial should not have been sent anywhere", 0, brain.runs)
    }

    @Test
    fun `silence is the send button, and it presses it exactly once`() {
        open()
        say("how tall is everest")

        assertEquals("the brain did not run once on the transcript", 1, brain.runs)
        assertEquals("how tall is everest", brain.instruction)
    }

    @Test
    fun `the first sentence is spoken before the answer has finished`() {
        open()
        say("tell me about everest")

        brain.emit("It is tall.")
        drain()

        assertEquals(
            "the first finished sentence was not spoken until the end",
            listOf("It is tall."),
            spoken()
        )
        assertFalse("the answer was not still streaming", brain.done)
    }

    @Test
    fun `a last sentence with no full stop is still spoken`() {
        // "Sure" printed but never spoken is the version of this bug the user
        // notices, because the shortest answers are the ones that break.
        open()
        say("are you there")

        brain.emit("Sure")
        drain()
        assertEquals("nothing should be spoken yet", emptyList<String>(), spoken())

        brain.complete("Sure")
        drain()
        assertEquals(listOf("Sure"), spoken())
    }

    @Test
    fun `barge-in stays quiet while the rest of the answer arrives`() {
        // stop() only clears what is already queued. Without a muted chunker
        // the next sentence boundary starts it talking again half a second
        // later, which is what makes an assistant feel like an appliance.
        val activity = open().get()
        say("tell me everything")

        brain.emit("One.")
        drain()
        assertEquals(listOf("One."), spoken())

        tap(activity)

        brain.emit("One. Two. Three.")
        drain()
        brain.complete("One. Two. Three. And more")
        drain()

        assertEquals("it started talking again after barge-in",
            listOf("One."), spoken())
    }

    @Test
    fun `sentences finished before the engine started are spoken, in order`() {
        // TTS init is asynchronous, and by the time it lands the brain may
        // already have streamed several sentences. One slot that each new
        // chunk overwrites starts the answer from the middle; this is why
        // Mouth holds a queue.
        open()
        recognizer().triggerOnResults(bundle("say three things"))
        drain()

        brain.emit("One.")
        brain.emit("One. Two.")
        brain.emit("One. Two. Three.")
        drain()
        assertEquals("the engine had not started yet",
            emptyList<String>(), spoken())

        engineReady()

        assertEquals(listOf("One.", "Two.", "Three."), spoken())
    }

    @Test
    fun `the answer comes back in the language it was asked in`() {
        // Not the device default, or Swedish is read back in an English
        // accent. The locale is remembered until the engine can take it.
        open()
        say("nagot pa svenska")
        brain.emit("Javisst.")
        drain()

        val engine = ShadowTextToSpeech.getLastTextToSpeechInstance()
        assertEquals(Ears(app()).locale(), shadowOf(engine).currentLanguage)
    }

    @Test
    fun `the microphone and the engine are both released on the way out`() {
        // The mic leak breaks recognition in other apps until the process
        // dies; the engine leak binds another connection on every trip
        // through the tile.
        val controller = open()
        say("say something")
        brain.emit("Talking.")
        drain()

        val ears = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        val mouth = ShadowTextToSpeech.getLastTextToSpeechInstance()

        controller.pause().stop().destroy()
        drain()

        assertTrue("the recogniser was never destroyed", shadowOf(ears).isDestroyed)
        assertTrue("the engine was stopped but never shut down",
            shadowOf(mouth).isShutdown)
    }

    @Test
    fun `saying nothing is not an error`() {
        val activity = open().get()
        recognizer().triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        drain()

        assertNull("a dialog was raised for an ordinary silent turn",
            ShadowDialog.getLatestDialog())
        assertTrue(
            "the screen did not return to idle",
            texts(activity).any { it == activity.getString(R.string.tap_to_talk) }
        )
    }

    @Test
    fun `an answer that arrives after the screen is gone changes nothing`() {
        // SurfaceBrain.run takes no cancellation token, so its callbacks
        // arrive whether or not anyone is still here. Without the flag a
        // chunk lands on a dead view, speak() is called on an engine already
        // shut down, and an answer nobody waited for reaches the widget.
        val controller = open()
        say("ask something")
        controller.pause().stop().destroy()
        drain()

        brain.emit("Late answer.")
        brain.complete("Late answer.")
        drain()

        assertNull("a discarded answer reached the widget",
            ResultStore.lastText(app()))
    }
}

/**
 * A brain that streams when the test says so.
 *
 * Twelve lines, and it never ships: CoreBrain answers synchronously through
 * onResult and never calls onPartial, so without this the two tests that
 * matter most above would have no stream to run against.
 */
private class StreamingBrain : SurfaceBrain {

    override val tasks = listOf(Task.ASK)

    var runs = 0
    var instruction = ""
    var done = false

    private var partial: ((String) -> Unit)? = null
    private var result: ((BrainResult) -> Unit)? = null

    override fun status(context: Context, onStatus: (BrainStatus) -> Unit) =
        onStatus(BrainStatus("Stand-in", ready = true))

    override fun prepare(context: Context, onStatus: (BrainStatus) -> Unit) =
        status(context, onStatus)

    override fun run(
        context: Context,
        task: Task,
        input: String,
        instruction: String,
        onPartial: (String) -> Unit,
        onResult: (BrainResult) -> Unit
    ) {
        runs++
        this.instruction = instruction
        done = false
        partial = onPartial
        result = onResult
    }

    /** One more chunk of a streamed answer, as the model would produce it. */
    fun emit(text: String) = partial?.invoke(text) ?: Unit

    /** The end of the answer. */
    fun complete(text: String) {
        done = true
        result?.invoke(BrainResult(text, ok = true))
    }
}

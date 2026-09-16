package com.caceras.surfacelab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws the real screens to PNG files.
 *
 * Every layout bug this project has shipped was obvious in one second on a
 * phone and invisible to everything else: padding in pixels, a title bar
 * drawn twice, a screen described in a commit message by someone who had
 * never seen it. There is no Android SDK on the machine these changes are
 * written on and no emulator in CI, so "how does it look" has been answered
 * by guessing.
 *
 * Robolectric's native graphics mode rasterises for real -- the same Skia the
 * device uses. So the screens can be rendered on the JVM and uploaded as
 * build artefacts, and the question stops being a guess.
 *
 * These are not assertions about beauty. They assert only that something was
 * drawn; the point is the file, which a person (or a model that cannot open
 * an emulator) can then actually look at.
 *
 *     gradle testCoreDebugUnitTest
 *
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class ShotTest {

    private val width = 1233   // 411dp at xxhdpi, a Pixel in portrait
    private val height = 2742

    /**
     * [minPainted] is the fraction of the screen that must not still be the
     * erase colour. The first version of this only asked for two distinct
     * colours, and passed on a screen that was ninety per cent unpainted --
     * an assertion weak enough to be worthless.
     */
    private fun shoot(name: String, decor: View, minPainted: Double = 0.9, shotWidth: Int = width, shotHeight: Int = height, afterLayout: () -> Unit = {}) {
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(shotWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(shotHeight, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, shotWidth, shotHeight)

        // Drain immediate layout work posted by Dialog.show / setLayout before
        // accepting a frame. A first measure alone can miss a header control.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        fun relayout(view: View) {
            view.forceLayout()
            if (view is android.view.ViewGroup) (0 until view.childCount).forEach { relayout(view.getChildAt(it)) }
        }
        relayout(decor)
        decor.measure(View.MeasureSpec.makeMeasureSpec(shotWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(shotHeight, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, shotWidth, shotHeight)
        decor.viewTreeObserver.dispatchOnPreDraw()
        decor.findViewWithTag<android.view.ViewGroup>("sheet-header")?.let { header ->
            val done = header.getChildAt(1)
            val rect = android.graphics.Rect()
            assertTrue("$name close action is missing", done.getGlobalVisibleRect(rect))
            assertTrue("$name close action is clipped", rect.width() >= done.context.dp(48) && rect.height() >= done.context.dp(48))
            val origin = IntArray(2); decor.getLocationOnScreen(origin)
            rect.offset(-origin[0], -origin[1])
            assertTrue("$name close action is outside the capture", android.graphics.Rect(0, 0, shotWidth, shotHeight).contains(rect))
        }

        afterLayout()
        val bitmap = Bitmap.createBitmap(shotWidth, shotHeight, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)   // so "drew nothing" is unmistakable
        decor.draw(Canvas(bitmap))
        // A real frame traversal can schedule drawable/text invalidation. Draw
        // the settled tree again rather than accepting a half-painted first frame.
        decor.viewTreeObserver.dispatchOnPreDraw()
        decor.invalidate()
        bitmap.eraseColor(Color.MAGENTA)
        decor.draw(Canvas(bitmap))

        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        // A screen that drew nothing is a screenshot of the erase colour.
        val pixels = IntArray(shotWidth * shotHeight)
        bitmap.getPixels(pixels, 0, shotWidth, 0, 0, shotWidth, shotHeight)
        val painted = pixels.count { it != Color.MAGENTA }.toDouble() / pixels.size
        assertTrue(
            "$name painted only ${"%.1f".format(painted * 100)}% of the screen, " +
                "expected at least ${"%.0f".format(minPainted * 100)}%",
            painted >= minPainted
        )
    }

    @Test
    fun `the chat screen, empty`() {
        Chat.clear(RuntimeEnvironment.getApplication())
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shoot("chat-empty", activity.window.decorView)
    }

    @Test
    fun `the chat screen, mid conversation`() {
        Chat.save(RuntimeEnvironment.getApplication(), listOf(
            Turn("Give me three ideas for a Sunday",
                 "**Walk somewhere new.** Pick a street you have never turned down.\n" +
                 "* Cook something slow -- a stew, bread, anything that takes hours.\n" +
                 "* Call the person you keep meaning to call."),
            Turn("list 10 more",
                 "1. Read in a park\n2. Fix the thing that has been broken\n" +
                 "3. Swim\n4. Write a letter\n5. Cycle with no destination")
        ))
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shoot("chat-conversation", activity.window.decorView)
    }

    @Test
    fun `the hands-free screen`() {
        val application = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        org.robolectric.shadows.ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val activity = Robolectric.buildActivity(VoiceActivity::class.java).setup().get()
        shoot("voice", activity.window.decorView, minPainted = 0.05)
    }
    @Test
    fun `the settings panel remains contained`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        fun children(view: View): List<View> = if (view !is android.view.ViewGroup) listOf(view)
            else listOf(view) + (0 until view.childCount).flatMap { children(view.getChildAt(it)) }
        children(activity.window.decorView).filterIsInstance<android.widget.TextView>()
            .first { it.text == activity.getString(R.string.more) }.performClick()
        shoot("chat-settings", org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView)
    }

    @Test
    fun `voice setup with an explicit path back to typing`() {
        val activity = Robolectric.buildActivity(VoiceActivity::class.java,
            android.content.Intent().putExtra("setup", true)).setup().get()
        shoot("voice-setup", activity.window.decorView)
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-night-xxhdpi")
    fun `chat in dark mode`() {
        Chat.clear(RuntimeEnvironment.getApplication())
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shoot("chat-night", activity.window.decorView)
    }

    @Test
    fun `missing speech language keeps setup and typing reachable`() {
        val application = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        org.robolectric.shadows.ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val activity = Robolectric.buildActivity(VoiceActivity::class.java).setup().get()
        org.robolectric.Shadows.shadowOf(org.robolectric.shadows.ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            .triggerOnError(android.speech.SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        shoot("voice-error", activity.window.decorView)
    }

    @Test
    fun `voice answer streams into the native screen`() {
        val application = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        org.robolectric.shadows.ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val brain = StreamingBrain()
        Brains.useForTest(brain)
        try {
            val activity = Robolectric.buildActivity(VoiceActivity::class.java).setup().get()
            org.robolectric.Shadows.shadowOf(org.robolectric.shadows.ShadowSpeechRecognizer.getLatestSpeechRecognizer())
                .triggerOnResults(android.os.Bundle().apply {
                    putStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                        arrayListOf("Help me slow down for a minute."))
                })
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            brain.emit("Let’s make a little space.\n\nPut down what you’re carrying. Take one slow breath.\n\nWhat is the one thing that needs your attention next?")
            shoot("voice-answer", activity.window.decorView)
        } finally { Brains.useForTest(null) }
    }
    @Test
    fun `saved conversations have searchable previews`() {
        val context = RuntimeEnvironment.getApplication()
        Chat.clearArchives(context)
        Chat.save(context, listOf(Turn("A slower Sunday", "A long walk, good coffee, and a little time to read.")))
        Chat.archiveCurrent(context, "Find a quiet place nearby")
        Chat.save(context, listOf(Turn("The right words", "Thank you for making time. I would love to hear what you think.")))
        Chat.archiveCurrent(context, "")
        Chat.clear(context)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val sheet = ConversationSheet(activity) {}
        sheet.show()
        shoot("conversations", sheet.window!!.decorView)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xxhdpi")
    fun `compact chat keeps playback and navigation reachable`() {
        val context = RuntimeEnvironment.getApplication()
        Chat.save(context, listOf(Turn("A quick thought", "Make space for one thing at a time.")))
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        fun children(view: View): List<View> = if (view !is android.view.ViewGroup) listOf(view)
            else listOf(view) + (0 until view.childCount).flatMap { children(view.getChildAt(it)) }
        children(activity.window.decorView).filterIsInstance<android.widget.TextView>()
            .first { it.text == "Listen" }.performClick()
        shoot("chat-compact", activity.window.decorView, shotWidth = 960, shotHeight = 1920)
        listOf("Voice", "History", activity.getString(R.string.stop_speaking)).forEach { label ->
            val control = children(activity.window.decorView).filterIsInstance<android.widget.TextView>().first { it.text == label }
            val rect = android.graphics.Rect()
            assertTrue("$label is not visible", control.getGlobalVisibleRect(rect))
            assertTrue("$label is clipped", rect.height() >= activity.dp(48))
        }
    }


    @Test
    fun `native phone actions are readable and branded`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val dialog = NativeActions.show(activity)
        shoot("aegentica-actions", dialog.window!!.decorView)
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xxhdpi")
    fun `landscape voice keeps the transcript and controls reachable`() {
        val activity = Robolectric.buildActivity(VoiceActivity::class.java,
            android.content.Intent().putExtra("setup", true)).setup().get()
        shoot("aegentica-voice-landscape", activity.window.decorView, shotWidth = 2742, shotHeight = 1233)
        val scroll = activity.window.decorView.findViewWithTag<ReadingScrollView>("voice-transcript")
        assertTrue("voice text has no viewport", scroll.height >= activity.dp(72))
        val rect = android.graphics.Rect()
        assertTrue(scroll.getGlobalVisibleRect(rect))
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp-xxhdpi")
    fun `wide chat bounds the reading column`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shoot("aegentica-wide", activity.window.decorView, shotWidth = 3000, shotHeight = 2400)
        val frame = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as AdaptiveFrame
        assertTrue("reading column stretches across a desktop", frame.getChildAt(0).width <= activity.dp(720))
    }

    @Test
    fun `large font chat keeps navigation visible`() {
        val context = RuntimeEnvironment.getApplication()
        val config = android.content.res.Configuration(context.resources.configuration).apply { fontScale = 2f }
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shoot("aegentica-large-font", activity.window.decorView)
        fun children(v: View): List<View> = listOf(v) + if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { children(v.getChildAt(it)) } else emptyList()
        listOf("Voice", "History", "Actions").forEach { name ->
            val button = children(activity.window.decorView).filterIsInstance<android.widget.TextView>().first { it.text == name }
            val rect = android.graphics.Rect()
            assertTrue("$name missing at large text", button.getGlobalVisibleRect(rect))
            assertTrue("$name clipped at large text", rect.height() >= activity.dp(48))
        }
    }

    @Test
    fun `compact widget draws both touch targets without answer exposure`() {
        val context = RuntimeEnvironment.getApplication()
        val view = SurfaceWidgetProvider().views(context, true).apply(context, android.widget.FrameLayout(context))
        val host = android.widget.FrameLayout(context).apply {
            setBackgroundColor(context.getColor(R.color.chat_bg))
            padDp(4, 4, 4, 4)
            addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        shoot("aegentica-widget", host, shotWidth = context.dp(240), shotHeight = context.dp(110))
        listOf(R.id.widget_type, R.id.widget_talk).forEach { id ->
            val button = view.findViewById<View>(id)
            assertTrue("widget target clipped", button.height >= context.dp(48))
        }
    }

    @Test
    fun `the adaptive icon renders the AE signum`() {
        val context = RuntimeEnvironment.getApplication()
        val view = android.widget.ImageView(context).apply {
            setBackgroundColor(context.getColor(R.color.chat_bg))
            setImageResource(R.mipmap.ic_launcher)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            padDp(24, 24, 24, 24)
        }
        shoot("aegentica-icon", view, shotWidth = context.dp(180), shotHeight = context.dp(180))
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-night-xxhdpi")
    fun `settings preserve the design system in dark mode`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        fun children(v: View): List<View> = listOf(v) + if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { children(v.getChildAt(it)) } else emptyList()
        children(activity.window.decorView).filterIsInstance<android.widget.TextView>().first { it.text == "Settings" }.performClick()
        shoot("cohesion-settings-night", org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView)
    }

    @Test
    fun `timer form uses the shared private field treatment`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val sheet = NativeActions.show(activity)
        fun children(v: View): List<View> = listOf(v) + if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { children(v.getChildAt(it)) } else emptyList()
        children(sheet.window!!.decorView).filterIsInstance<android.widget.TextView>().first { it.text == "Set a timer" }.performClick()
        val form = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        // Render the dialog content on an explicit host; transparent window margins are not UI.
        val content = form.findViewById<android.view.ViewGroup>(android.R.id.content)
        val host = android.widget.FrameLayout(activity).apply { setBackgroundColor(activity.getColor(R.color.chat_bg)) }
        val parent = content.parent as android.view.ViewGroup
        parent.removeView(content)
        host.addView(content, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER))
        shoot("cohesion-timer", host, shotWidth = 1233, shotHeight = 1233)
    }

    @Test fun `settings feedback stays reachable at the end of the sheet`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        fun children(v: View): List<View> = listOf(v) + if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { children(v.getChildAt(it)) } else emptyList()
        children(activity.window.decorView).filterIsInstance<android.widget.TextView>().first { it.text == "Settings" }.performClick()
        val decor = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
        shoot("settings-feedback", decor) {
            val scroll = decor.findViewWithTag<android.widget.ScrollView>("settings")
            scroll.scrollTo(0, scroll.getChildAt(0).height)
            val copy = children(decor).filterIsInstance<android.widget.TextView>().first { it.text == "Copy app info" }
            val rect = android.graphics.Rect()
            assertTrue(copy.getGlobalVisibleRect(rect))
            assertTrue(rect.height() >= activity.dp(48))
        }
    }

    private fun selectionPrompt(): android.app.AlertDialog {
        Robolectric.buildActivity(ProcessTextActivity::class.java, android.content.Intent().apply {
            component = android.content.ComponentName("com.caceras.surface", "com.caceras.surfacelab.Ask")
            putExtra(android.content.Intent.EXTRA_PROCESS_TEXT, "A short piece of selected text, ready to discuss.")
            putExtra(android.content.Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }).setup()
        return org.robolectric.shadows.ShadowDialog.getLatestDialog() as android.app.AlertDialog
    }

    private fun dialogShot(name: String, form: android.app.Dialog, context: android.content.Context) {
        val content = form.findViewById<android.view.ViewGroup>(android.R.id.content)
        (content.parent as android.view.ViewGroup).removeView(content)
        val host = android.widget.FrameLayout(context).apply {
            setBackgroundColor(context.getColor(R.color.chat_bg))
            addView(content, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER))
        }
        shoot(name, host, shotHeight = 1600)
    }

    @Test fun `selection ask has shared styling and clear validation`() {
        val form = selectionPrompt()
        form.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        dialogShot("audit-selection", form, form.context)
    }

    @Test @Config(qualifiers = "w320dp-h914dp-xxhdpi")
    fun `answer actions stay usable with large type in a narrow window`() {
        RuntimeEnvironment.setFontScale(1.8f)
        val context = RuntimeEnvironment.getApplication()
        Chat.save(context, listOf(Turn("A small next step?", "Take a short walk. Then choose one thing to finish.")))
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shoot("audit-answer-large-font", activity.window.decorView, shotWidth = 960)
    }

    @Test fun `failed voice has an explicit typed recovery`() {
        val brain = StreamingBrain(); Brains.useForTest(brain)
        try {
            val app = RuntimeEnvironment.getApplication()
            org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
            org.robolectric.shadows.ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
            val activity = Robolectric.buildActivity(VoiceActivity::class.java).setup().get()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            org.robolectric.Shadows.shadowOf(org.robolectric.shadows.ShadowSpeechRecognizer.getLatestSpeechRecognizer())
                .triggerOnResults(android.os.Bundle().apply { putStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Help me plan tomorrow")) })
            brain.fail("The model is still downloading. Try again shortly.")
            shoot("audit-voice-recovery", activity.window.decorView)
        } finally { Brains.useForTest(null) }
    }

    @Test fun `workspace today library and capture`() {
        val context=RuntimeEnvironment.getApplication()
        WorkspaceStore(context).use { store ->
            val project=store.save(Record(kind="project",title="A calmer everyday",body="Make room for the things that matter.",pinned=true))
            val note=store.save(Record(title="An idea for tomorrow",body="Start with a walk. Capture three ideas for the new project, then choose one small next step.",pinned=true))
            store.link(note.id,project.id)
            store.save(Record(kind="task",title="Write the first three examples",body="Keep them practical and easy to try."))
        }
        val today=Robolectric.buildActivity(WorkspaceActivity::class.java,WorkspaceActivity.intent(context,"today")).setup()
        shoot("workspace-today",today.get().window.decorView)
        today.pause().stop().destroy()
        val library=Robolectric.buildActivity(WorkspaceActivity::class.java,WorkspaceActivity.intent(context,"library")).setup()
        shoot("workspace-library",library.get().window.decorView)
        library.pause().stop().destroy()
        val id=WorkspaceStore(context).use { it.list(kind="note").single().id }
        val edit=Robolectric.buildActivity(WorkspaceActivity::class.java,WorkspaceActivity.intent(context,"library",id)).setup()
        shoot("workspace-note",org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView)
        edit.pause().stop().destroy()
    }

    @Test @Config(qualifiers="w360dp-h800dp-xxhdpi-night") fun `workspace dark at large font`() {
        RuntimeEnvironment.setFontScale(1.5f)
        val activity=Robolectric.buildActivity(WorkspaceActivity::class.java,WorkspaceActivity.intent(RuntimeEnvironment.getApplication(),"library")).setup()
        shoot("workspace-library-night",activity.get().window.decorView,shotWidth=1080,shotHeight=2400)
        activity.pause().stop().destroy()
    }
}

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
    /** [backdrop] stands in for the app behind a translucent panel, as on the phone. */
    private fun shoot(name: String, decor: View, minPainted: Double = 0.9, shotWidth: Int = width, shotHeight: Int = height, backdrop: Bitmap? = null, afterLayout: () -> Unit = {}) {
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
        fun invalidateTree(view: View) {
            view.invalidate()
            if (view is android.view.ViewGroup) (0 until view.childCount).forEach { invalidateTree(view.getChildAt(it)) }
        }
        invalidateTree(decor)
        bitmap.eraseColor(Color.MAGENTA)
        if (backdrop != null) Canvas(bitmap).drawBitmap(backdrop, null, android.graphics.Rect(0, 0, shotWidth, shotHeight), null)
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

    /** Three box-blur passes at quarter scale: close to the soft blur Android draws behind a window. */
    private fun softBlur(source: Bitmap, scale: Int = 4, radius: Int = 11): Bitmap {
        val small = Bitmap.createScaledBitmap(source, maxOf(1, source.width / scale), maxOf(1, source.height / scale), true)
        val w = small.width; val h = small.height
        var px = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
        fun pass(src: IntArray, across: Boolean): IntArray {
            val dst = IntArray(src.size); val lines = if (across) h else w; val len = if (across) w else h; val n = 2 * radius + 1
            for (line in 0 until lines) {
                fun at(i: Int) = i.coerceIn(0, len - 1).let { src[if (across) line * w + it else it * w + line] }
                val sum = IntArray(4)
                fun add(c: Int, sign: Int) { sum[0] += sign * (c ushr 24); sum[1] += sign * ((c shr 16) and 255); sum[2] += sign * ((c shr 8) and 255); sum[3] += sign * (c and 255) }
                for (i in -radius..radius) add(at(i), 1)
                for (i in 0 until len) {
                    dst[if (across) line * w + i else i * w + line] = ((sum[0] / n) shl 24) or ((sum[1] / n) shl 16) or ((sum[2] / n) shl 8) or (sum[3] / n)
                    add(at(i + radius + 1), 1); add(at(i - radius), -1)
                }
            }
            return dst
        }
        repeat(3) { px = pass(pass(px, true), false) }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
        return Bitmap.createScaledBitmap(out, source.width, source.height, true)
    }

    /**
     * A floating window drawn where Android places it: at the bottom, above a
     * gesture navigation bar, over [backdrop]. Android blurs what is behind the
     * sheet; the render approximates that blur with [softBlur].
     */
    private fun shootSheet(name: String, activity: android.app.Activity, backdrop: Bitmap) {
        val decor = activity.window.decorView
        val attrs = activity.window.attributes
        fun place() {
            decor.measure(View.MeasureSpec.makeMeasureSpec(attrs.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
            decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        }
        place(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); place()
        decor.viewTreeObserver.dispatchOnPreDraw()
        val screen = Bitmap.createScaledBitmap(backdrop, width, height, true)
        val bitmap = screen.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val left = (width - decor.measuredWidth) / 2
        val top = height - decor.context.dp(24) - attrs.y - decor.measuredHeight
        val area = android.graphics.Rect(left, top, left + decor.measuredWidth, top + decor.measuredHeight)
        val soft = softBlur(screen)
        val radius = decor.context.dp(28).toFloat()
        canvas.save()
        canvas.clipPath(android.graphics.Path().apply { addRoundRect(android.graphics.RectF(area), radius, radius, android.graphics.Path.Direction.CW) })
        canvas.drawBitmap(soft, area, area, null)
        canvas.restore()
        canvas.save(); canvas.translate(left.toFloat(), top.toFloat()); decor.draw(canvas); canvas.restore()
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("$name sheet is empty", decor.measuredHeight >= decor.context.dp(52))
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
        org.junit.Assert.assertEquals(ReadingActivity::class.java.name, org.robolectric.Shadows.shadowOf(activity).nextStartedActivity.component!!.className)
        listOf("Voice", "History", "AI").forEach { label ->
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

    private fun workspaceFixture():String {
        val context=RuntimeEnvironment.getApplication()
        return WorkspaceStore(context).use { store ->
            val project=store.save(Record(kind="project",title="A calmer everyday",body="Make room for the things that matter.",pinned=true))
            val note=store.save(Record(title="An idea for tomorrow",body="Start with a walk. Capture three ideas for the new project, then choose one small next step.",pinned=true))
            store.link(note.id,project.id)
            store.save(Record(kind="task",title="Write the first three examples",body="Keep them practical and easy to try."))
            note.id
        }
    }
    @Test fun `workspace today`() {
        workspaceFixture()
        val activity=Robolectric.buildActivity(WorkspaceActivity::class.java,WorkspaceActivity.intent(RuntimeEnvironment.getApplication(),"today")).setup()
        shoot("workspace-today",activity.get().window.decorView)
        activity.pause().stop().destroy()
    }
    @Test fun `workspace library`() {
        workspaceFixture()
        val activity=Robolectric.buildActivity(WorkspaceActivity::class.java,WorkspaceActivity.intent(RuntimeEnvironment.getApplication(),"library")).setup()
        shoot("workspace-library",activity.get().window.decorView)
        activity.pause().stop().destroy()
    }
    @Test fun `workspace capture`() {
        val id=workspaceFixture()
        val activity=Robolectric.buildActivity(WorkspaceActivity::class.java,WorkspaceActivity.intent(RuntimeEnvironment.getApplication(),"library",id)).setup()
        shoot("workspace-note",org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView)
        activity.pause().stop().destroy()
    }

    @Test fun `collection table renders shared records and typed values`() {
        val context=RuntimeEnvironment.getApplication()
        val id=WorkspaceStore(context).use { store ->
            val group=store.save(Record(kind="collection",title="Small possibilities"))
            store.addProperty(group.id,"Priority","number")
            val field=store.properties(group.id).single()
            for((title,priority) in listOf("A morning walk" to "1","Three ideas for the project" to "2")) {
                val note=store.save(Record(title=title))
                store.link(group.id,note.id,"member"); store.setProperty(note.id,field,priority)
            }
            group.id
        }
        val activity=Robolectric.buildActivity(WorkspaceActivity::class.java,WorkspaceActivity.intent(context,"library",id)).setup()
        fun children(v:View):List<View> = listOf(v)+if(v is android.view.ViewGroup) (0 until v.childCount).flatMap { children(v.getChildAt(it)) } else emptyList()
        val editor=org.robolectric.shadows.ShadowDialog.getLatestDialog()
        children(editor.window!!.decorView).filterIsInstance<android.widget.TextView>().first { it.text=="Table view" }.performClick()
        val table=org.robolectric.shadows.ShadowDialog.getLatestDialog()
        shoot("workspace-table",table.window!!.decorView)
        assertTrue(children(table.window!!.decorView).filterIsInstance<android.widget.TextView>().any { it.text=="A morning walk" })
        table.dismiss(); activity.pause().stop().destroy()
    }

    @Test @Config(qualifiers="w320dp-h640dp-xxhdpi") fun `compact reader controls remain reachable`() {
        WorkspaceStore(RuntimeEnvironment.getApplication()).use { it.put("reading-text","Make space for one thing at a time. Start with a small step.") }
        val activity=Robolectric.buildActivity(ReadingActivity::class.java).setup()
        shoot("workspace-reader-compact",activity.get().window.decorView,shotWidth=960,shotHeight=1920)
        fun children(v:View):List<View> = listOf(v)+if(v is android.view.ViewGroup) (0 until v.childCount).flatMap { children(v.getChildAt(it)) } else emptyList()
        for(title in listOf("Back","Play","Next")) {
            val button=children(activity.get().window.decorView).filterIsInstance<android.widget.TextView>().first { it.text==title }
            val rect=android.graphics.Rect()
            assertTrue(button.getGlobalVisibleRect(rect)); assertTrue(rect.height()>=activity.get().dp(48))
        }
        activity.pause().stop().destroy()
    }

    @Test @Config(qualifiers="w360dp-h800dp-night-xxhdpi") fun `workspace dark at large font`() {
        RuntimeEnvironment.setFontScale(1.5f)
        val activity=Robolectric.buildActivity(WorkspaceActivity::class.java,WorkspaceActivity.intent(RuntimeEnvironment.getApplication(),"library")).setup()
        shoot("workspace-library-night",activity.get().window.decorView,shotWidth=1080,shotHeight=2400)
        activity.pause().stop().destroy()
    }
    @Test fun `repaired reply formatting in the real chat`() {
        Chat.save(RuntimeEnvironment.getApplication(), listOf(Turn("Show the formatting.",
            "A **clear answer** with *emphasis*, plain sentences and `inline code`.\n\n" +
            "* Keep the original note.\n* Review the next step.\n\n" +
            "Literal values still work: 2 * 3 and snake_case.")))
        val activity=Robolectric.buildActivity(MainActivity::class.java).setup()
        shoot("reader-repair-chat",activity.get().window.decorView)
        activity.pause().stop().destroy()
    }


    @Test fun `transcribe live verbatim lines`() {
        val app=RuntimeEnvironment.getApplication()
        org.robolectric.shadows.ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val service=Robolectric.buildService(TranscriptionService::class.java).create()
        service.get().onStartCommand(android.content.Intent(app,TranscriptionService::class.java),0,1)
        fun say(text:String) { org.robolectric.Shadows.shadowOf(org.robolectric.shadows.ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnResults(android.os.Bundle().apply { putStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION,arrayListOf(text)) }); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1)) }
        say("okej så idén för onboardingen är att vi visar tre exempel"); say("och jag vill att Lina tittar på det innan fredag")
        org.robolectric.Shadows.shadowOf(org.robolectric.shadows.ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnPartialResults(android.os.Bundle().apply { putStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION,arrayListOf("sen behöver vi")) })
        val activity=Robolectric.buildActivity(TranscribeActivity::class.java).setup()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        shoot("transcribe-live",activity.get().window.decorView)
        activity.pause().stop().destroy(); service.destroy()
        org.robolectric.shadows.ShadowSpeechRecognizer.reset()
    }

    @Test @Config(qualifiers="w360dp-h800dp-night-xxhdpi") fun `transcribe ready in dark mode`() {
        TranscriptionService.clearFinished()
        WorkspaceStore(RuntimeEnvironment.getApplication()).use { Transcripts.append(it,null,1_790_000_000_000,1,4_000,"veckomöte med teamet om lanseringen","sv-SE") }
        val activity=Robolectric.buildActivity(TranscribeActivity::class.java).setup()
        shoot("transcribe-ready-night",activity.get().window.decorView,shotWidth=1080,shotHeight=2400)
        activity.pause().stop().destroy()
    }

    @Test fun `live conversation with note sources`() {
        val app=RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        app.getSharedPreferences("surfacelab",0).edit().putBoolean("live_consent",true).commit()
        WorkspaceStore(app).use { Transcripts.append(it,null,1_790_000_000_000,1,4_000,"vi bestämde att lansera onboarding på fredag med tre exempel","sv-SE") }
        var listener: LiveTransportListener?=null
        Live.keyForTest="test"; Live.transportForTest={ _,l -> listener=l; object:LiveTransport { override fun send(text:String)=true; override fun close() {} } }
        Live.audioForTest=object:LiveAudio { override fun start(onChunk:(ByteArray)->Unit,onLevel:(Float)->Unit)=true; override fun play(pcm:ByteArray) {}; override fun flush() {}; override fun stop() {} }
        try {
            val activity=Robolectric.buildActivity(LiveActivity::class.java,LiveActivity.intent(app,start=true)).setup()
            fun server(json:String) { listener!!.message(json); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
            listener!!.opened(); server("""{"setupComplete":{}}""")
            server("""{"serverContent":{"inputTranscription":{"text":"Vad bestämde vi om onboardingen?"}}}""")
            server("""{"toolCall":{"functionCalls":[{"id":"1","name":"search_notes","args":{"query":"onboarding"}}]}}""")
            server("""{"serverContent":{"outputTranscription":{"text":"Ni bestämde att lansera på fredag med tre exempel, enligt ditt transkript."},"turnComplete":true}}""")
            server("""{"serverContent":{"interimInputTranscription":{"text":"Och vem skulle"}}}""")
            shoot("live-conversation",activity.get().window.decorView)
            activity.pause().stop().destroy()
        } finally { Live.keyForTest=null; Live.transportForTest=null; Live.audioForTest=null }
    }

    @Test fun `insights review before anything is added`() {
        val app=RuntimeEnvironment.getApplication()
        val id=WorkspaceStore(app).use { store -> store.save(Record(kind="person",title="Lina")); Transcripts.append(store,null,1_790_000_000_000,1,2_000,"okej så Lina ska få utkastet innan fredag och vi visar tre exempel i Ægentica onboardingen","sv-SE").id }
        Brains.useForTest(object:SurfaceBrain {
            override val tasks=listOf(Task.ASK)
            override fun status(context:android.content.Context,onStatus:(BrainStatus)->Unit)=onStatus(BrainStatus("Test",true))
            override fun prepare(context:android.content.Context,onStatus:(BrainStatus)->Unit)=status(context,onStatus)
            override fun run(context:android.content.Context,task:Task,input:String,instruction:String,onPartial:(String)->Unit,onResult:(BrainResult)->Unit) =
                onResult(BrainResult("{\"title\":\"Onboarding med tre exempel\",\"summary\":[\"Tre exempel visas i onboardingen\"],\"tasks\":[\"Skicka utkastet till Lina före fredag\"],\"people\":[\"Lina\"],\"projects\":[\"Ægentica onboarding\"]}",true))
        })
        try {
            val activity=Robolectric.buildActivity(WorkspaceActivity::class.java,WorkspaceActivity.intent(app,"library",id)).setup()
            fun children(v:View):List<View> = listOf(v)+if(v is android.view.ViewGroup) (0 until v.childCount).flatMap { children(v.getChildAt(it)) } else emptyList()
            children(org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView).filterIsInstance<android.widget.TextView>().first { it.text=="Insights" }.performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            shoot("insights-review",org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView,minPainted=0.3)
            activity.pause().stop().destroy()
        } finally { Brains.useForTest(null) }
    }

    @Test fun `verbatim changes after polish`() {
        val app=RuntimeEnvironment.getApplication()
        val id=WorkspaceStore(app).use { store ->
            val r=Transcripts.append(store,null,1_790_000_000_000,1,3_000,"eh så vi ses på fredag och eh tar med de tre exemplen","sv-SE")
            store.save(r.copy(body="Vi ses på fredag och tar med de tre exemplen."),r.revision).id
        }
        val activity=Robolectric.buildActivity(WorkspaceActivity::class.java,WorkspaceActivity.intent(app,"library",id)).setup()
        fun children(v:View):List<View> = listOf(v)+if(v is android.view.ViewGroup) (0 until v.childCount).flatMap { children(v.getChildAt(it)) } else emptyList()
        children(org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView).filterIsInstance<android.widget.TextView>().first { it.text=="Verbatim" }.performClick()
        val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog()
        children(dialog.window!!.decorView).filterIsInstance<android.widget.TextView>().first { it.tag=="original-compare" }.performClick()
        assertTrue(children(dialog.window!!.decorView).filterIsInstance<android.widget.TextView>().any { it.tag=="original-text" && it.text is android.text.Spanned })
        shoot("verbatim-changes",dialog.window!!.decorView,minPainted=0.3)
        activity.pause().stop().destroy()
    }

    @Test fun `record audio mode ready`() {
        val app=RuntimeEnvironment.getApplication()
        TranscriptionService.clearFinished()
        app.getSharedPreferences("surfacelab",0).edit().putString("capture_mode","record").commit()
        val activity=Robolectric.buildActivity(TranscribeActivity::class.java).setup()
        val root=activity.get().window.decorView
        fun children(v:View):List<View> = listOf(v)+if(v is android.view.ViewGroup) (0 until v.childCount).flatMap { children(v.getChildAt(it)) } else emptyList()
        assertTrue(children(root).any { it.tag=="mode-record" && it.isSelected })
        shoot("transcribe-record-ready",root)
        activity.pause().stop().destroy()
    }

    @Test fun `recording note with speakers and audio`() {
        val app=RuntimeEnvironment.getApplication()
        val text="[00:00] Speaker 1: Okej, då kör vi veckomötet. Lina, hur går onboardingen?\n[00:06] Speaker 2: Bra, tre exempel är klara och jag skickar utkastet före fredag.\n[00:14] Speaker 1: Perfekt. Then let's review it together on Monday."
        val id=WorkspaceStore(app).use { store ->
            val note=store.save(Record(title="Recording · veckomöte",body=text,original=text,source=AudioNotes.SOURCE))
            java.io.File(AudioNotes.dir(app),"${note.id}-1.aac").writeBytes(ByteArray(10))
            AudioNotes.add(store,note.id,AudioNotes.Part("${note.id}-1.aac",1_520_000)); note.id
        }
        val activity=Robolectric.buildActivity(WorkspaceActivity::class.java,WorkspaceActivity.intent(app,"library",id)).setup()
        val decor=org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
        fun children(v:View):List<View> = listOf(v)+if(v is android.view.ViewGroup) (0 until v.childCount).flatMap { children(v.getChildAt(it)) } else emptyList()
        assertTrue(children(decor).any { it.tag=="audio-speakers" }); assertTrue(children(decor).none { it.tag=="audio-transcribe" })
        shoot("recording-note",decor,minPainted=0.3)
        activity.pause().stop().destroy()
        AudioNotes.dir(app).deleteRecursively()
    }

    @Test fun `assistant panel over another app`() = assistantShots("")
    @Test @Config(qualifiers = "w411dp-h914dp-night-xxhdpi") fun `assistant panel over another app in dark mode`() = assistantShots("-night")

    /** The frosted sheet over a stand-in Sheets screen whose rows run under it, so the glass shows what it blurs. */
    private fun assistantShots(suffix: String) {
        val app=RuntimeEnvironment.getApplication()
        val rows=listOf("SEO task","Hemsidetyp?","Har ni jobbat med byrå tidigare?","Kan ni ge en bakgrund om er hemsida","Viktigaste tjänsterna?","Vilka sökord tror ni är viktigast?","Målgrupp?","Säsonger?",
            "Andra typer av marknadsföring?","Har ni tidigare SEO-arbeten?","Några andra domäner?","Vilka sociala medier har ni?","Hur enkelt är det att ändra hemsidan?","Förkunskaper hos de inblandade?","Vem gör implementationerna?","Vem är kontaktperson?","Budget per månad?","Önskad start?","Konkurrenter?")
        val shot=android.graphics.Bitmap.createBitmap(1080,2400,android.graphics.Bitmap.Config.ARGB_8888).also { b ->
            // In dark mode the app behind is dark too, as Sheets follows the system theme.
            val dark=suffix.isNotEmpty()
            val c=android.graphics.Canvas(b); c.drawColor(if (dark) android.graphics.Color.rgb(31,31,31) else android.graphics.Color.WHITE)
            val p=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize=40f; color=if (dark) android.graphics.Color.rgb(227,227,227) else android.graphics.Color.rgb(32,33,36) }
            val line=android.graphics.Paint().apply { color=if (dark) android.graphics.Color.rgb(60,64,67) else android.graphics.Color.rgb(218,220,224); strokeWidth=3f }
            val green=android.graphics.Paint().apply { color=if (dark) android.graphics.Color.rgb(23,58,36) else android.graphics.Color.rgb(230,244,234) }
            c.drawRect(0f,0f,1080f,220f,android.graphics.Paint().apply { color=android.graphics.Color.rgb(11,87,208) })
            rows.forEachIndexed { i,r -> val y=320f+i*110f; if (i%4==1) c.drawRect(700f,y-70f,1080f,y+40f,green); c.drawText(r,40f,y,p); c.drawLine(0f,y+40f,1080f,y+40f,line) }
            c.drawLine(700f,220f,700f,2400f,line)
        }
        val answer="Förslag, en rad per fråga:\n1. Hemsidetyp – e-handel, tjänsteföretag eller lokal verksamhet.\n2. Tidigare byrå – vilka byråer och vad som fungerade.\n3. Bakgrund – affärsidé, historik och var sidan står i dag.\n4. Viktigaste tjänster – de 3–5 som ska ranka först.\n5. Sökord – kundens egna gissningar, att jämföra mot data."
        Brains.useForTest(object:SurfaceBrain {
            override val tasks=listOf(Task.ASSIST)
            override fun status(context:android.content.Context,onStatus:(BrainStatus)->Unit)=onStatus(BrainStatus("Test",true))
            override fun prepare(context:android.content.Context,onStatus:(BrainStatus)->Unit)=status(context,onStatus)
            override fun seesImages(context:android.content.Context)=true
            override fun run(context:android.content.Context,task:Task,input:String,instruction:String,onPartial:(String)->Unit,onResult:(BrainResult)->Unit)=onResult(BrainResult(answer,true))
        })
        AssistantActivity.blurForTest=true
        try {
            val activity=Robolectric.buildActivity(AssistantActivity::class.java,AssistantActivity.intent(app)).setup()
            ScreenContexts.text("com.google.android.apps.docs.editors.sheets",rows.joinToString("\n")); ScreenContexts.screenshot(shot)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val root=activity.get().window.decorView
            shootSheet("assistant-ready$suffix",activity.get(),shot)
            (root.findViewWithTag<android.widget.EditText>("assistant-input")).setText("Fyll i beskrivningar för raderna")
            root.findViewWithTag<View>("assistant-ask").performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            shootSheet("assistant-panel$suffix",activity.get(),shot)
            activity.pause().stop().destroy()
        } finally { Brains.useForTest(null); ScreenContexts.begin(); AssistantActivity.blurForTest=null }
    }

    @Test fun `keyboard panel with an answer`() = keyboardShot("")
    @Test @Config(qualifiers = "w411dp-h914dp-night-xxhdpi") fun `keyboard panel with an answer in dark mode`() = keyboardShot("-night")

    /** Ægentica keys where Android puts a keyboard: at the bottom, under a messaging field. */
    private fun keyboardShot(suffix: String) {
        val app = RuntimeEnvironment.getApplication()
        var finish: ((BrainResult) -> Unit)? = null
        Brains.useForTest(object : SurfaceBrain {
            override val tasks = listOf(Task.KEYBOARD)
            override fun status(context: android.content.Context, onStatus: (BrainStatus) -> Unit) = onStatus(BrainStatus("Test", true))
            override fun prepare(context: android.content.Context, onStatus: (BrainStatus) -> Unit) = status(context, onStatus)
            override fun run(context: android.content.Context, task: Task, input: String, instruction: String, onPartial: (String) -> Unit, onResult: (BrainResult) -> Unit) { finish = onResult }
        })
        try {
            val field = android.widget.EditText(app).apply { setText("hej jag kan inte komma imorgon pga jobbet men kanske på fredag istället") }
            val connection = field.onCreateInputConnection(android.view.inputmethod.EditorInfo())
            val panel = KeyboardPanel(app, object : KeyboardPanel.Host {
                override fun connection() = connection
                override fun editor() = android.view.inputmethod.EditorInfo().apply { inputType = android.text.InputType.TYPE_CLASS_TEXT }
                override fun backToKeyboard() = Unit
            })
            panel.view.findViewWithTag<View>("keys-actions").let { (it as android.view.ViewGroup).getChildAt(1) }.performClick()
            finish!!(BrainResult("Hej! Jag kan tyvärr inte komma i morgon på grund av jobbet, men kanske på fredag i stället?", true))
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val view = panel.view
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (suffix.isEmpty()) Color.rgb(246, 248, 251) else Color.rgb(15, 17, 21))
            val bubble = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(11, 87, 208) }
            canvas.drawRoundRect(android.graphics.RectF(width * 0.3f, 300f, width - 40f, 520f), 60f, 60f, bubble)
            canvas.save(); canvas.translate(0f, (height - view.measuredHeight).toFloat()); view.draw(canvas); canvas.restore()
            val dir = File("build/screenshots").apply { mkdirs() }
            File(dir, "keyboard-panel$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(view.measuredHeight > view.context.dp(200))
        } finally { Brains.useForTest(null) }
    }

    @Test fun `agent voice with a proposal`() {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("agent-voice",0).edit().clear().putString("endpoint","https://agent.example.se/token").commit()
        app.getSharedPreferences("surfacelab",0).edit().putBoolean("agent_consent",true).commit()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO,android.Manifest.permission.POST_NOTIFICATIONS)
        var listener:AgentRoomListener?=null
        AgentConfig.codeForTest="code"
        AgentConfig.httpForTest=ConnectedAI.Http { _,_,_,_ -> ConnectedAI.Response(201,"application/json",java.io.ByteArrayInputStream("""{"server_url":"wss://lk.example.se","participant_token":"t"}""".toByteArray())) {} }
        AgentRooms.forTest={ _,_,_,l -> listener=l; object:AgentRoom { override fun setMicrophone(on:Boolean) {}; override fun shareScreen(permission:android.content.Intent?,notification:android.app.Notification?,onStop:()->Unit) {}; override fun disconnect() {} } }
        try {
            val activity=Robolectric.buildActivity(AgentActivity::class.java,AgentActivity.intent(app,start=true)).setup()
            val service=Robolectric.buildService(AgentService::class.java,org.robolectric.Shadows.shadowOf(app).nextStartedService).create().startCommand(0,1)
            var n=0; while(listener==null && n<100) { Thread.sleep(20); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); n++ }
            listener!!.connected()
            listener!!.transcript("u1","Vad står det i mitt SEO-ark och vad saknas?",true,false)
            listener!!.transcript("a1","Arket listar 44 frågor till kunden. Beskrivningarna saknas för alla rader; jag kan föreslå dem en i taget.",true,true)
            listener!!.transcript("u2","Påminn mig om tio minuter att skicka det till Lina.",true,false)
            listener!!.agentState("listening")
            listener!!.call("propose_action","""{"kind":"timer","minutes":10}""")
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            shoot("agent-voice",activity.get().window.decorView)
            activity.pause().stop().destroy(); AgentService.conversation?.end(); service.destroy()
        } finally { AgentService.clear(); AgentConfig.codeForTest=null; AgentConfig.httpForTest=null; AgentRooms.forTest=null }
    }
}

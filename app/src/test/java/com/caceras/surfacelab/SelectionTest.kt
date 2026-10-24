package com.caceras.surfacelab

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.view.ActionMode
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import android.widget.EditText
import android.widget.PopupMenu
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Selecting text: Ægentica comes first on our own pages, and the one selection entry opens the genie sheet. */
@RunWith(AndroidJUnit4::class)
class SelectionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private class Mode(private val menu: Menu) : ActionMode() {
        var finished = false
        override fun setTitle(title: CharSequence?) {}
        override fun setTitle(resId: Int) {}
        override fun setSubtitle(subtitle: CharSequence?) {}
        override fun setSubtitle(resId: Int) {}
        override fun setCustomView(view: View?) {}
        override fun invalidate() {}
        override fun finish() { finished = true }
        override fun getMenu() = menu
        override fun getTitle(): CharSequence? = null
        override fun getSubtitle(): CharSequence? = null
        override fun getCustomView(): View? = null
        override fun getMenuInflater() = MenuInflater(RuntimeEnvironment.getApplication())
    }

    /** Stands in for the web view's own toolbar callback: the system's smart Translate first, then Copy and the process-text entries. */
    private inner class PageToolbar(private val editable: Boolean = false, private val selection: Boolean = true) : ActionMode.Callback {
        val clicked = mutableListOf<MenuItem>()
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean { fill(menu); return true }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean { menu.clear(); fill(menu); return true }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean { clicked += item; return true }
        override fun onDestroyActionMode(mode: ActionMode) {}
        private fun fill(menu: Menu) {
            if (selection) {
                menu.add(Menu.NONE, android.R.id.textAssist, 0, "Translate")
                if (editable) menu.add(Menu.NONE, android.R.id.cut, 4, android.R.string.cut)
                menu.add(Menu.NONE, android.R.id.copy, 5, android.R.string.copy)
            }
            menu.add(Menu.NONE, android.R.id.paste, 6, android.R.string.paste)
            menu.add(Menu.NONE, android.R.id.selectAll, 8, android.R.string.selectAll)
            if (selection) {
                menu.add(Menu.NONE, Menu.NONE, 100, "Ægentica").intent = Intent(Intent.ACTION_PROCESS_TEXT).setComponent(ComponentName(context, "com.caceras.surfacelab.Ask"))
                menu.add(Menu.NONE, Menu.NONE, 101, "Google Translate").intent = Intent(Intent.ACTION_PROCESS_TEXT).setComponent(ComponentName("com.google.android.apps.translate", "Translate"))
            }
        }
    }

    /** The order Android's floating toolbar shows: the assist slot first, then always-shown items, then by order. */
    private fun shown(menu: Menu): List<String> {
        fun flag(item: MenuItem, name: String) = runCatching { item.javaClass.getMethod(name).invoke(item) as Boolean }.getOrDefault(false)
        val items = (0 until menu.size()).map { menu.getItem(it) }.filter { it.isVisible && it.isEnabled }
        return items.sortedWith(Comparator { a, b ->
            when {
                a.itemId == android.R.id.textAssist -> if (b.itemId == android.R.id.textAssist) 0 else -1
                b.itemId == android.R.id.textAssist -> 1
                flag(a, "requiresActionButton") != flag(b, "requiresActionButton") -> if (flag(a, "requiresActionButton")) -1 else 1
                else -> a.order - b.order
            }
        }).map { it.title.toString() }
    }

    private fun menu(): Menu = PopupMenu(context, View(context)).menu

    @After fun reset() { SelectionGenie.evaluate = { web, script, done -> web.evaluateJavascript(script) { done(it) } } }

    @Test fun `on our own pages Ægentica comes first and the system's Translate moves after it, still working`() {
        val page = PageToolbar()
        var summoned = 0
        val toolbar = SelectionToolbar(context, page) { summoned++ }
        val menu = menu(); val mode = Mode(menu)
        assertTrue(toolbar.onCreateActionMode(mode, menu))
        toolbar.onPrepareActionMode(mode, menu) // the page refreshes its menu, as a web view does when the smart action arrives
        val order = shown(menu)
        assertEquals("Ægentica", order.first())
        assertEquals("Translate", order[1])
        assertEquals("our own selection entry is not listed twice", 1, order.count { it == "Ægentica" })
        assertTrue("other apps' entries stay", "Google Translate" in order)

        val ours = (0 until menu.size()).map { menu.getItem(it) }.first { it.isVisible && it.itemId == android.R.id.textAssist }
        toolbar.onActionItemClicked(mode, ours)
        assertEquals(1, summoned)
        assertTrue("the page never sees our item", page.clicked.isEmpty())

        val moved = menu.findItem(R.id.selection_assist_moved)
        toolbar.onActionItemClicked(mode, moved)
        assertEquals("the system's own action runs with its own item", android.R.id.textAssist, page.clicked.single().itemId)
        assertEquals("Translate", page.clicked.single().title.toString())
    }

    @Test fun `a lone paste toolbar is left alone`() {
        val toolbar = SelectionToolbar(context, PageToolbar(selection = false)) { fail("nothing to summon about") }
        val menu = menu()
        toolbar.onCreateActionMode(Mode(menu), menu)
        assertFalse("Ægentica" in shown(menu))
    }

    @Test fun `a selection in one of our text fields opens the genie and is replaced only after Replace`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val genie = SelectionGenie(activity)
        val field = EditText(activity).apply { setText("vi ses imorgon kl 3"); setSelection(0, 14) }
        activity.setContentView(genie.root().apply { addView(field) })
        val menu = menu(); val mode = Mode(menu)
        val toolbar = genie.wrap(field, PageToolbar(editable = true), ActionMode.TYPE_FLOATING)
        toolbar.onCreateActionMode(mode, menu)
        val ours = (0 until menu.size()).map { menu.getItem(it) }.first { it.isVisible && it.itemId == android.R.id.textAssist }
        toolbar.onActionItemClicked(mode, ours)
        assertTrue(mode.finished)
        val started = shadowOf(activity).nextStartedActivityForResult
        assertEquals(SelectionGenie.REQUEST, started.requestCode)
        assertEquals(Intent.ACTION_PROCESS_TEXT, started.intent.action)
        assertEquals("vi ses imorgon", started.intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT))
        assertFalse(started.intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true))
        assertEquals("nothing is written while the genie works", "vi ses imorgon kl 3", field.text.toString())

        genie.result(SelectionGenie.REQUEST, Activity.RESULT_CANCELED, null)
        assertEquals("closing the sheet changes nothing", "vi ses imorgon kl 3", field.text.toString())

        toolbar.onActionItemClicked(mode, ours)
        shadowOf(activity).nextStartedActivityForResult
        genie.result(SelectionGenie.REQUEST, Activity.RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, "Vi ses i morgon"))
        assertEquals("Vi ses i morgon kl 3", field.text.toString())
    }

    @Test fun `a selection on the page is read and replaced through the page, password fields give nothing`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val genie = SelectionGenie(activity)
        val web = WebView(activity)
        val scripts = mutableListOf<String>()
        var answer = """{"text":"Reduced motion","editable":true}"""
        SelectionGenie.evaluate = { _, script, done -> scripts += script; done(answer) }
        val menu = menu(); val mode = Mode(menu)
        val toolbar = genie.wrap(web, PageToolbar(), ActionMode.TYPE_FLOATING)
        toolbar.onCreateActionMode(mode, menu)
        val ours = (0 until menu.size()).map { menu.getItem(it) }.first { it.isVisible && it.itemId == android.R.id.textAssist }
        toolbar.onActionItemClicked(mode, ours); idle()
        assertEquals(SelectionGenie.PICK, scripts.single())
        val started = shadowOf(activity).nextStartedActivityForResult
        assertEquals("Reduced motion", started.intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT))
        genie.result(SelectionGenie.REQUEST, Activity.RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, "Minskade \"rörelser\""))
        assertTrue("the answer is typed over the kept range, quoted as data", scripts.last().endsWith("(\"Minskade \\\"rörelser\\\"\")"))

        answer = """{"secret":true}"""
        toolbar.onActionItemClicked(mode, ours); idle()
        assertNull("a password field is never handed over", shadowOf(activity).nextStartedActivityForResult)
    }

    @Test fun `the one selection entry is Ægentica and opens the genie sheet`() {
        val found = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), 0)
            .filter { it.activityInfo.packageName == context.packageName }.map { it.activityInfo }
        val ours = found.firstOrNull { it.name.endsWith(".Ask") }
        assertNotNull("the Ægentica entry is registered", ours)
        assertEquals(AssistantActivity::class.java.name, ours!!.targetActivity)
        assertTrue("the old per-task entries are gone", found.none { it.name.substringAfterLast('.') in setOf("Listen", "Summarize", "Proofread", "Rewrite", "Polish") })
    }
}

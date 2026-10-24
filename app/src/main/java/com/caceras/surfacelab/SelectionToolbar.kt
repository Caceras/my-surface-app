package com.caceras.surfacelab

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.text.Editable
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * The text-selection toolbar on Ægentica's own pages, with Ægentica first.
 *
 * Android always shows the toolbar's assist slot (android.R.id.textAssist) first, with its icon;
 * that is where the system puts its own smart action, such as Translate. Here Ægentica takes that
 * slot, the system's action moves just after it and still works (its click goes to the original
 * item), and the duplicate "Ægentica" entry Android lists from this app's own manifest is hidden.
 * Toolbars without a selection (a lone Paste) are left as they are.
 */
class SelectionToolbar(
    private val context: Context,
    private val inner: ActionMode.Callback,
    private val summon: (ActionMode) -> Unit,
) : ActionMode.Callback2() {
    private var ours: MenuItem? = null
    private var moved: MenuItem? = null
    /** The system's own assist item, hidden in its slot; [moved] stands in for it. */
    private var system: MenuItem? = null

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        val shown = inner.onCreateActionMode(mode, menu)
        if (shown) arrange(menu)
        return shown
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        inner.onPrepareActionMode(mode, menu)
        arrange(menu)
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (item === ours) { summon(mode); return true }
        if (item === moved) return system?.let { inner.onActionItemClicked(mode, it) } ?: false
        return inner.onActionItemClicked(mode, item)
    }

    override fun onDestroyActionMode(mode: ActionMode) = inner.onDestroyActionMode(mode)

    override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
        if (inner is ActionMode.Callback2) inner.onGetContentRect(mode, view, outRect) else super.onGetContentRect(mode, view, outRect)
    }

    private fun items(menu: Menu) = (0 until menu.size()).map { menu.getItem(it) }

    /**
     * Ægentica in the assist slot, the system's action right after it, our duplicate hidden. Safe
     * to repeat after every prepare. Items are hidden rather than removed: several can share the
     * assist id, and removeItem would take the first one, whoever owns it.
     */
    internal fun arrange(menu: Menu) {
        val all = items(menu)
        val mine = all.firstOrNull { it === ours }
        val stand = all.firstOrNull { it === moved }
        if (!hasSelection(all)) {
            mine?.isVisible = false
            if (stand != null) menu.removeItem(R.id.selection_assist_moved)
            moved = null
            system?.let { s -> if (all.any { it === s }) s.isVisible = true }
            system = null
            return
        }
        // The entry Android lists from this app's own selection alias would be a second "Ægentica".
        all.filter { it !== mine && it.intent?.component?.packageName == context.packageName }.forEach { it.isVisible = false }
        val assist = all.firstOrNull { it.itemId == ASSIST && it !== mine && it.isVisible }
        if (assist != null) {
            system = assist
            assist.isVisible = false
            val proxy = stand ?: menu.add(Menu.NONE, R.id.selection_assist_moved, 1, assist.title).also { moved = it }
            proxy.title = assist.title
            proxy.icon = assist.icon
            proxy.contentDescription = assist.contentDescription
            proxy.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        } else if (stand != null && system.let { s -> s == null || all.none { it === s } }) {
            menu.removeItem(R.id.selection_assist_moved)
            moved = null
            system = null
        }
        (mine ?: menu.add(Menu.NONE, ASSIST, 0, NAME).also { ours = it }).apply {
            isVisible = true
            title = NAME
            icon = context.getDrawable(R.drawable.rabbit_look)
            contentDescription = "Ask Ægentica about the selection"
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
    }

    companion object {
        const val NAME = "Ægentica"
        private const val ASSIST = android.R.id.textAssist

        /** A toolbar for selected text has Copy (or Cut); a lone Paste or Select all is an insertion point. */
        internal fun hasSelection(items: List<MenuItem>): Boolean = items.any { item ->
            item.isVisible && (item.itemId == android.R.id.copy || item.itemId == android.R.id.cut ||
                item.title?.toString() in setOf(systemString(android.R.string.copy), systemString(android.R.string.cut)))
        }

        private fun systemString(id: Int) = android.content.res.Resources.getSystem().getString(id)
    }
}

/** Where a selection came from, and how to put the genie's answer back. */
internal class Pick(val text: String, val editable: Boolean, val put: (String) -> Unit)

/**
 * Selections on one of Ægentica's own screens open the genie sheet in place. Wrap the screen's
 * content in [root]; the result of the sheet arrives through [result].
 */
class SelectionGenie(private val activity: Activity) {
    private var pending: Pick? = null

    /** The screen's root: catches the selection toolbar of every view inside it. */
    fun root(): FrameLayout = object : FrameLayout(activity) {
        override fun startActionModeForChild(originalView: View, callback: ActionMode.Callback, type: Int): ActionMode? =
            super.startActionModeForChild(originalView, wrap(originalView, callback, type), type)
    }

    internal fun wrap(view: View, callback: ActionMode.Callback, type: Int): ActionMode.Callback =
        if (type == ActionMode.TYPE_FLOATING) SelectionToolbar(activity, callback) { mode -> summon(view, mode) } else callback

    private fun summon(view: View, mode: ActionMode) {
        when (view) {
            is WebView -> evaluate(view, PICK) { raw ->
                mode.finish()
                pageSelection(raw, view)?.let { open(it) }
            }
            is TextView -> {
                val pick = textSelection(view)
                mode.finish()
                pick?.let { open(it) }
            }
            else -> mode.finish()
        }
    }

    private fun open(pick: Pick) {
        if (pick.text.isBlank()) return
        pending = pick
        @Suppress("DEPRECATION")
        activity.startActivityForResult(Genie.intent(activity, pick.text, pick.editable, Genie.Origin.PAGE, app = activity.packageName), REQUEST)
    }

    /** True when [requestCode] was the genie's; the answer goes back only when the owner tapped Replace. */
    fun result(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST) return false
        val pick = pending
        pending = null
        val reply = Genie.reply(resultCode, data)
        if (pick != null && pick.editable && reply != null) pick.put(reply)
        return true
    }

    private fun pageSelection(raw: String?, web: WebView): Pick? {
        val json = runCatching { JSONObject(raw ?: return null) }.getOrNull() ?: return null
        if (json.optBoolean("secret")) return null
        val text = json.optString("text").takeIf { it.isNotBlank() } ?: return null
        val view = WeakReference(web)
        return Pick(text, json.optBoolean("editable")) { reply -> view.get()?.let { evaluate(it, put(reply)) {} } }
    }

    private fun textSelection(view: TextView): Pick? {
        val start = minOf(view.selectionStart, view.selectionEnd)
        val end = maxOf(view.selectionStart, view.selectionEnd)
        if (start < 0 || end <= start || end > view.text.length) return null
        val text = view.text.subSequence(start, end).toString()
        if (KeyboardText.secret(view.inputType)) return null
        val editable = view.onCheckIsTextEditor() && view.isEnabled && view.text is Editable
        val target = WeakReference(view)
        return Pick(text, editable) { reply ->
            val field = target.get() ?: return@Pick
            val content = field.text as? Editable ?: return@Pick
            // Only the same words, still in the same place, are replaced.
            if (end <= content.length && content.subSequence(start, end).toString() == text) content.replace(start, end, reply)
        }
    }

    companion object {
        const val REQUEST = 47

        /** Test seam for the page's JavaScript; the real one runs in the page's main frame. */
        @Volatile internal var evaluate: (WebView, String, (String?) -> Unit) -> Unit = { web, script, done -> web.evaluateJavascript(script) { done(it) } }

        /**
         * The page's selection: inside a text field (its value range) or anywhere on the page. The
         * range is kept on the page so a Replace finds it after the toolbar closes. Password,
         * one-time-code and card fields give nothing.
         */
        internal const val PICK = """(function(){
var e=document.activeElement,s=window.getSelection();
if(e&&(e.tagName==='TEXTAREA'||e.tagName==='INPUT')&&typeof e.selectionStart==='number'){
if(/password/i.test(e.type)||/one-time-code|cc-/i.test(e.autocomplete||''))return {secret:true};
window.__aegenticaPick={el:e,start:e.selectionStart,end:e.selectionEnd};
return {text:e.value.substring(e.selectionStart,e.selectionEnd),editable:!e.readOnly&&!e.disabled};}
if(!s||!s.rangeCount)return {text:''};
var ed=!!(e&&e.isContentEditable);
window.__aegenticaPick={range:s.getRangeAt(0).cloneRange(),el:e,editable:ed};
return {text:s.toString(),editable:ed};})()"""

        /** Types [reply] over the kept range, so the page sees ordinary input (and can undo it). */
        internal fun put(reply: String) = """(function(v){var p=window.__aegenticaPick;window.__aegenticaPick=null;if(!p)return false;
if(p.el&&typeof p.start==='number'){p.el.focus();p.el.setSelectionRange(p.start,p.end);
if(!document.execCommand('insertText',false,v)){p.el.setRangeText(v,p.start,p.end,'end');p.el.dispatchEvent(new Event('input',{bubbles:true}));}return true;}
if(p.range&&p.editable){if(p.el)p.el.focus();var s=window.getSelection();s.removeAllRanges();s.addRange(p.range);return document.execCommand('insertText',false,v);}
return false;})(""" + JSONObject.quote(reply) + ")"
    }
}
